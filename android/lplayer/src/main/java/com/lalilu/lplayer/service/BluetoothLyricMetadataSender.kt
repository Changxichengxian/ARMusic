package com.lalilu.lplayer.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.lalilu.lmedia.lyric.LyricItem
import com.lalilu.lmedia.lyric.LyricSourceEmbedded
import com.lalilu.lmedia.lyric.LyricUtils
import com.lalilu.lmedia.lyric.findPlayingIndex
import com.lalilu.lmedia.lyric.getSentenceContent
import com.lalilu.lplayer.MPlayerKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

@OptIn(UnstableApi::class)
internal class BluetoothLyricMetadataSender(
    context: Context,
    private val player: Player,
) : Player.Listener, CoroutineScope {
    private val scopeJob = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Main + scopeJob

    private val lyricSource = LyricSourceEmbedded(context.applicationContext)
    private val originalItems = linkedMapOf<String, MediaItem>()
    private var lyricJob: Job? = null
    private var ignoreNextTransitionMediaId: String? = null

    init {
        player.addListener(this)
        MPlayerKV.enableBluetoothLyricMetadata.flow()
            .onEach { enabled ->
                if (enabled == true) {
                    restartForCurrentItem()
                } else {
                    stopSending()
                    restoreKnownItems()
                }
            }
            .launchIn(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem?.mediaId == ignoreNextTransitionMediaId) {
            ignoreNextTransitionMediaId = null
            return
        }
        restartForCurrentItem()
    }

    fun release() {
        stopSending()
        restoreKnownItems()
        player.removeListener(this)
        coroutineContext.cancel()
    }

    private fun restartForCurrentItem() {
        stopSending()
        if (MPlayerKV.enableBluetoothLyricMetadata.value != true) return

        val mediaItem = player.currentMediaItem ?: return
        val originalItem = rememberOriginalItem(mediaItem)

        lyricJob = launch {
            val lyrics = withContext(Dispatchers.IO) {
                lyricSource.loadLyric(originalItem)
                    ?.let { LyricUtils.parseLrc(it.first, it.second) }
                    ?: emptyList()
            }

            var lastText = ""
            while (isActive) {
                if (!player.isPlaying) {
                    delay(300)
                    continue
                }

                val currentText = lyrics.currentText(player.currentPosition)
                if (currentText.isNotBlank() && currentText != lastText) {
                    lastText = currentText
                    sendLyricAsMediaTitle(originalItem, currentText)
                }
                delay(300)
            }
        }
    }

    private fun stopSending() {
        lyricJob?.cancel()
        lyricJob = null
    }

    private fun rememberOriginalItem(mediaItem: MediaItem): MediaItem {
        return originalItems.getOrPut(mediaItem.mediaId) {
            val knownOriginal = originalItems[mediaItem.mediaId]
            knownOriginal ?: mediaItem
        }
    }

    private fun sendLyricAsMediaTitle(originalItem: MediaItem, lyricText: String) {
        val currentItem = player.currentMediaItem ?: return
        if (currentItem.mediaId != originalItem.mediaId) return

        val originalMetadata = originalItem.mediaMetadata
        val updatedMetadata = originalMetadata.buildUpon()
            .setTitle(lyricText)
            .setDisplayTitle(lyricText)
            .setArtist(originalMetadata.title ?: originalMetadata.artist)
            .setSubtitle(originalMetadata.artist)
            .build()

        ignoreNextTransitionMediaId = originalItem.mediaId
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            currentItem.buildUpon()
                .setMediaMetadata(updatedMetadata)
                .build()
        )
    }

    private fun restoreKnownItems() {
        if (originalItems.isEmpty()) return

        val timeline = player.currentTimeline
        val window = Timeline.Window()
        for (index in 0 until timeline.windowCount) {
            val currentItem = timeline.getWindow(index, window).mediaItem
            val originalItem = originalItems[currentItem.mediaId] ?: continue
            if (currentItem.mediaMetadata == originalItem.mediaMetadata) continue
            player.replaceMediaItem(index, originalItem)
        }
    }

    private fun List<LyricItem>.currentText(positionMs: Long): String {
        return when (val current = getOrNull(findPlayingIndex(positionMs))) {
            is LyricItem.NormalLyric -> current.content
            is LyricItem.WordsLyric -> current.getSentenceContent()
            else -> ""
        }.trim()
    }
}
