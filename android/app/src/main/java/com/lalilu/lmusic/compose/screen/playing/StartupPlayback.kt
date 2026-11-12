package com.lalilu.lmusic.compose.screen.playing

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.blankj.utilcode.util.LogUtils
import com.lalilu.lmedia.lyric.LyricItem
import com.lalilu.lmedia.lyric.LyricSourceEmbedded
import com.lalilu.lmedia.lyric.LyricUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class StartupPlayback(
    val item: MediaItem,
    val background: BlurBackgroundData?,
    val lyrics: List<LyricItem>,
)

internal val LocalStartupPlayback = compositionLocalOf<StartupPlayback?> { null }

internal suspend fun loadStartupPlayback(context: Context, item: MediaItem): StartupPlayback =
    coroutineScope {
        val cover = async {
            try {
                loadPlayingBackground(context, item)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LogUtils.w("Failed to load cover for ${item.mediaId}", error)
                null
            }
        }
        val lyrics = async { loadPlaybackLyrics(context, item) }
        StartupPlayback(item, cover.await(), lyrics.await())
    }

private suspend fun loadPlaybackLyrics(context: Context, item: MediaItem): List<LyricItem> =
    withContext(Dispatchers.IO) {
        try {
            LyricSourceEmbedded(context).loadLyric(item)
                ?.let { (text, translation) -> LyricUtils.parseLrc(text, translation) }
                .orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LogUtils.w("Failed to load lyrics for ${item.mediaId}", error)
            emptyList()
        }
    }

@Composable
internal fun rememberPlaybackLyrics(item: MediaItem?): State<List<LyricItem>> {
    val context = LocalContext.current
    val prepared = LocalStartupPlayback.current?.takeIf { it.item == item }
    val lyrics = remember { mutableStateOf(prepared?.lyrics.orEmpty()) }
    LaunchedEffect(item, prepared) {
        lyrics.value = prepared?.lyrics.orEmpty()
        if (prepared == null && item != null) lyrics.value = loadPlaybackLyrics(context, item)
    }
    return lyrics
}

@Composable
internal fun PlaybackCover(item: MediaItem?, modifier: Modifier) {
    val prepared = LocalStartupPlayback.current?.takeIf { it.item == item }?.background?.displayBitmap
    if (prepared != null) {
        val bitmap = remember(prepared) { prepared.asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        AsyncImage(
            model = item,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}
