package com.lalilu.lmusic.sync

import com.lalilu.lmusic.agent.ARMusicAgentBundleImporter
import com.lalilu.lmusic.agent.ARMusicAgentFiles
import com.lalilu.lmusic.agent.ARMusicAgentHistoryManager
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

enum class ARMusicHistorySyncMode {
    KEEP_ON_BOTH,
    DESKTOP_ONLY,
}

data class ARMusicHistorySyncOutcome(
    val desktopResult: ARMusicHistoryMergeResult,
    val importedToPhone: Int,
    val duplicatesOnPhone: Int,
    val clearedFromPhone: Int,
)

/** One LAN transaction for non-destructive history sync. Destructive clear is USB/ADB only. */
class ARMusicHistorySyncCoordinator(
    private val client: ARMusicLanSyncClient,
    private val historyManager: ARMusicAgentHistoryManager,
    private val importer: ARMusicAgentBundleImporter,
    private val files: ARMusicAgentFiles,
    private val json: Json,
) {
    suspend fun sync(
        baseUrl: String,
        mode: ARMusicHistorySyncMode = ARMusicHistorySyncMode.KEEP_ON_BOTH,
        userConfirmedDesktopOnly: Boolean = false,
    ): ARMusicHistorySyncOutcome {
        if (mode == ARMusicHistorySyncMode.DESKTOP_ONLY) {
            error("只在电脑保留模式仅允许在电脑端通过 USB/ADB 执行")
        }

        val local = json.decodeFromString<ARMusicHistoryPayload>(
            historyManager.buildPayload().toString()
        )
        val response = client.mergeHistory(baseUrl, local).getOrThrow()
        check(response.result.persisted) { "电脑端没有确认持久化，手机记录保持不变" }
        check(response.result.snapshotId == response.history.snapshotId) {
            "电脑端返回的听歌记录校验不一致，手机记录保持不变"
        }
        check(response.history.snapshotId == snapshotId(response.history.sessions)) {
            "电脑端返回的听歌记录内容校验失败，手机记录保持不变"
        }
        val returnedBySession = response.history.sessions.groupBy {
            it.syncId to it.startedAtMs
        }
        check(local.sessions.all { original ->
            returnedBySession[original.syncId to original.startedAtMs]?.any { returned ->
                returned.durationMs >= original.durationMs &&
                    returned.repeatCount >= original.repeatCount
            } == true
        }) {
            "电脑端返回内容没有完整覆盖手机记录，手机记录保持不变"
        }

        val importPath = files.historyMergeImportPath()
        files.writeTextFile(importPath, json.encodeToString(response.history))
        val imported = importer.importHistory(importPath)
        return ARMusicHistorySyncOutcome(
            desktopResult = response.result,
            importedToPhone = imported.importedHistories,
            duplicatesOnPhone = imported.duplicates,
            clearedFromPhone = 0,
        )
    }

    private fun snapshotId(sessions: List<ARMusicListeningSession>): String {
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
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("armusic-history-v2\u0000".toByteArray(Charsets.UTF_8))
        rows.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return "history-sha256-${digest.digest().joinToString("") { "%02x".format(it) }.take(32)}"
    }
}
