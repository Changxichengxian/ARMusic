package com.lalilu.lhistory

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.repository.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
@OptIn(UnstableApi::class)
@Named("history_analytics_listener")
class HistoryAnalyticsListener(
    private val historyRepo: HistoryRepository,
    private val statIdResolver: HistoryStatIdResolver,
    private val application: Application,
) : AnalyticsListener {
    private val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob()
    private var playingItem: PlayingItemHandler? = null
    private val handler = Handler(Looper.getMainLooper())
    private val settingsSp by lazy {
        application.getSharedPreferences(application.packageName, Application.MODE_PRIVATE)
    }

    init {
        loopUpdate()
    }

    fun loopUpdate() {
        saveOldPlayingItem(force = true)
        handler.postDelayed(::loopUpdate, 5000L)
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int
    ) {
        val mediaId = mediaItem?.mediaId ?: return

        when {
            playingItem == null -> {
                setNewPlayingItem(
                    mediaId = mediaId,
                    title = mediaItem.mediaMetadata.title.toString()
                )
            }

            playingItem?.mediaId != mediaId -> {
                saveOldPlayingItem()
                setNewPlayingItem(
                    mediaId = mediaId,
                    title = mediaItem.mediaMetadata.title.toString(),
                    isPlaying = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )
            }

            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                playingItem?.updateRepeatCount(1)
                saveOldPlayingItem()
            }
        }
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        if (playingItem == null) return

        playingItem?.updateIsPlaying(isPlaying)
        if (!isPlaying) {
            saveOldPlayingItem()
        }
    }

    private fun saveOldPlayingItem(force: Boolean = false) {
        val item = playingItem ?: return
        if (force) {
            item.tryUpdateDuration()
        } else {
            if (item.isPlaying) item.updateIsPlaying(false)
        }

        scope.launch {
            val thresholdMs = settingsSp.historyDurationFilterMs()
            if (!item.shouldRecord(thresholdMs)) return@launch
            val primaryKey = item.ensureSaved(historyRepo)
            historyRepo.updateHistory(
                id = primaryKey,
                duration = item.duration,
                repeatCount = item.repeatCount,
                startTime = item.startTime
            )
        }
    }

    private fun setNewPlayingItem(
        mediaId: String,
        title: String,
        isPlaying: Boolean = false
    ) = scope.launch(Dispatchers.Main.immediate) {
        val startTime = System.currentTimeMillis()
        val statIdentity = statIdResolver.resolve(mediaId, title)

        playingItem = PlayingItemHandler(
            mediaId = mediaId,
            title = title,
            parentId = statIdentity.id.takeIf { it != mediaId }.orEmpty(),
            parentTitle = statIdentity.title.takeIf { statIdentity.id != mediaId }.orEmpty(),
            startTime = startTime,
        ).apply {
            updateIsPlaying(isPlaying)
        }
    }
}

private class PlayingItemHandler(
    val mediaId: String,
    val title: String,
    val parentId: String,
    val parentTitle: String,
    val startTime: Long = System.currentTimeMillis(),
) {
    private var primaryKey: Long? = null
    var lastPlayTime = startTime
        private set
    var isPlaying: Boolean = false
        private set
    var duration: Long = 0
        private set
    var repeatCount: Int = 0
        private set

    fun updateRepeatCount(repeatCount: Int) {
        this.repeatCount += repeatCount
    }

    fun updateIsPlaying(isPlaying: Boolean) {
        if (isPlaying) {
            lastPlayTime = System.currentTimeMillis()
        } else {
            duration += System.currentTimeMillis() - lastPlayTime
        }
        this.isPlaying = isPlaying
    }

    fun tryUpdateDuration() {
        if (!isPlaying) return

        val now = System.currentTimeMillis()
        duration += now - lastPlayTime
        lastPlayTime = now
    }

    fun shouldRecord(thresholdMs: Long): Boolean {
        return duration >= thresholdMs
    }

    suspend fun ensureSaved(historyRepo: HistoryRepository): Long {
        primaryKey?.let { return it }

        return historyRepo.preSaveHistory(
            LHistory(
                contentId = mediaId,
                contentTitle = title,
                parentId = parentId,
                parentTitle = parentTitle,
                startTime = startTime,
                duration = duration,
            )
        ).also { primaryKey = it }
    }
}
