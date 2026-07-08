package com.lalilu.lmusic.compose.screen.tag

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lalilu.R
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.scanner.FileSystemScanner
import com.lalilu.lmedia.wrapper.Taglib
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

private const val COVER_MAX_SIDE = 1200
private const val COVER_MIN_SIDE_WARNING = 600

internal data class DownloadedCover(
    val bytes: ByteArray,
    val mimeType: String,
)

internal data class CoverPrepareResult(
    val cover: Result<DownloadedCover>,
    val messages: List<String> = emptyList(),
)

internal fun Throwable.mediaWritePermissionIntentSender(
    context: Context,
    uri: Uri,
): IntentSender? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is RecoverableSecurityException) {
        return userAction.actionIntent.intentSender
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && this is SecurityException && uri.scheme == "content") {
        return runCatching {
            MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
        }.getOrNull()
    }

    return null
}

internal fun DownloadedCover.fileExtension(): String {
    return when (mimeType.substringBefore(";").lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}

internal fun DownloadedCover.prepareForEmbeddedCover(context: Context): CoverPrepareResult {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) {
        return CoverPrepareResult(
            Result.failure(
                IllegalArgumentException(context.getString(R.string.tag_editor_no_image_read))
            )
        )
    }
    if (width != height) {
        return CoverPrepareResult(
            Result.failure(
                IllegalArgumentException(context.getString(R.string.tag_editor_cover_not_square))
            )
        )
    }

    val messages = buildList {
        if (width < COVER_MIN_SIDE_WARNING) add(context.getString(R.string.tag_editor_cover_too_small))
        if (width > COVER_MAX_SIDE) add(context.getString(R.string.tag_editor_cover_too_large))
    }
    val cover = if (width > COVER_MAX_SIDE) {
        resizeSquareCover(maxSide = COVER_MAX_SIDE)
    } else {
        this
    }

    return CoverPrepareResult(Result.success(cover), messages)
}

private fun DownloadedCover.resizeSquareCover(maxSide: Int): DownloadedCover {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return this
    return try {
        val scaled = Bitmap.createScaledBitmap(source, maxSide, maxSide, true)
        try {
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)
            DownloadedCover(
                bytes = output.toByteArray(),
                mimeType = "image/jpeg",
            )
        } finally {
            if (scaled != source) scaled.recycle()
        }
    } finally {
        source.recycle()
    }
}

internal fun readCoverFromUri(
    context: Context,
    uri: Uri,
): DownloadedCover? {
    return runCatching {
        val bytes = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: return null

        if (bytes.isEmpty()) return null

        DownloadedCover(
            bytes = bytes,
            mimeType = context.contentResolver.getType(uri)
                ?: guessImageMimeType(bytes)
        )
    }.getOrNull()
}

internal suspend fun refreshSavedSong(
    context: Context,
    song: LSong,
    fileSystemScanner: FileSystemScanner,
) {
    LMedia.replaceSong(song)

    fileSystemScanner.updateAsync()
    song.fileInfo.pathStr?.takeIf(String::isNotBlank)?.let { path ->
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
            LMedia.updateAsync()
        }
    } ?: LMedia.updateAsync()
}

internal suspend fun readSongCovers(
    context: Context,
    song: LSong,
): List<DownloadedCover> {
    val embedded = runCatching {
        context.contentResolver.openFileDescriptor(song.uri, "r")
            ?.use { Taglib.getPicturesWithFD(it.detachFd()) }
            ?.filter { it.isNotEmpty() }
            ?.map {
                DownloadedCover(
                    bytes = it,
                    mimeType = guessImageMimeType(it)
                )
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    if (embedded.isNotEmpty()) return embedded

    return readSongCover(context, song)
        ?.let(::listOf)
        .orEmpty()
}

internal suspend fun readSongCover(
    context: Context,
    song: LSong,
): DownloadedCover? {
    val embedded = runCatching {
        context.contentResolver.openFileDescriptor(song.uri, "r")
            ?.use { Taglib.getPictureWithFD(it.detachFd()) }
    }.getOrNull()

    if (embedded != null && embedded.isNotEmpty()) {
        return DownloadedCover(
            bytes = embedded,
            mimeType = guessImageMimeType(embedded)
        )
    }

    return listOf(song.albumCoverUri, song.artworkUri)
        .filterNotNull()
        .firstNotNullOfOrNull { readCoverFromUri(context, it) }
}

internal fun writeCoverToUri(
    context: Context,
    uri: Uri,
    cover: DownloadedCover,
): Boolean {
    return runCatching {
        context.contentResolver.openOutputStream(uri, "wt")
            ?.use { it.write(cover.bytes) } != null
    }.getOrDefault(false)
}

internal fun safeFileName(raw: String): String {
    return raw
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim()
        .take(80)
        .ifBlank { "cover" }
}

internal fun LSong.defaultTagSearchKeyword(): String {
    return listOf(metadata.title, metadata.artist)
        .map { it.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
}

internal fun currentSearchKeyword(
    title: String,
    artist: String,
    fallback: String?,
): String {
    return listOf(title, artist)
        .map { it.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
        .ifBlank { fallback.orEmpty() }
}

internal fun downloadCover(
    client: OkHttpClient,
    url: String,
): DownloadedCover? {
    return runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val body = response.body ?: return null
            val bytes = body.bytes()
            if (bytes.isEmpty()) return null

            DownloadedCover(
                bytes = bytes,
                mimeType = body.contentType()?.toString()?.substringBefore(";")
                    ?: guessImageMimeType(bytes)
            )
        }
    }.getOrNull()
}

private fun guessImageMimeType(bytes: ByteArray): String {
    return when {
        bytes.size >= 8
                && bytes[0] == 0x89.toByte()
                && bytes[1] == 'P'.code.toByte()
                && bytes[2] == 'N'.code.toByte()
                && bytes[3] == 'G'.code.toByte() -> "image/png"

        bytes.size >= 3
                && bytes[0] == 0xFF.toByte()
                && bytes[1] == 0xD8.toByte()
                && bytes[2] == 0xFF.toByte() -> "image/jpeg"

        bytes.size >= 12
                && bytes[0] == 'R'.code.toByte()
                && bytes[1] == 'I'.code.toByte()
                && bytes[2] == 'F'.code.toByte()
                && bytes[3] == 'F'.code.toByte()
                && bytes[8] == 'W'.code.toByte()
                && bytes[9] == 'E'.code.toByte()
                && bytes[10] == 'B'.code.toByte()
                && bytes[11] == 'P'.code.toByte() -> "image/webp"

        else -> "image/jpeg"
    }
}
