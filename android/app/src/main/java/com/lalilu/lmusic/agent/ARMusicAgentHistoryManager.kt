package com.lalilu.lmusic.agent

import android.os.Build
import com.lalilu.lhistory.HistoryAnalyticsListener
import com.lalilu.lhistory.HistoryMutationCoordinator
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.repository.HistoryDao
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.sync.ARMusicAndroidManifestBuilder
import com.lalilu.lmusic.sync.ARMusicHistoryIdentityStore
import com.lalilu.lplayer.MPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ARMusicAgentHistoryManager(
    private val files: ARMusicAgentFiles,
    private val historyDao: HistoryDao,
    private val manifestBuilder: ARMusicAndroidManifestBuilder,
    private val historyAnalyticsListener: HistoryAnalyticsListener,
    private val mutationCoordinator: HistoryMutationCoordinator,
    private val historyIdentityStore: ARMusicHistoryIdentityStore,
) {
    suspend fun exportHistory(outputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val payload = buildPayload()
        files.writeTextFile(outputPath, payload.toString(2))
        val count = payload.getJSONArray("sessions").length()
        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_EXPORT_HISTORY,
            message = "Exported $count listening sessions to $outputPath",
            outputPath = outputPath,
            exportedHistories = count,
            historySnapshotId = payload.getString("snapshotId"),
        )
    }

    /**
     * Destructive by design, but only after desktop sends a receipt for the exact current snapshot.
     * Any playback between export and confirmation changes snapshotId and aborts the clear.
     */
    suspend fun clearHistory(receiptPath: String, commandId: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val receipt = JSONObject(files.readTextFile(receiptPath))
        require(receipt.optString("schema") == CLEAR_RECEIPT_SCHEMA) { "清除确认文件格式不正确" }
        require(receipt.optBoolean("desktopPersisted", false)) { "电脑端尚未确认保存，手机记录不会删除" }
        require(receipt.optBoolean("userConfirmed", false)) { "缺少用户二次确认，手机记录不会删除" }
        require(commandId.isNotBlank() && receipt.optString("commandId") == commandId) {
            "清除命令编号不匹配；本次没有删除"
        }
        val now = System.currentTimeMillis()
        val expiresAt = receipt.optLong("expiresAt", -1L)
        require(expiresAt in now..(now + CLEAR_RECEIPT_MAX_LIFETIME_MS)) {
            "清除确认已过期或有效期异常；本次没有删除"
        }

        mergeKnownHistoryAliases()
        // Hashing the whole library can take minutes on a phone. It must happen before taking the
        // history mutation lock; otherwise history persistence and a song mutation can wait on
        // each other through the manifest builder.
        val trackIndex = buildHistoryTrackIndex()
        require(historyAnalyticsListener.flushBeforeExternalHistoryClear()) {
            "请先暂停播放，再重新预览并执行只在电脑保留；本次没有删除"
        }
        var clearSucceeded = false
        return try {
            val rawHistories = mutationCoordinator.withMutation {
                historyDao.mergeKnownContentIdAliases(
                    manifestBuilder.knownHistoryMediaIdAliases()
                )
                historyDao.getAllForBackup()
            }
            val current = buildPayload(trackIndex, rawHistories)
            val sessions = current.getJSONArray("sessions")
            val expectedSnapshot = receipt.optString("phoneSnapshotId")
            val expectedCount = receipt.optInt("phoneHistoryCount", -1)
            require(expectedSnapshot.isNotBlank() && expectedSnapshot == current.getString("snapshotId")) {
                "手机记录在同步后发生了变化，请重新同步；本次没有删除"
            }
            require(expectedCount == sessions.length()) { "手机记录数量校验失败；本次没有删除" }
            require(receipt.optInt("phoneRawHistoryCount", -1) == rawHistories.size) {
                "手机原始记录数量校验失败；本次没有删除"
            }
            require(receipt.optString("phoneRawSnapshotId") == current.getString("rawSnapshotId")) {
                "手机原始记录校验失败；本次没有删除"
            }
            require(receipt.optString("desktopStoreSnapshotId").isNotBlank()) {
                "电脑持久化凭据缺失；本次没有删除"
            }

            val backupPath = files.historyClearBackupPath(System.currentTimeMillis())
            files.writeTextFile(backupPath, current.toString(2))
            val backup = JSONObject(files.readTextFile(backupPath))
            require(
                backup.getString("snapshotId") == expectedSnapshot &&
                    backup.getString("rawSnapshotId") == current.getString("rawSnapshotId") &&
                    backup.getJSONArray("rawHistories").length() == rawHistories.size
            ) {
                "手机本地安全备份校验失败；本次没有删除"
            }

            val clearedCount = mutationCoordinator.withMutation {
                val clearNow = System.currentTimeMillis()
                require(expiresAt in clearNow..(clearNow + CLEAR_RECEIPT_MAX_LIFETIME_MS)) {
                    "清除确认已过期或有效期异常；本次没有删除"
                }
                require(files.markCommandConsumed(commandId)) {
                    "这个清除确认已经使用过；本次没有重复删除"
                }
                historyDao.clearIfUnchanged(rawHistories).also { count ->
                    check(count >= 0) { "手机记录在清除前发生了变化；本次没有删除" }
                }
            }
            historyAnalyticsListener.resetAfterExternalHistoryClear()
            clearSucceeded = true
            AgentCommandResult(
                ok = true,
                command = ARMusicAgentManager.COMMAND_CLEAR_HISTORY,
                message = "Cleared $clearedCount histories after verified desktop persistence",
                outputPath = backupPath,
                clearedHistories = clearedCount,
                historySnapshotId = expectedSnapshot,
            )
        } finally {
            if (!clearSucceeded) {
                historyAnalyticsListener.cancelExternalHistoryClear()
            }
        }
    }

    suspend fun restoreRawHistory(inputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val payload = JSONObject(files.readTextFile(inputPath))
        require(payload.optString("schema") == HISTORY_SCHEMA) { "原始历史备份格式不正确" }
        val rawArray = payload.optJSONArray("rawHistories")
            ?: error("备份中没有 rawHistories")
        val rawHistories = (0 until rawArray.length()).map { index ->
            val row = rawArray.optJSONObject(index) ?: error("第 ${index + 1} 条原始历史格式不正确")
            row.toRawHistory()
        }
        require(payload.optInt("rawHistoryCount", -1) == rawHistories.size) {
            "原始历史数量校验失败"
        }
        require(payload.optString("rawSnapshotId") == rawSnapshotId(rawHistories)) {
            "原始历史快照校验失败"
        }

        val (stats, aliasStats) = mutationCoordinator.withMutation {
            val restored = historyDao.restoreRawHistories(rawHistories)
            val aliases = historyDao.mergeKnownContentIdAliases(
                manifestBuilder.knownHistoryMediaIdAliases()
            )
            restored to aliases
        }
        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_RESTORE_RAW_HISTORY,
            message = "Restored ${stats.inserted}, merged ${stats.merged}, unchanged ${stats.unchanged}, skipped ${stats.skipped} raw histories",
            outputPath = inputPath,
            restoredHistories = stats.inserted,
            mergedHistories = stats.merged + aliasStats.updatedRows + aliasStats.removedDuplicates,
            skipped = stats.skipped,
            duplicates = stats.unchanged,
        )
    }

    suspend fun prepareUsbFileReplace(): AgentCommandResult {
        awaitARMusicLibraryReady()
        val initialLease = MPlayer.beginUsbFileReplaceLease(USB_FILE_REPLACE_PREPARE_TIMEOUT_MS)
        val flushed = runCatching {
            historyAnalyticsListener.flushBeforeProcessStop()
        }.getOrElse { error ->
            MPlayer.cancelUsbFileReplaceLease(initialLease.token)
            throw error
        }
        if (!flushed) {
            MPlayer.cancelUsbFileReplaceLease(initialLease.token)
            error("请先暂停播放；当前听歌时间尚未安全写入，电脑不会停止 ARMusic")
        }
        val lease = MPlayer.renewUsbFileReplaceLease(
            token = initialLease.token,
            durationMs = USB_FILE_REPLACE_LEASE_MS,
        )
        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_PREPARE_USB_FILE_REPLACE,
            message = "Paused listening session flushed; ARMusic can now be stopped for verified USB replacement",
            leaseToken = lease.token,
            leaseExpiresAt = lease.expiresAtEpochMs,
        )
    }

    suspend fun verifyUsbFileReplaceLease(inputPath: String): AgentCommandResult {
        val request = JSONObject(files.readTextFile(inputPath))
        require(request.optString("schema") == USB_FILE_REPLACE_LEASE_SCHEMA) {
            "USB 文件替换租约请求格式不正确"
        }
        val token = request.getString("leaseToken")
        MPlayer.renewUsbFileReplaceLease(token, USB_FILE_REPLACE_LEASE_MS)
        val flushed = runCatching {
            historyAnalyticsListener.flushBeforeProcessStop()
        }.getOrElse { error ->
            MPlayer.cancelUsbFileReplaceLease(token)
            throw error
        }
        if (!flushed) {
            MPlayer.cancelUsbFileReplaceLease(token)
            error("播放已恢复，USB 文件替换租约校验失败")
        }
        val lease = MPlayer.renewUsbFileReplaceLease(token, USB_FILE_REPLACE_LEASE_MS)
        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_VERIFY_USB_FILE_REPLACE_LEASE,
            message = "USB file replacement lease verified on phone monotonic clock",
            leaseToken = lease.token,
            leaseExpiresAt = lease.expiresAtEpochMs,
        )
    }

    suspend fun cancelUsbFileReplaceLease(inputPath: String): AgentCommandResult {
        val request = JSONObject(files.readTextFile(inputPath))
        require(request.optString("schema") == USB_FILE_REPLACE_LEASE_SCHEMA) {
            "USB 文件替换租约请求格式不正确"
        }
        val token = request.getString("leaseToken")
        MPlayer.cancelUsbFileReplaceLease(token)
        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_CANCEL_USB_FILE_REPLACE_LEASE,
            message = "USB file replacement lease cancelled",
            leaseToken = token,
        )
    }

    suspend fun buildPayload(): JSONObject {
        // Alias cleanup and the final snapshot each take a short history lock. The identity scan
        // (or its cache-only fast path) runs between them with no history lock held, keeping the
        // history/song coordinator hierarchy one-way.
        mergeKnownHistoryAliases()
        val trackIndex = buildHistoryTrackIndex()
        val rawHistories = mutationCoordinator.withMutation {
            historyDao.mergeKnownContentIdAliases(
                manifestBuilder.knownHistoryMediaIdAliases()
            )
            historyDao.getAllForBackup()
        }
        return buildPayload(trackIndex, rawHistories)
    }

    private suspend fun mergeKnownHistoryAliases() = mutationCoordinator.withMutation {
        historyDao.mergeKnownContentIdAliases(manifestBuilder.knownHistoryMediaIdAliases())
    }

    private suspend fun buildHistoryTrackIndex(): HistoryTrackIndex {
        val songs = LMedia.get<LSong>(blockFilter = false)
        val cachedSyncIds = songs.mapNotNull { song ->
            val title = song.metadata.title.ifBlank { song.name }
            historyIdentityStore.resolve(song.id, title)?.let { syncId -> song.id to syncId }
        }
        // export_library and a previous verified full scan persist this mapping for every song.
        // If the cache is complete, history export needs no 3.2 GB audio reread. A single miss
        // falls back to buildLocalTracks(), which re-hashes every file and refreshes the cache.
        if (cachedSyncIds.size == songs.size) {
            return HistoryTrackIndex(
                syncIdByMediaId = cachedSyncIds.toMap(),
                coveredSyncIds = cachedSyncIds.map { it.second }
                    .distinct()
                    .sorted(),
            )
        }
        val localTracks = manifestBuilder.buildLocalTracks()
        return HistoryTrackIndex(
            syncIdByMediaId = localTracks.associate { it.song.id to it.track.syncId },
            coveredSyncIds = localTracks.map { it.track.syncId }.distinct().sorted(),
        )
    }

    private fun buildPayload(
        trackIndex: HistoryTrackIndex,
        rawHistories: List<LHistory>,
    ): JSONObject {
        val sessions = rawHistories
            .filter { it.contentId.isNotBlank() && it.duration > 0L }
            .map { history ->
                val canonicalMediaId = manifestBuilder.canonicalHistoryMediaId(history.contentId)
                val syncId = historyIdentityStore.resolve(history.contentId, history.contentTitle)
                    ?: trackIndex.syncIdByMediaId[canonicalMediaId]
                    ?: historyIdentityStore.resolve(canonicalMediaId, history.contentTitle)
                    ?: "android-media-${history.contentId}"
                history.toSession(syncId)
            }
            .sortedWith(compareBy<HistorySession> { it.startedAtMs }.thenBy { it.eventId })
        val snapshotId = snapshotId(sessions)
        val rawSnapshotId = rawSnapshotId(rawHistories)

        return JSONObject()
            .put("schema", HISTORY_SCHEMA)
            .put("deviceId", androidDeviceId())
            .put("generatedAt", isoNow())
            .put("snapshotId", snapshotId)
            .put("snapshotComplete", true)
            .put("rawHistoryCount", rawHistories.size)
            .put("rawSnapshotId", rawSnapshotId)
            .put("coveredSyncIds", JSONArray(trackIndex.coveredSyncIds))
            .put("sessions", JSONArray().also { array ->
                sessions.forEach { session -> array.put(session.toJson()) }
            })
            .put("rawHistories", JSONArray().also { array ->
                rawHistories.forEach { history -> array.put(history.toRawJson()) }
            })
    }

    private fun LHistory.toSession(syncId: String): HistorySession {
        // Duration grows while one playback session is being checkpointed. Identity therefore
        // uses only song + start; desktop/phone merge keeps the largest duration and repeatCount.
        val eventKey = listOf(syncId, startTime).joinToString("\u0000")
        return HistorySession(
            eventId = "android-${sha256("armusic-android-history-v1\u0000$eventKey").take(32)}",
            syncId = syncId,
            sourceDevice = androidDeviceId(),
            startedAtMs = startTime,
            durationMs = duration.coerceAtLeast(0L),
            repeatCount = repeatCount.coerceAtLeast(0),
            mediaId = contentId,
            contentTitle = contentTitle,
        )
    }

    private fun snapshotId(sessions: List<HistorySession>): String {
        val rows = sessions.map { session ->
            listOf(
                session.eventId,
                session.syncId,
                session.sourceDevice,
                session.startedAtMs,
                session.durationMs,
                session.repeatCount,
            ).joinToString("\u0000")
        }.sorted()
        return "history-sha256-${sha256("armusic-history-v2\u0000" + rows.joinToString("" )).take(32)}"
    }

    private fun rawSnapshotId(histories: List<LHistory>): String {
        val rows = histories.map { history ->
            listOf(
                history.id,
                history.contentId,
                history.contentTitle,
                history.parentId,
                history.parentTitle,
                history.duration,
                history.repeatCount,
                history.startTime,
            ).joinToString("\u0000")
        }.sorted()
        return "raw-history-sha256-${sha256("armusic-raw-history-v1\u0000" + rows.joinToString("")).take(32)}"
    }

    private fun LHistory.toRawJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("contentId", contentId)
        .put("contentTitle", contentTitle)
        .put("parentId", parentId)
        .put("parentTitle", parentTitle)
        .put("duration", duration)
        .put("repeatCount", repeatCount)
        .put("startTime", startTime)

    private fun JSONObject.toRawHistory(): LHistory {
        require(has("id") && has("contentId") && has("startTime") && has("duration")) {
            "原始历史缺少必要字段"
        }
        return LHistory(
            id = getLong("id"),
            contentId = getString("contentId"),
            contentTitle = optString("contentTitle"),
            parentId = optString("parentId"),
            parentTitle = optString("parentTitle"),
            duration = getLong("duration"),
            repeatCount = optInt("repeatCount", 0),
            startTime = getLong("startTime"),
        )
    }

    private fun androidDeviceId(): String {
        return "android-${Build.MANUFACTURER}-${Build.MODEL}"
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "-")
    }

    private fun isoNow(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        Locale.ROOT,
    ).format(Date())

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class HistorySession(
        val eventId: String,
        val syncId: String,
        val sourceDevice: String,
        val startedAtMs: Long,
        val durationMs: Long,
        val repeatCount: Int,
        val mediaId: String,
        val contentTitle: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("eventId", eventId)
            .put("syncId", syncId)
            .put("sourceDevice", sourceDevice)
            .put("startedAtMs", startedAtMs)
            .put("durationMs", durationMs)
            .put("repeatCount", repeatCount)
            .put("mediaId", mediaId)
            .put("contentTitle", contentTitle)
    }

    private data class HistoryTrackIndex(
        val syncIdByMediaId: Map<String, String>,
        val coveredSyncIds: List<String>,
    )

    companion object {
        const val HISTORY_SCHEMA = "armusic-listening-history-v2"
        const val CLEAR_RECEIPT_SCHEMA = "armusic-history-clear-receipt-v1"
        const val CLEAR_RECEIPT_MAX_LIFETIME_MS = 10 * 60 * 1000L
        const val USB_FILE_REPLACE_LEASE_MS = 2 * 60 * 1000L
        const val USB_FILE_REPLACE_PREPARE_TIMEOUT_MS = 5 * 60 * 1000L
        const val USB_FILE_REPLACE_LEASE_SCHEMA = "armusic-usb-replace-lease-v1"
    }
}
