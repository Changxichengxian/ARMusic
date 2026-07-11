package com.lalilu.lmusic.utils.coil.fetcher

import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.lalilu.lmedia.entity.LAlbum
import okio.buffer
import okio.source

class LAlbumFetcher private constructor(
    private val options: Options,
    private val album: LAlbum
) : BaseFetcher() {
    override suspend fun fetch(): FetchResult? {
        // 作品封面优先来自歌曲文件本身。旧版 work-cover 映射只用于兼容回退，
        // 避免应用内的旧标记盖过后来写进音频文件的真实封面。
        // Album/work grids only need a thumbnail. MediaStore normally exposes the embedded cover
        // as a bounded thumbnail and avoids decoding a multi-megapixel APIC frame for every card.
        val mediaStoreCover = album.songs.firstNotNullOfOrNull {
            fetchMediaStoreCovers(options.context, it.artworkUri)
        } ?: album.songs.firstNotNullOfOrNull {
            fetchMediaStoreCovers(options.context, it.albumCoverUri)
        } ?: fetchMediaStoreCovers(options.context, album.coverUri)
        val embeddedCover = if (mediaStoreCover == null) {
            album.songs.firstNotNullOfOrNull {
                fetchCoverByTaglib(options.context, it)
            } ?: album.songs.firstNotNullOfOrNull {
                fetchCoverByRetriever(options.context, it)
            }
        } else {
            null
        }

        val overrideResult = if (mediaStoreCover == null && embeddedCover == null) {
            val override = album.coverOverride.orEmpty()
            when {
                override.startsWith(COVER_URI_PREFIX) -> {
                    fetchMediaStoreCovers(options.context, override.removePrefix(COVER_URI_PREFIX).toUri())
                }

                override.startsWith(COVER_SONG_PREFIX) -> {
                    val songId = override.removePrefix(COVER_SONG_PREFIX)
                    album.songs.firstOrNull { it.id == songId }
                        ?.let { fetchForSong(options.context, it) }
                }

                else -> null
            }
        } else {
            null
        }

        val realCover = mediaStoreCover
            ?: embeddedCover
            ?: overrideResult
        val result = realCover
            ?: ARMusicFallbackCover.create(
                identity = album.id,
                title = album.name,
                artist = album.artistName.orEmpty(),
            )

        return result.let { stream ->
            SourceFetchResult(
                source = ImageSource(stream.source().buffer(), options.fileSystem),
                mimeType = null,
                dataSource = if (realCover != null) DataSource.DISK else DataSource.MEMORY,
            )
        }
    }

    class AlbumFactory : Fetcher.Factory<LAlbum> {
        override fun create(data: LAlbum, options: Options, imageLoader: ImageLoader) =
            LAlbumFetcher(options, data)
    }

    private companion object {
        const val COVER_URI_PREFIX = "uri:"
        const val COVER_SONG_PREFIX = "song:"
    }
}
