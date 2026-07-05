package com.lalilu.lmusic.utils.coil.fetcher

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.blankj.utilcode.util.LogUtils
import com.lalilu.lmedia.extension.EXTERNAL_CONTENT_URI
import com.lalilu.lmedia.wrapper.Taglib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.io.InputStream

data class IndexedMediaItemCover(
    val item: MediaItem,
    val index: Int,
)

class IndexedMediaItemCoverFetcher(
    private val options: Options,
    private val data: IndexedMediaItemCover,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val item = data.item
        val songUri = EXTERNAL_CONTENT_URI.buildUpon()
            .appendEncodedPath(item.mediaId)
            .build()
            ?: return null

        val stream = when (item.mediaMetadata.mediaType) {
            MediaMetadata.MEDIA_TYPE_MUSIC -> {
                fetchCoverByTaglib(options.context, songUri, data.index)
                    ?: fetchCoverByRetriever(options.context, songUri)
                    ?: fetchMediaStoreCovers(options.context, item.mediaMetadata.artworkUri)
            }

            else -> fetchMediaStoreCovers(options.context, item.mediaMetadata.artworkUri)
        } ?: return null

        return SourceFetchResult(
            source = ImageSource(stream.source().buffer(), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    private suspend fun fetchCoverByTaglib(
        context: Context,
        songUri: Uri,
        index: Int,
    ): ByteArrayInputStream? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(songUri, "r")
        }.getOrElse {
            LogUtils.e(songUri, it)
            null
        }?.use { fileDescriptor ->
            val covers = Taglib.getPicturesWithFD(fileDescriptor.detachFd())
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            covers
                .getOrNull(index.coerceIn(0, (covers.size - 1).coerceAtLeast(0)))
                ?.inputStream()
        }
    }

    private suspend fun fetchCoverByRetriever(
        context: Context,
        songUri: Uri,
    ): InputStream? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, songUri)
            retriever.embeddedPicture?.inputStream()
        } catch (e: Exception) {
            LogUtils.e(songUri, e)
            null
        } finally {
            retriever.close()
            retriever.release()
        }
    }

    private suspend fun fetchMediaStoreCovers(context: Context, uri: Uri?): InputStream? {
        uri ?: return null

        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)
            }.getOrNull()
        }
    }

    class Factory : Fetcher.Factory<IndexedMediaItemCover> {
        override fun create(
            data: IndexedMediaItemCover,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = IndexedMediaItemCoverFetcher(options, data)
    }
}
