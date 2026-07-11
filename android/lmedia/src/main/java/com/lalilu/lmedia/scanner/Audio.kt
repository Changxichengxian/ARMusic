package com.lalilu.lmedia.scanner

import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.LogUtils
import com.lalilu.common.base.SourceType
import com.lalilu.lmedia.entity.FileInfo
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.entity.Metadata
import com.lalilu.lmedia.extension.mediaUri
import java.net.URLDecoder

data class Audio(
    var id: Long,
    var title: String,
    var album: String,
    var albumId: Long,
    var artist: String,
    var artistId: String,
    var albumArtist: String? = null,
    var data: String? = null,
    var dateAdded: Long? = null,
    var dateModified: Long? = null,
    var fileName: String? = null,
    var extensionMimeType: String,
    var formatMimeType: String? = null,
    var duration: Long,
    var size: Long,
    var bitrate: Int = -1,
    var date: String? = null,
    var track: Int? = null,
    var disc: Int? = null
) {
    companion object {
        const val INVALID_ARTIST = "<unknown>"
        const val INVALID_PATH = "<unknown_path>"
    }

    fun toSong(
        genre: String = "",
        fileMetadata: Metadata? = null,
    ): LSong? {
        try {
            val pathPath = runCatching { URLDecoder.decode(data, "UTF-8") }
                .getOrNull()

            val directoryPath = FileUtils.getDirName(pathPath)
                ?.takeIf(String::isNotEmpty)
                ?: "Unknown dir"

            return LSong(
                // Assert that the fields that should always exist are present. I can't confirm
                // that every device provides these fields, but it seems likely that they do.
                id = id.toString(),
                uri = id.mediaUri(),
                sourceType = SourceType.MediaStore,
                fileInfo = FileInfo(
                    fileName = fileName,
                    mimeType = extensionMimeType,
                    pathStr = pathPath ?: INVALID_PATH,
                    directoryPath = directoryPath,
                    size = size,
                    bitrate = bitrate
                ),
                // TODO 待完善填充MediaStore部分缺失的数据
                metadata = Metadata(
                    title = title,
                    album = album,
                    artist = artist,
                    albumArtist = albumArtist ?: INVALID_ARTIST,
                    composer = "",
                    lyricist = "",
                    comment = "",
                    genre = genre,
                    track = track?.toString() ?: "",
                    disc = disc?.toString() ?: "",
                    date = "",
                    work = "",
                    sameSongGroup = "",
                    duration = duration,
                    dateAdded = dateAdded ?: 0,
                    dateModified = dateModified ?: 0
                ).preferEmbeddedTags(fileMetadata),
            )
        } catch (e: Exception) {
            LogUtils.e(e.message, e)
            return null
        }
    }
}

/**
 * 文件内标签是可随音频文件迁移的真实来源；空标签才使用 MediaStore 的索引值。
 * 这里只覆盖产品明确依赖的字段，其余字段继续沿用 MediaStore，避免一次扫描改变过多行为。
 */
private fun Metadata.preferEmbeddedTags(fileMetadata: Metadata?): Metadata {
    if (fileMetadata == null) return this

    return copy(
        title = fileMetadata.title.nonBlankOr(title),
        artist = fileMetadata.artist.nonBlankOr(artist),
        album = fileMetadata.album.nonBlankOr(album),
        work = fileMetadata.work.nonBlankOr(work),
        sameSongGroup = fileMetadata.sameSongGroup.nonBlankOr(sameSongGroup),
        duration = fileMetadata.duration.takeIf { it > 0 } ?: duration,
    )
}

private fun String.nonBlankOr(fallback: String): String {
    return trim().takeIf(String::isNotEmpty) ?: fallback
}
