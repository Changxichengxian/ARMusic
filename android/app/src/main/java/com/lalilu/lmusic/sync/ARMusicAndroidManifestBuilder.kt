package com.lalilu.lmusic.sync

import android.app.Application
import android.os.Build
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ARMusicAndroidManifestBuilder(
    private val context: Application,
) {
    suspend fun buildManifest(): ARMusicSyncManifest = withContext(Dispatchers.IO) {
        val tracks = buildLocalTracks().map { it.track }

        ARMusicSyncManifest(
            libraryId = "android-${Build.MANUFACTURER}-${Build.MODEL}",
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .joinToString(" ")
                .trim()
                .ifBlank { "ARMusic Android" },
            generatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                .format(Date()),
            tracks = tracks,
        )
    }

    suspend fun findLocalTrack(syncId: String): ARMusicLocalSyncTrack? = withContext(Dispatchers.IO) {
        buildLocalTracks().firstOrNull { it.track.syncId == syncId }
    }

    private fun buildLocalTracks(): List<ARMusicLocalSyncTrack> {
        return LMedia.get<LSong>(blockFilter = false)
            .mapNotNull { song ->
                runCatching {
                    ARMusicLocalSyncTrack(track = song.toSyncTrack(), song = song)
                }.getOrNull()
            }
    }

    private fun LSong.toSyncTrack(): ARMusicSyncTrack {
        val fileName = fileInfo.fileName
            ?: fileInfo.pathStr?.substringAfterLast('/')
            ?: "$id.audio"
        val directory = fileInfo.directoryPath
            .takeIf { it.isNotBlank() && it != "Unknown dir" }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        val relativePath = listOfNotNull(directory, fileName).joinToString("/")

        return ARMusicSyncTrack(
            syncId = createSyncId(this, fileName),
            title = metadata.title.ifBlank { name },
            artist = metadata.artist.ifBlank { "未知歌手" },
            album = metadata.album.ifBlank { "本地音乐" },
            durationSeconds = metadata.duration / 1000,
            sizeBytes = fileInfo.size,
            relativePath = relativePath,
            modifiedAt = metadata.dateModified.takeIf { it > 0 }?.toString(),
            source = "android",
        )
    }

    private fun createSyncId(song: LSong, fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(song.fileInfo.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(fileName.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))

        runCatching {
            val chunkSize = 64 * 1024
            val size = song.fileInfo.size
            context.contentResolver.openFileDescriptor(song.uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    val channel = input.channel
                    val first = ByteArray(minOf(chunkSize.toLong(), size).toInt())
                    if (first.isNotEmpty()) {
                        channel.position(0)
                        channel.readFully(first)
                        digest.update(first)
                    }

                    if (size > chunkSize) {
                        val last = ByteArray(minOf(chunkSize.toLong(), size).toInt())
                        channel.position(maxOf(0L, size - last.size))
                        channel.readFully(last)
                        digest.update(last)
                    }
                }
            }
        }

        return "sha256-${digest.digest().toHex().take(32)}"
    }

    private fun java.nio.channels.FileChannel.readFully(buffer: ByteArray) {
        val byteBuffer = java.nio.ByteBuffer.wrap(buffer)
        while (byteBuffer.hasRemaining() && read(byteBuffer) != -1) {
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte)
    }
}

data class ARMusicLocalSyncTrack(
    val track: ARMusicSyncTrack,
    val song: LSong,
)
