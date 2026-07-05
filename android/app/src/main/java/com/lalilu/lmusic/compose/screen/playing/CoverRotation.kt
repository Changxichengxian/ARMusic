package com.lalilu.lmusic.compose.screen.playing

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lalilu.lmedia.extension.EXTERNAL_CONTENT_URI
import com.lalilu.lmedia.wrapper.Taglib
import com.lalilu.lmusic.datastore.SettingsSp
import com.lalilu.lmusic.utils.coil.fetcher.IndexedMediaItemCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.random.Random

private const val COVER_SWITCH_SETTLE_MS = 500L

@Composable
fun rememberRotatingCoverData(
    item: MediaItem?,
    currentPosition: Long,
    duration: Long,
    settingsSp: SettingsSp = koinInject(),
): Any? {
    val context = LocalContext.current
    val rotateMultipleCovers by settingsSp.rotateMultipleCovers
    var coverCount by remember(item?.mediaId) { mutableIntStateOf(1) }
    var coverOrder by remember(item?.mediaId) { mutableStateOf(listOf(0)) }
    var visibleCoverIndex by remember(item?.mediaId) { mutableIntStateOf(0) }
    val lastPosition = remember(item?.mediaId) { mutableLongStateOf(currentPosition) }

    LaunchedEffect(item?.mediaId) {
        val count = readEmbeddedCoverCount(context, item).coerceAtLeast(1)
        coverCount = count
        coverOrder = buildCoverOrder(count)
        visibleCoverIndex = 0
    }

    if (item == null) return null
    if (!rotateMultipleCovers || coverCount <= 1) return item

    LaunchedEffect(item.mediaId, currentPosition / 500L, coverCount) {
        val previousPosition = lastPosition.longValue
        val restarted = previousPosition > 3000L &&
                currentPosition <= 1500L &&
                currentPosition + 500L < previousPosition

        if (restarted) {
            coverOrder = buildCoverOrder(coverCount)
            visibleCoverIndex = 0
        }

        lastPosition.longValue = currentPosition
    }

    val segmentIndex = coverIndexForPosition(
        position = currentPosition,
        duration = duration,
        coverCount = coverCount,
    )
    val targetCoverIndex = coverOrder.getOrNull(segmentIndex) ?: segmentIndex

    LaunchedEffect(item.mediaId, targetCoverIndex) {
        if (visibleCoverIndex == targetCoverIndex) return@LaunchedEffect

        kotlinx.coroutines.delay(COVER_SWITCH_SETTLE_MS)
        visibleCoverIndex = targetCoverIndex
    }

    return if (visibleCoverIndex == 0) {
        item
    } else {
        IndexedMediaItemCover(item = item, index = visibleCoverIndex)
    }
}

private fun buildCoverOrder(coverCount: Int): List<Int> {
    if (coverCount <= 1) return listOf(0)
    return listOf(0) + (1 until coverCount).shuffled(Random(System.nanoTime()))
}

private fun coverIndexForPosition(
    position: Long,
    duration: Long,
    coverCount: Int,
): Int {
    if (duration <= 0L || coverCount <= 1) return 0

    val segment = duration.toDouble() / coverCount.toDouble()
    if (segment <= 0.0) return 0

    return (position / segment)
        .toInt()
        .coerceIn(0, coverCount - 1)
}

private suspend fun readEmbeddedCoverCount(
    context: Context,
    item: MediaItem?,
): Int = withContext(Dispatchers.IO) {
    if (item == null || item.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_MUSIC) {
        return@withContext 1
    }

    val songUri = EXTERNAL_CONTENT_URI.buildUpon()
        .appendEncodedPath(item.mediaId)
        .build()
        ?: return@withContext 1

    runCatching {
        context.contentResolver.openFileDescriptor(songUri, "r")
            ?.use { Taglib.getPicturesWithFD(it.detachFd()) }
            ?.count { it.isNotEmpty() }
            ?: 1
    }.getOrDefault(1)
}
