package com.lalilu.lhistory

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.repository.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
@OptIn(UnstableApi::class)
@Named("history_analytics_listener")
class HistoryAnalyticsListener(
    private val historyRepo: HistoryRepository,
    private val statIdResolver: HistoryStatIdResolver,
    private val application: Application,
    private val mutationCoordinator: HistoryMutationCoordinator,
) : AnalyticsListener {
    private val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob()
    private val persistenceRequests = Channel<PersistenceRequest>(Channel.UNLIMITED)
    private var playingItem: PlayingItemHandler? = null
    private var externalClearInProgress = false
    private var externalClearItem: PlayingItemHandler? = null
    private val handler = Handler(Looper.getMainLooper())
    private val settingsSp by lazy {
        application.getSharedPreferences(application.packageName, Application.MODE_PRIVATE)
    }

    init {
        scope.launch { processPersistenceRequests() }
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
                playingItem?.let { item ->
                    item.updateRepeatCount(1)
                    item.tryUpdateDuration()
                    queuePersistence(item)
                }
            }
        }
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        val item = playingItem ?: return

        // MPlayer immediately pauses transport while this short lease is active. Ignore the
        // transient true callback so a process stop cannot strand a millisecond-long session.
        if (isPlaying && usbFileReplaceLeaseActive()) return

        // A resumed playback during an external clear belongs to a fresh session. Keeping it
        // separate prevents resetAfterExternalHistoryClear from discarding newly played time.
        if (isPlaying && externalClearInProgress && item === externalClearItem) {
            playingItem = item.freshSession().apply { updateIsPlaying(true) }
            return
        }

        item.updateIsPlaying(isPlaying)
        if (!isPlaying) {
            saveOldPlayingItem()
        }
    }

    /**
     * Starts an externally requested destructive clear.
     *
     * The player must already be paused. All earlier persistence requests and a forced save of
     * the current below-threshold session are completed before this method returns.
     */
    suspend fun flushBeforeExternalHistoryClear(): Boolean {
        var accepted = false
        val completion = withContext(Dispatchers.Main.immediate) {
            val item = playingItem
            if (externalClearInProgress || item?.isPlaying == true) {
                return@withContext null
            }

            accepted = true
            externalClearInProgress = true
            externalClearItem = item
            item?.tryUpdateDuration()
            item?.let {
                CompletableDeferred<Unit>().also { deferred ->
                    persistenceRequests.send(
                        PersistenceRequest(
                            item = it,
                            snapshot = it.snapshot(),
                            forceRecord = true,
                            completion = deferred,
                        )
                    )
                }
            }
        }

        if (!accepted) return false
        return try {
            completion?.await()
            true
        } catch (error: Throwable) {
            cancelExternalHistoryClear()
            throw error
        }
    }

    /** Flushes a paused session before an external process stop without entering clear mode. */
    suspend fun flushBeforeProcessStop(): Boolean {
        var accepted = false
        val completion = withContext(Dispatchers.Main.immediate) {
            val item = playingItem
            if (externalClearInProgress || item?.isPlaying == true) return@withContext null
            accepted = true
            item?.tryUpdateDuration()
            item?.let {
                CompletableDeferred<Unit>().also { deferred ->
                    persistenceRequests.send(
                        PersistenceRequest(
                            item = it,
                            snapshot = it.snapshot(),
                            forceRecord = true,
                            completion = deferred,
                        )
                    )
                }
            }
        }
        if (!accepted) return false
        completion?.await()
        return true
    }

    /** Releases the clear guard after an aborted validation without changing the current item. */
    suspend fun cancelExternalHistoryClear() = withContext(Dispatchers.Main.immediate) {
        externalClearInProgress = false
        externalClearItem = null
    }

    /**
     * Drops the deleted row id after a successful clear. If playback resumed in the meantime,
     * onIsPlayingChanged has already moved it to a fresh session and that new session is kept.
     */
    suspend fun resetAfterExternalHistoryClear() = withContext(Dispatchers.Main.immediate) {
        val clearedItem = externalClearItem
        if (clearedItem != null && playingItem === clearedItem) {
            playingItem = clearedItem.freshSession()
        }
        externalClearInProgress = false
        externalClearItem = null
    }

    private fun saveOldPlayingItem(force: Boolean = false) {
        val item = playingItem ?: return
        if (force) {
            item.tryUpdateDuration()
        } else {
            if (item.isPlaying) item.updateIsPlaying(false)
        }

        queuePersistence(item)
    }

    private fun queuePersistence(item: PlayingItemHandler) {
        val result = persistenceRequests.trySend(
            PersistenceRequest(
                item = item,
                snapshot = item.snapshot(),
            )
        )
        if (result.isFailure) {
            Log.e(LOG_TAG, "Unable to queue listening history persistence", result.exceptionOrNull())
        }
    }

    private suspend fun processPersistenceRequests() {
        for (request in persistenceRequests) {
            runCatching { persist(request) }
                .onSuccess { request.completion?.complete(Unit) }
                .onFailure { error ->
                    Log.e(LOG_TAG, "Unable to persist listening history", error)
                    request.completion?.completeExceptionally(error)
                }
        }
    }

    private suspend fun persist(request: PersistenceRequest) {
        mutationCoordinator.withMutation {
            val thresholdMs = settingsSp.historyDurationFilterMs()
            val shouldRecord = if (request.forceRecord) {
                request.snapshot.duration > 0L ||
                    request.snapshot.repeatCount > 0 ||
                    request.item.hasPrimaryKey()
            } else {
                request.snapshot.duration >= thresholdMs
            }
            if (!shouldRecord) return@withMutation

            val primaryKey = request.item.ensureSaved(historyRepo, request.snapshot)
            historyRepo.updateHistory(
                id = primaryKey,
                duration = request.snapshot.duration,
                repeatCount = request.snapshot.repeatCount,
                startTime = request.snapshot.startTime,
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
            updateIsPlaying(isPlaying && !usbFileReplaceLeaseActive())
        }
    }

    private fun usbFileReplaceLeaseActive(): Boolean {
        val storedBootCount = settingsSp.getInt(USB_REPLACE_LEASE_BOOT_COUNT_KEY, -1)
        val currentBootCount = Settings.Global.getInt(
            application.contentResolver,
            Settings.Global.BOOT_COUNT,
            -2,
        )
        if (storedBootCount < 0 || storedBootCount != currentBootCount) return false
        val remaining = settingsSp.getLong(USB_REPLACE_LEASE_UNTIL_ELAPSED_KEY, 0L) -
            SystemClock.elapsedRealtime()
        return remaining in 1L..USB_REPLACE_MAX_LEASE_MS
    }

    private data class PersistenceRequest(
        val item: PlayingItemHandler,
        val snapshot: PlayingItemSnapshot,
        val forceRecord: Boolean = false,
        val completion: CompletableDeferred<Unit>? = null,
    )

    companion object {
        private const val LOG_TAG = "HistoryAnalytics"
        private const val USB_REPLACE_LEASE_UNTIL_ELAPSED_KEY =
            "armusic_usb_replace_lease_until_elapsed_ms"
        private const val USB_REPLACE_LEASE_BOOT_COUNT_KEY =
            "armusic_usb_replace_lease_boot_count"
        private const val USB_REPLACE_MAX_LEASE_MS = 10 * 60 * 1000L
    }
}

private data class PlayingItemSnapshot(
    val mediaId: String,
    val title: String,
    val parentId: String,
    val parentTitle: String,
    val startTime: Long,
    val duration: Long,
    val repeatCount: Int,
)

private class PlayingItemHandler(
    val mediaId: String,
    val title: String,
    val parentId: String,
    val parentTitle: String,
    val startTime: Long = System.currentTimeMillis(),
) {
    private var primaryKey: Long? = null
    private var lastPlayElapsedRealtime = SystemClock.elapsedRealtime()
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
        if (this.isPlaying == isPlaying) return
        if (isPlaying) {
            lastPlayElapsedRealtime = SystemClock.elapsedRealtime()
            lastPlayTime = System.currentTimeMillis()
        } else {
            accumulateElapsedDuration()
        }
        this.isPlaying = isPlaying
    }

    fun tryUpdateDuration() {
        if (!isPlaying) return

        accumulateElapsedDuration()
    }

    fun snapshot(): PlayingItemSnapshot = PlayingItemSnapshot(
        mediaId = mediaId,
        title = title,
        parentId = parentId,
        parentTitle = parentTitle,
        startTime = startTime,
        duration = duration,
        repeatCount = repeatCount,
    )

    fun freshSession(): PlayingItemHandler = PlayingItemHandler(
        mediaId = mediaId,
        title = title,
        parentId = parentId,
        parentTitle = parentTitle,
    )

    fun hasPrimaryKey(): Boolean = primaryKey != null

    suspend fun ensureSaved(
        historyRepo: HistoryRepository,
        snapshot: PlayingItemSnapshot,
    ): Long {
        primaryKey?.let { return it }

        return historyRepo.preSaveHistory(
            LHistory(
                contentId = snapshot.mediaId,
                contentTitle = snapshot.title,
                parentId = snapshot.parentId,
                parentTitle = snapshot.parentTitle,
                startTime = snapshot.startTime,
                duration = snapshot.duration,
            )
        ).also { primaryKey = it }
    }

    private fun accumulateElapsedDuration() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastPlayElapsedRealtime).coerceAtLeast(0L)
        duration = if (Long.MAX_VALUE - duration < delta) Long.MAX_VALUE else duration + delta
        lastPlayElapsedRealtime = now
        lastPlayTime = System.currentTimeMillis()
    }
}
