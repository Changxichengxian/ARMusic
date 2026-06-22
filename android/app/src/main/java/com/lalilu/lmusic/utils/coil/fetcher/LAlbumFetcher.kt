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
        val override = album.coverOverride.orEmpty()
        val overrideResult = when {
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

        val result = overrideResult
            ?: fetchMediaStoreCovers(options.context, album.coverUri)
            ?: album.songs.firstNotNullOfOrNull { fetchForSong(options.context, it) }

        return result?.let { stream ->
            SourceFetchResult(
                source = ImageSource(stream.source().buffer(), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK
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
