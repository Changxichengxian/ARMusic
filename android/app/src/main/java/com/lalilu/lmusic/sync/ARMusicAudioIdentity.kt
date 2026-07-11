package com.lalilu.lmusic.sync

import android.app.Application
import android.net.Uri
import com.lalilu.lmedia.entity.LSong
import java.io.FileInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale

data class ARMusicTrackIdentity(
    val stableId: String,
    val revisionHash: String,
    val legacyId: String,
)

/**
 * Identifies the audio payload, not the mutable ID3 container around it. Desktop uses the same
 * byte-for-byte algorithm, so editing title, work, cover or lyrics does not turn a song into a
 * second song during sync.
 */
class ARMusicAudioIdentity(
    private val context: Application,
) {
    private val cache = context.getSharedPreferences("armusic_audio_identity_v3", Application.MODE_PRIVATE)

    fun create(song: LSong, fileName: String): ARMusicTrackIdentity {
        return context.contentResolver.openFileDescriptor(song.uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use inputUse@ { input ->
                val channel = input.channel
                val size = descriptor.statSize.takeIf { it >= 0L } ?: channel.size()
                val cacheKey = sha256Hex(
                    "${song.uri}\u0000$size\u0000${song.metadata.dateModified}\u0000$fileName"
                )
                cache.getString(cacheKey, null)?.split('|')?.takeIf { it.size == 3 }?.let { fields ->
                    return@inputUse ARMusicTrackIdentity(
                        stableId = fields[0],
                        revisionHash = fields[1],
                        legacyId = fields[2],
                    )
                }
                compute(channel, size, fileName).also { identity ->
                    cache.edit().putString(
                        cacheKey,
                        listOf(identity.stableId, identity.revisionHash, identity.legacyId)
                            .joinToString("|"),
                    ).apply()
                }
            }
        } ?: error("无法读取 ${song.uri}")
    }

    fun createUncached(uri: Uri, fileName: String): ARMusicTrackIdentity {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val size = descriptor.statSize.takeIf { it >= 0L } ?: input.channel.size()
                compute(input.channel, size, fileName)
            }
        } ?: error("无法读取 $uri")
    }

    fun createUncached(file: File): ARMusicTrackIdentity {
        return FileInputStream(file).use { input ->
            compute(input.channel, file.length(), file.name)
        }
    }

    private fun compute(channel: FileChannel, size: Long, fileName: String): ARMusicTrackIdentity {
        val bounds = audioPayloadBounds(
            channel = channel,
            size = size,
            isMp3 = fileName.substringAfterLast('.', "").equals("mp3", ignoreCase = true),
        )
        val hashes = fullHashes(channel, size, bounds.first, bounds.second)
        return ARMusicTrackIdentity(
            stableId = hashes.first,
            revisionHash = hashes.second,
            legacyId = legacySyncId(channel, size, fileName),
        )
    }

    private fun fullHashes(
        channel: FileChannel,
        size: Long,
        audioStart: Long,
        audioEnd: Long,
    ): Pair<String, String> {
        val stable = MessageDigest.getInstance("SHA-256").apply {
            update("armusic-audio-v2\u0000".toByteArray(Charsets.UTF_8))
            update((audioEnd - audioStart).coerceAtLeast(0L).toString().toByteArray(Charsets.UTF_8))
        }
        val revision = MessageDigest.getInstance("SHA-256").apply {
            update("armusic-file-v1\u0000".toByteArray(Charsets.UTF_8))
            update(size.toString().toByteArray(Charsets.UTF_8))
        }
        val bytes = ByteArray(HASH_BUFFER_SIZE)
        var position = 0L
        channel.position(0L)
        while (true) {
            val buffer = ByteBuffer.wrap(bytes)
            val read = channel.read(buffer)
            if (read <= 0) break
            revision.update(bytes, 0, read)
            val chunkEnd = position + read
            val overlapStart = maxOf(position, audioStart)
            val overlapEnd = minOf(chunkEnd, audioEnd)
            if (overlapStart < overlapEnd) {
                stable.update(
                    bytes,
                    (overlapStart - position).toInt(),
                    (overlapEnd - overlapStart).toInt(),
                )
            }
            position = chunkEnd
        }
        check(position == size) { "音乐文件在计算校验值时发生变化，请重试" }
        return "audio-sha256-${stable.digest().toHex().take(32)}" to
            "file-sha256-${revision.digest().toHex().take(32)}"
    }

    private fun legacySyncId(channel: FileChannel, size: Long, fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(size.toString().toByteArray(Charsets.UTF_8))
        digest.update(fileName.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        digest.update(channel.readBytes(0L, minOf(SAMPLE_SIZE.toLong(), size).toInt()))
        if (size > SAMPLE_SIZE) {
            val lastLength = minOf(SAMPLE_SIZE.toLong(), size).toInt()
            digest.update(channel.readBytes(size - lastLength, lastLength))
        }
        return "sha256-${digest.digest().toHex().take(32)}"
    }

    private fun audioPayloadBounds(
        channel: FileChannel,
        size: Long,
        isMp3: Boolean,
    ): Pair<Long, Long> {
        if (!isMp3) return 0L to size
        var start = 0L
        var end = size

        if (size >= 10) {
            val header = channel.readBytes(0L, 10)
            if (header.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) &&
                header.copyOfRange(6, 10).all { it.toInt() and 0x80 == 0 }
            ) {
                val tagSize = ((header[6].toLong() and 0x7F) shl 21) or
                    ((header[7].toLong() and 0x7F) shl 14) or
                    ((header[8].toLong() and 0x7F) shl 7) or
                    (header[9].toLong() and 0x7F)
                val footerSize = if (header[5].toInt() and 0x10 != 0) 10L else 0L
                start = (10L + tagSize + footerSize).coerceAtMost(size)
            }
        }

        do {
            val before = end
            if (end >= 128 && channel.readBytes(end - 128, 3).contentEquals("TAG".toByteArray())) {
                end -= 128
            }

            if (end >= 32) {
                val footer = channel.readBytes(end - 32, 32)
                if (footer.copyOfRange(0, 8).contentEquals("APETAGEX".toByteArray())) {
                    val apeSize = ByteBuffer.wrap(footer, 12, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                        .toLong() and 0xFFFFFFFFL
                    if (apeSize >= 32 && apeSize <= end - start) end -= apeSize
                }
            }

            if (end >= 15 && channel.readBytes(end - 9, 9).contentEquals("LYRICS200".toByteArray())) {
                val lyricsSize = channel.readBytes(end - 15, 6)
                    .toString(Charsets.US_ASCII)
                    .toLongOrNull()
                val total = lyricsSize?.plus(15)
                if (total != null && total <= end - start) end -= total
            }
        } while (end != before)

        return if (end > start) start to end else 0L to size
    }

    private fun FileChannel.readBytes(position: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        this.position(position)
        while (buffer.hasRemaining() && read(buffer) != -1) Unit
        if (buffer.hasRemaining()) error("音乐文件读取不完整")
        return bytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    private companion object {
        const val SAMPLE_SIZE = 64 * 1024
        const val HASH_BUFFER_SIZE = 1024 * 1024
    }
}
