package com.lalilu.lmusic.sync

import android.app.Application
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ARMusicTrackDownloader(
    private val context: Application,
    private val syncClient: ARMusicLanSyncClient,
    private val audioIdentity: ARMusicAudioIdentity,
    private val freshScanner: ARMusicFreshLibraryScanner,
    private val songMutationCoordinator: ARMusicSongMutationCoordinator,
) {
    suspend fun downloadToMusicDirectory(
        baseUrl: String,
        track: ARMusicSyncTrack,
    ): Uri = withContext(Dispatchers.IO) {
        require(track.syncExclusionReason() == null) {
            track.syncExclusionReason() ?: "这首歌不在跨端同步范围"
        }
        songMutationCoordinator.withMutation {
            val before = freshScanner.scan().matching(track)
            check(before.size <= 1) { "手机曲库已有多个相同音频，下载已中止" }
            if (before.size == 1) return@withMutation before.single().uri

            val published = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                downloadWithMediaStore(baseUrl, track)
            } else {
                downloadWithFile(baseUrl, track)
            }
            val after = freshScanner.scan(requireUnique = false).matching(track)
            if (after.size > 1 && published.scheme == "content") {
                check(deletePublishedIfUnchanged(published, track)) {
                    "下载后检测到重复音频，但本次文件已发生变化；已保留现场，请人工处理"
                }
                val remaining = freshScanner.scan(requireUnique = false).matching(track)
                check(remaining.size == 1) { "下载后检测到重复音频，状态需要人工核对" }
                return@withMutation remaining.single().uri
            }
            check(after.size == 1) { "歌曲已发布，但 MediaStore 最终校验状态未知，请勿自动重试" }
            after.single().uri
        }
    }

    private suspend fun downloadWithMediaStore(
        baseUrl: String,
        track: ARMusicSyncTrack,
    ): Uri {
        val resolver = context.contentResolver
        val displayName = track.safeFileName()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, displayName.toMimeType())
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ARMusic")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建音乐文件")

        var published = false
        try {
            val received = resolver.openOutputStream(uri)?.use { output ->
                syncClient.downloadTrack(baseUrl, track.syncId, output).getOrThrow()
            } ?: error("无法写入音乐文件")
            verifyDownloaded(uri, displayName, track, received)
            check(freshScanner.scan().matching(track).isEmpty()) {
                "发布前发现手机已有这首歌；待发布副本已取消"
            }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "歌曲发布状态更新失败" }
            published = true
            awaitMediaStoreDuration(uri)
            return uri
        } catch (error: Throwable) {
            if (!published) {
                val cleaned = runCatching {
                    deletePendingIfUnchanged(uri, displayName, track)
                }.getOrDefault(false)
                if (!cleaned) {
                    error.addSuppressed(
                        IllegalStateException("本次待发布文件未能安全清理，请人工核对 $uri")
                    )
                }
                throw error
            }
            throw IllegalStateException(
                "歌曲已发布，但最终索引状态未知；已保留文件，请先只读核对，不要自动重试",
                error,
            )
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun downloadWithFile(
        baseUrl: String,
        track: ARMusicSyncTrack,
    ): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "ARMusic"
        ).apply { mkdirs() }
        val file = dir.uniqueChild(track.safeFileName())
        val extension = file.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val temporary = File(dir, ".armusic-new-${System.nanoTime()}$extension")

        val received = FileOutputStream(temporary).use { output ->
            syncClient.downloadTrack(baseUrl, track.syncId, output).getOrThrow()
        }
        try {
            verifyIdentity(audioIdentity.createUncached(temporary), track, received)
            check(!file.exists() && temporary.renameTo(file)) { "歌曲发布失败，未留下不完整文件" }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(file.name.toMimeType()),
            null
        )
        return Uri.fromFile(file)
    }

    private fun verifyDownloaded(
        uri: Uri,
        fileName: String,
        track: ARMusicSyncTrack,
        received: Long,
    ) {
        verifyIdentity(audioIdentity.createUncached(uri, fileName), track, received)
    }

    private suspend fun awaitMediaStoreDuration(uri: Uri) {
        repeat(60) {
            val duration = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DURATION),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
            if (duration >= 15_000L) return
            delay(500L)
        }
        error("歌曲已发布，但 MediaStore 尚未完成时长索引，请勿自动重试")
    }

    private fun deletePublishedIfUnchanged(uri: Uri, track: ARMusicSyncTrack): Boolean {
        val pending = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.IS_PENDING, MediaStore.Audio.Media.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            (cursor.getInt(0) != 0) to cursor.getString(1).orEmpty()
        } ?: return false
        if (pending.first) return false
        val identity = audioIdentity.createUncached(uri, pending.second)
        if (identity.revisionHash != track.revisionHash) return false
        return context.contentResolver.delete(uri, null, null) == 1
    }

    private fun deletePendingIfUnchanged(
        uri: Uri,
        displayName: String,
        track: ARMusicSyncTrack,
    ): Boolean {
        val pending = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.IS_PENDING),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null }
            ?: return false
        if (!pending) return false
        val identity = audioIdentity.createUncached(uri, displayName)
        if (identity.revisionHash != track.revisionHash) return false
        return context.contentResolver.delete(uri, null, null) == 1
    }

    private fun verifyIdentity(
        identity: ARMusicTrackIdentity,
        track: ARMusicSyncTrack,
        received: Long,
    ) {
        if (track.sizeBytes > 0L) check(received == track.sizeBytes) { "歌曲传输不完整" }
        val expectedIds = listOf(track.syncId) + track.legacySyncIds
        check(identity.stableId in expectedIds || identity.legacyId in expectedIds) {
            "歌曲音频身份校验失败"
        }
        track.revisionHash?.let { expected ->
            check(identity.revisionHash == expected) { "歌曲完整文件校验失败" }
        }
    }

    private fun ARMusicSyncTrack.safeFileName(): String {
        val rawName = relativePath.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "$syncId.mp3" }
        return rawName.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun String.toMimeType(): String {
        val extension = substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
        return extension
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "audio/mpeg"
    }

    private fun File.uniqueChild(fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ""

        var index = 0
        while (true) {
            val candidate = if (index == 0) {
                File(this, fileName)
            } else {
                File(this, "$baseName ($index)$extension")
            }
            if (!candidate.exists()) return candidate
            index += 1
        }
    }
}
