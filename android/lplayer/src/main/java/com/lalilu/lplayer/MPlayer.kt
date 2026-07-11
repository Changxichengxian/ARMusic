package com.lalilu.lplayer

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.Utils
import com.lalilu.lmedia.LMedia
import com.lalilu.lplayer.action.Action
import com.lalilu.lplayer.action.PlayerAction
import com.lalilu.lplayer.extensions.PlayMode
import com.lalilu.lplayer.extensions.playMode
import com.lalilu.lplayer.service.CustomCommand
import com.lalilu.lplayer.service.MService
import com.lalilu.lplayer.service.getHistoryItems
import com.lalilu.lplayer.service.saveHistoryIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import java.util.UUID

data class UsbFileReplaceLease(
    val token: String,
    val expiresAtEpochMs: Long,
)

@OptIn(UnstableApi::class)
object MPlayer : CoroutineScope, Player.Listener {
    override val coroutineContext: CoroutineContext = Dispatchers.IO
    private val sessionToken by lazy {
        SessionToken(Utils.getApp(), ComponentName(Utils.getApp(), MService::class.java))
    }

    private var browserInstance: MediaBrowser? = null
    private var initialized = false
    private var usbReplaceLeaseToken: String? = null
    private var usbReplaceLeaseUntilElapsedMs: Long = 0L
    private var usbReplaceLeaseExpiryJob: Job? = null
    private val browserFuture by lazy {
        MediaBrowser
            .Builder(Utils.getApp(), sessionToken)
            .buildAsync()
    }

    val module = module {
    }

    var pauseWhenCompletion: Boolean by mutableStateOf(false)
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var currentMediaItem by mutableStateOf<MediaItem?>(null)
        private set
    var currentMediaMetadata: MediaMetadata? by mutableStateOf(null)
        private set
    var currentPlaylistMetadata: MediaMetadata? by mutableStateOf(null)
        private set
    var currentDuration: Long by mutableLongStateOf(0L)
        private set
    var currentTimelineItems by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    val currentPosition: Long
        get() = runCatching { if (browserFuture.isDone) browserFuture.get()?.currentPosition else null }
            .getOrNull() ?: 0L

    val currentBufferedPosition: Long
        get() = runCatching { if (browserFuture.isDone) browserFuture.get()?.bufferedPosition else null }
            .getOrNull() ?: 0L

    fun isItemPlaying(mediaId: String): Boolean {
        if (!isPlaying) return false
        return currentMediaItem?.mediaId == mediaId
    }

    fun init() {
        if (initialized) return
        initialized = true

        launch(Dispatchers.Main) {
            val browser = browserFuture.await()
            browserInstance = browser
            browser.addListener(this@MPlayer)
            if (usbReplaceLeaseActive()) browser.pause()

            val items = getHistoryItems()
            if (items.isEmpty()) {
                LogUtils.i("No songs found")
                return@launch
            }

            browser.playWhenReady = false
            browser.setMediaItems(items)
            browser.prepare()
        }
    }

    fun doAction(action: Action) = launch(Dispatchers.Main) {
        val browser = browserFuture.await()
        if (usbReplaceLeaseActive() && action.requestsPlayback(browser)) {
            browser.pause()
            return@launch
        }

        when (action) {
            PlayerAction.Play -> browser.play()
            PlayerAction.Pause -> browser.pause()

            PlayerAction.SkipToNext -> {
                if (browser.playMode == PlayMode.Shuffle) {
                    browser.sendCustomCommand(
                        CustomCommand.SeekToNext.toSessionCommand(),
                        Bundle.EMPTY
                    )
                } else {
                    browser.seekToNext()
                }
            }

            PlayerAction.SkipToPrevious -> {
                if (browser.playMode == PlayMode.Shuffle) {
                    browser.sendCustomCommand(
                        CustomCommand.SeekToPrevious.toSessionCommand(),
                        Bundle.EMPTY
                    )
                } else {
                    browser.seekToPrevious()
                }
            }

            PlayerAction.PlayOrPause -> {
                if (browser.isPlaying) {
                    browser.pause()
                } else {
                    browser.play()
                }
            }

            is PlayerAction.PlayById -> {
                browser.getItem(action.mediaId).await().value?.let {
                    val index = browser.currentTimeline.indexOf(action.mediaId)

                    if (index == -1) {
                        val item = browser.getItem(action.mediaId)
                            .await().value ?: return@launch

                        browser.addMediaItem(0, item)
                        browser.prepare()
                        browser.play()
                    } else {
                        browser.seekTo(index, 0)
                        browser.play()
                    }
                }
            }

            is PlayerAction.SeekTo -> {
                browser.seekTo(action.positionMs)
            }

            is PlayerAction.CustomAction -> {}
            is PlayerAction.PauseWhenCompletion -> {
                pauseWhenCompletion = !action.cancel
            }

            is PlayerAction.SetPlayMode -> {
                browser.playMode = action.playMode
            }

            is PlayerAction.AddToNext -> {
                val item = browser.getItem(action.mediaId).await().value ?: return@launch
                val index = browser.currentTimeline.indexOf(action.mediaId)

                if (index != -1) {
                    val offset = if (index > browser.currentMediaItemIndex) 1 else 0
                    browser.moveMediaItem(index, browser.currentMediaItemIndex + offset)
                } else {
                    browser.addMediaItem(browser.currentMediaItemIndex + 1, item)
                }
            }

            is PlayerAction.UpdateList -> {
                val index = action.mediaId?.let { action.mediaIds.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: 0

                val items = LMedia.mapItems(action.mediaIds)
                browser.setMediaItems(items, index, 0)
                if (action.start) {
                    browser.play()
                }
            }
        }
    }

    /**
     * Blocks every app-owned resume path for a short USB replacement window.
     * The caller must already have paused playback; this method never hides an active session.
     */
    suspend fun beginUsbFileReplaceLease(durationMs: Long = 30_000L): UsbFileReplaceLease =
        withContext(Dispatchers.Main.immediate) {
            require(durationMs in 1_000L..600_000L) { "USB 文件替换租约时长不正确" }
            val browser = browserFuture.await()
            require(!browser.isPlaying) { "请先暂停播放，再执行电脑端文件替换" }

            val token = UUID.randomUUID().toString().replace("-", "")
            val lease = setUsbFileReplaceLease(token, durationMs)
            browser.pause()
            withTimeout(2_000L) {
                while (browser.isPlaying) delay(20L)
            }
            lease
        }

    suspend fun renewUsbFileReplaceLease(
        token: String,
        durationMs: Long = 30_000L,
    ): UsbFileReplaceLease = withContext(Dispatchers.Main.immediate) {
        require(usbReplaceLeaseToken == token && usbReplaceLeaseActive()) {
            "USB 文件替换租约已失效"
        }
        val browser = browserFuture.await()
        require(!browser.isPlaying) { "播放已恢复，USB 文件替换租约校验失败" }
        setUsbFileReplaceLease(token, durationMs)
    }

    suspend fun cancelUsbFileReplaceLease(token: String) = withContext(Dispatchers.Main.immediate) {
        if (usbReplaceLeaseToken == token) clearUsbFileReplaceLease()
    }

    fun replaceMediaItem(item: MediaItem) = launch(Dispatchers.Main) {
        val browser = browserFuture.await()
        val index = browser.currentTimeline.indexOf(item.mediaId)
        if (index < 0) return@launch

        val currentIndex = browser.currentMediaItemIndex
        browser.replaceMediaItem(index, item)

        if (index == currentIndex) {
            currentMediaItem = item
            currentMediaMetadata = item.mediaMetadata
            currentDuration = item.mediaMetadata.durationMs ?: browser.duration.coerceAtLeast(0L)
        }
        updateItems(browser.currentTimeline, currentIndex)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && usbReplaceLeaseActive()) {
            browserInstance?.pause()
            this@MPlayer.isPlaying = false
            return
        }
        this@MPlayer.isPlaying = isPlaying
    }

    @OptIn(UnstableApi::class)
    override fun onPlaybackStateChanged(playbackState: Int) {

    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        currentMediaItem = mediaItem
        updateItems()

        if (pauseWhenCompletion) {
            browserInstance?.pause()
            pauseWhenCompletion = false
        }
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        currentMediaItem = browserInstance?.currentMediaItem
        currentMediaMetadata = mediaMetadata
        currentDuration = mediaMetadata.durationMs ?: browserInstance?.duration ?: 0L
        // TODO 此处获取到的duration仍然可能是上一首歌曲的时长
    }

    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
        currentPlaylistMetadata = mediaMetadata
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        updateItems(timeline)
    }

    fun updateItems(
        timeline: Timeline? = browserInstance?.currentTimeline,
        currentIndex: Int = browserInstance?.currentMediaItemIndex ?: 0
    ) {
        val items = timeline?.toMediaItems() ?: emptyList()
        currentTimelineItems = items.drop(currentIndex) + items.take(currentIndex)

        val ids = currentTimelineItems.map { it.mediaId }
        saveHistoryIds(mediaIds = ids)
    }

    private fun usbReplaceLeaseActive(): Boolean {
        if (usbReplaceLeaseToken == null && !restoreUsbFileReplaceLease()) return false
        if (SystemClock.elapsedRealtime() < usbReplaceLeaseUntilElapsedMs) return true
        clearUsbFileReplaceLease()
        return false
    }

    private fun restoreUsbFileReplaceLease(): Boolean {
        val app = Utils.getApp()
        val preferences = app.getSharedPreferences(app.packageName, Context.MODE_PRIVATE)
        val storedBootCount = preferences.getInt(USB_REPLACE_LEASE_BOOT_COUNT_KEY, -1)
        val currentBootCount = Settings.Global.getInt(
            app.contentResolver,
            Settings.Global.BOOT_COUNT,
            -2,
        )
        val token = preferences.getString(USB_REPLACE_LEASE_TOKEN_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?: return false
        val untilElapsed = preferences.getLong(USB_REPLACE_LEASE_UNTIL_ELAPSED_KEY, 0L)
        val remaining = untilElapsed - SystemClock.elapsedRealtime()
        if (storedBootCount < 0 || storedBootCount != currentBootCount || remaining !in 1L..600_000L) {
            preferences.edit()
                .remove(USB_REPLACE_LEASE_UNTIL_ELAPSED_KEY)
                .remove(USB_REPLACE_LEASE_BOOT_COUNT_KEY)
                .remove(USB_REPLACE_LEASE_TOKEN_KEY)
                .commit()
            return false
        }

        usbReplaceLeaseToken = token
        usbReplaceLeaseUntilElapsedMs = untilElapsed
        usbReplaceLeaseExpiryJob?.cancel()
        usbReplaceLeaseExpiryJob = launch(Dispatchers.Main) {
            delay(remaining)
            if (usbReplaceLeaseToken == token) clearUsbFileReplaceLease()
        }
        return true
    }

    private fun clearUsbFileReplaceLease() {
        usbReplaceLeaseToken = null
        usbReplaceLeaseUntilElapsedMs = 0L
        usbReplaceLeaseExpiryJob?.cancel()
        usbReplaceLeaseExpiryJob = null
        Utils.getApp()
            .getSharedPreferences(Utils.getApp().packageName, Context.MODE_PRIVATE)
                .edit()
                .remove(USB_REPLACE_LEASE_UNTIL_ELAPSED_KEY)
                .remove(USB_REPLACE_LEASE_BOOT_COUNT_KEY)
                .remove(USB_REPLACE_LEASE_TOKEN_KEY)
                .commit()
    }

    private fun setUsbFileReplaceLease(token: String, durationMs: Long): UsbFileReplaceLease {
        require(durationMs in 1_000L..600_000L) { "USB 文件替换租约时长不正确" }
        usbReplaceLeaseToken = token
        usbReplaceLeaseUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs
        check(
            Utils.getApp()
                .getSharedPreferences(Utils.getApp().packageName, Context.MODE_PRIVATE)
                .edit()
                .putLong(USB_REPLACE_LEASE_UNTIL_ELAPSED_KEY, usbReplaceLeaseUntilElapsedMs)
                .putString(USB_REPLACE_LEASE_TOKEN_KEY, token)
                .putInt(
                    USB_REPLACE_LEASE_BOOT_COUNT_KEY,
                    Settings.Global.getInt(
                        Utils.getApp().contentResolver,
                        Settings.Global.BOOT_COUNT,
                        -1,
                    ),
                )
                .commit()
        ) { "无法持久保存 USB 文件替换租约" }
        usbReplaceLeaseExpiryJob?.cancel()
        usbReplaceLeaseExpiryJob = launch(Dispatchers.Main) {
            delay(durationMs)
            if (usbReplaceLeaseToken == token) clearUsbFileReplaceLease()
        }
        return UsbFileReplaceLease(
            token = token,
            expiresAtEpochMs = System.currentTimeMillis() + durationMs,
        )
    }

    private fun Action.requestsPlayback(browser: Player): Boolean = when (this) {
        PlayerAction.Play -> true
        PlayerAction.PlayOrPause -> !browser.isPlaying
        is PlayerAction.PlayById -> true
        is PlayerAction.UpdateList -> start
        PlayerAction.ReloadAndPlay -> true
        else -> false
    }

    private const val USB_REPLACE_LEASE_UNTIL_ELAPSED_KEY =
        "armusic_usb_replace_lease_until_elapsed_ms"
    private const val USB_REPLACE_LEASE_BOOT_COUNT_KEY =
        "armusic_usb_replace_lease_boot_count"
    private const val USB_REPLACE_LEASE_TOKEN_KEY =
        "armusic_usb_replace_lease_token"
}

private fun Timeline.toMediaItems(): List<MediaItem> {
    return (0 until this.windowCount)
        .mapNotNull { this.getWindow(it, Timeline.Window()).mediaItem }
}

private fun Timeline.indexOf(mediaId: String): Int {
    return (0 until this.windowCount).firstOrNull {
        this.getWindow(it, Timeline.Window())
            .mediaItem.mediaId == mediaId
    } ?: -1
}
