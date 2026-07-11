package com.lalilu.lmusic.agent

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lalilu.lmusic.sync.ARMusicAudioIdentity
import com.lalilu.lmusic.sync.ARMusicFreshLibraryScanner
import com.lalilu.lmusic.sync.ARMusicSongMutationCoordinator
import com.lalilu.lmusic.sync.ARMusicSyncTrack
import com.lalilu.lmusic.sync.matching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.io.File

class ARMusicAgentTrackCommitter(
    private val application: Application,
    private val files: ARMusicAgentFiles,
    private val audioIdentity: ARMusicAudioIdentity,
    private val freshScanner: ARMusicFreshLibraryScanner,
    private val songMutationCoordinator: ARMusicSongMutationCoordinator,
) {
    suspend fun verify(requestPath: String): AgentCommandResult = withContext(Dispatchers.IO) {
        val request = JSONObject(files.readTextFile(requestPath))
        require(request.optString("schema") in setOf(VERIFY_SCHEMA, REQUEST_SCHEMA)) {
            "歌曲核对请求格式不正确"
        }
        val track = request.toIdentityTrack()
        songMutationCoordinator.withMutation {
            val matches = freshScanner.scan(requireUnique = false).matching(track)
            val currentRevision = matches.singleOrNull()?.identity?.revisionHash
            val expectedRevision = request.optString("expectedRevisionHash")
                .takeIf(String::isNotBlank)
            val verified = matches.size == 1 &&
                (expectedRevision == null || currentRevision == expectedRevision)
            AgentCommandResult(
                ok = verified,
                command = ARMusicAgentManager.COMMAND_VERIFY_TRACK,
                message = when {
                    matches.isEmpty() -> "Fresh verification found no matching phone song"
                    matches.size > 1 -> "Fresh verification found duplicate phone songs"
                    expectedRevision != null && currentRevision != expectedRevision ->
                        "Phone song changed after preview; replacement rejected"
                    else -> "Fresh verification found exactly one matching phone song"
                },
                outputPath = matches.singleOrNull()?.uri?.toString(),
                verifiedSongs = matches.size,
                committedSyncId = track.syncId,
                currentRevisionHash = currentRevision,
            )
        }
    }

    suspend fun commit(requestPath: String): AgentCommandResult = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "commit_track 需要 Android 10 或更高版本"
        }
        val request = JSONObject(files.readTextFile(requestPath))
        require(request.optString("schema") == REQUEST_SCHEMA) { "歌曲发布请求格式不正确" }
        require(!request.optBoolean("replaceExisting", false)) {
            "手机端原子覆盖尚不安全；冲突歌曲已保持不变"
        }
        val temporary = files.requireAgentPath(request.getString("temporaryPath"))
        require(temporary.isFile) { "临时歌曲不存在" }
        val track = request.toTrack()
        require(track.relativePath.substringAfterLast('.', "").equals("mp3", ignoreCase = true)) {
            "跨端同步只允许 MP3"
        }
        require(track.durationSeconds >= 15L) { "少于 15 秒的音频不会跨端同步" }
        require(track.syncId.startsWith("audio-sha256-") && !track.revisionHash.isNullOrBlank()) {
            "歌曲身份或文件校验值缺失"
        }
        if (track.sizeBytes > 0L) require(temporary.length() == track.sizeBytes) { "临时歌曲大小不一致" }
        verifyIdentity(temporary, track)

        songMutationCoordinator.withMutation {
            require(temporary.isFile) { "临时歌曲在发布前消失" }
            if (track.sizeBytes > 0L) require(temporary.length() == track.sizeBytes) {
                "临时歌曲在发布前发生变化"
            }
            verifyIdentity(temporary, track)
            val before = freshScanner.scan().matching(track)
            check(before.size <= 1) { "手机曲库已存在多个相同音频，发布已中止" }
            if (before.size == 1) {
                temporary.delete()
                return@withMutation result(
                    track = track,
                    output = before.single().uri,
                    alreadyPresent = true,
                )
            }

            publishNewTrack(temporary, track)
        }
    }

    private suspend fun publishNewTrack(
        temporary: File,
        track: ARMusicSyncTrack,
    ): AgentCommandResult {
        val resolver = application.contentResolver
        val displayName = track.relativePath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "${track.syncId}.mp3" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ARMusic")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建待发布音乐文件")
        var published = false
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(temporary).use { input -> input.copyTo(output) }
            } ?: error("无法写入待发布音乐文件")
            val writtenIdentity = audioIdentity.createUncached(uri, displayName)
            check(writtenIdentity.stableId == track.syncId) { "待发布歌曲音频身份校验失败" }
            check(writtenIdentity.revisionHash == track.revisionHash) { "待发布歌曲完整文件校验失败" }

            val raced = freshScanner.scan().matching(track)
            check(raced.isEmpty()) { "发布前发现手机已有这首歌；待发布副本已取消" }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "歌曲发布状态更新失败" }
            published = true
            awaitMediaStoreDuration(uri)

            val after = freshScanner.scan(requireUnique = false).matching(track)
            if (after.size > 1) {
                check(deleteInsertedIfUnchanged(uri, displayName, track, expectedPending = false)) {
                    "发布后检测到重复音频，但本次新建文件已发生变化；已保留现场，请人工处理"
                }
                val remaining = freshScanner.scan(requireUnique = false).matching(track)
                check(remaining.size == 1) { "发布后检测到重复音频，状态需要人工核对" }
                temporary.delete()
                return result(track, remaining.single().uri, alreadyPresent = true)
            }
            check(after.size == 1) { "歌曲已发布，但 MediaStore 最终校验状态未知，请勿自动重试" }
            temporary.delete()
            return result(track, after.single().uri, alreadyPresent = false)
        } catch (error: Throwable) {
            if (!published) {
                val cleaned = runCatching {
                    deleteInsertedIfUnchanged(uri, displayName, track, expectedPending = true)
                }.getOrDefault(false)
                if (!cleaned) {
                    error.addSuppressed(
                        IllegalStateException("本次待发布文件未能安全清理，请人工核对 $uri")
                    )
                }
            }
            throw error
        }
    }

    private fun deleteInsertedIfUnchanged(
        uri: Uri,
        displayName: String,
        track: ARMusicSyncTrack,
        expectedPending: Boolean,
    ): Boolean {
        val pending = application.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.IS_PENDING),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null }
            ?: return false
        if (pending != expectedPending) return false
        val current = audioIdentity.createUncached(uri, displayName)
        if (current.revisionHash != track.revisionHash) return false
        return application.contentResolver.delete(uri, null, null) == 1
    }

    private suspend fun awaitMediaStoreDuration(uri: Uri) {
        repeat(METADATA_POLL_COUNT) {
            val duration = application.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DURATION),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
            if (duration >= 15_000L) return
            delay(METADATA_POLL_DELAY_MS)
        }
        error("歌曲已发布，但 MediaStore 尚未完成时长索引，请稍后只读核对")
    }

    private fun verifyIdentity(file: File, track: ARMusicSyncTrack) {
        val identity = audioIdentity.createUncached(file)
        val expectedIds = (listOf(track.syncId) + track.legacySyncIds).toSet()
        check(identity.stableId in expectedIds || identity.legacyId in expectedIds) {
            "临时歌曲音频身份校验失败"
        }
        check(identity.revisionHash == track.revisionHash) { "临时歌曲完整文件校验失败" }
    }

    private fun JSONObject.toTrack(): ARMusicSyncTrack {
        val legacy = optJSONArray("legacySyncIds")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return ARMusicSyncTrack(
            syncId = getString("syncId"),
            legacySyncIds = legacy,
            revisionHash = getString("revisionHash"),
            title = optString("title"),
            artist = optString("artist"),
            album = optString("album"),
            durationSeconds = getLong("durationSeconds"),
            sizeBytes = getLong("sizeBytes"),
            relativePath = getString("relativePath"),
            source = "desktop",
        )
    }

    private fun JSONObject.toIdentityTrack(): ARMusicSyncTrack {
        val legacy = optJSONArray("legacySyncIds")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return ARMusicSyncTrack(
            syncId = getString("syncId"),
            legacySyncIds = legacy,
            title = "verify",
            artist = "",
            album = "",
            durationSeconds = 15L,
            relativePath = "verify.mp3",
            source = "desktop",
        )
    }

    private fun result(
        track: ARMusicSyncTrack,
        output: Uri,
        alreadyPresent: Boolean,
    ) = AgentCommandResult(
        ok = true,
        command = ARMusicAgentManager.COMMAND_COMMIT_TRACK,
        message = if (alreadyPresent) "Song already present; no duplicate was published" else "Song committed and verified",
        outputPath = output.toString(),
        committedSongs = if (alreadyPresent) 0 else 1,
        alreadyPresent = alreadyPresent,
        committedSyncId = track.syncId,
    )

    private companion object {
        const val REQUEST_SCHEMA = "armusic-track-commit-v1"
        const val VERIFY_SCHEMA = "armusic-track-verify-v1"
        const val METADATA_POLL_COUNT = 60
        const val METADATA_POLL_DELAY_MS = 500L
    }
}
