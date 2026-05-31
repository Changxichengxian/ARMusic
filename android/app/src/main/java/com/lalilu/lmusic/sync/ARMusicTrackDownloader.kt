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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ARMusicTrackDownloader(
    private val context: Application,
    private val syncClient: ARMusicLanSyncClient,
) {
    suspend fun downloadToMusicDirectory(
        baseUrl: String,
        track: ARMusicSyncTrack,
    ): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadWithMediaStore(baseUrl, track)
        } else {
            downloadWithFile(baseUrl, track)
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

        try {
            resolver.openOutputStream(uri)?.use { output ->
                syncClient.downloadTrack(baseUrl, track.syncId, output).getOrThrow()
            } ?: error("无法写入音乐文件")

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
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

        FileOutputStream(file).use { output ->
            syncClient.downloadTrack(baseUrl, track.syncId, output).getOrThrow()
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(file.name.toMimeType()),
            null
        )
        return Uri.fromFile(file)
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
