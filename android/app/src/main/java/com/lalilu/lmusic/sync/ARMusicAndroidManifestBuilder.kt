package com.lalilu.lmusic.sync

import android.app.Application
import android.os.Build
import android.util.Log
import com.lalilu.lhistory.repository.HistoryRepository
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ARMusicAndroidManifestBuilder(
    private val context: Application,
    private val historyRepository: HistoryRepository,
    private val audioIdentity: ARMusicAudioIdentity,
    private val historyIdentityStore: ARMusicHistoryIdentityStore,
    private val songMutationCoordinator: ARMusicSongMutationCoordinator,
) {
    suspend fun buildManifest(): ARMusicSyncManifest = withContext(Dispatchers.IO) {
        val historyStats = readHistoryStats()
        songMutationCoordinator.withMutation {
            val library = buildSyncLibraryUnlocked(historyStats)

            ARMusicSyncManifest(
                libraryId = "android-${Build.MANUFACTURER}-${Build.MODEL}",
                deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                    .joinToString(" ")
                    .trim()
                    .ifBlank { "ARMusic Android" },
                generatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                    .format(Date()),
                tracks = library.eligible.map { it.track },
                ignoredTracks = library.ignored,
            )
        }
    }

    suspend fun findLocalTrack(syncId: String): ARMusicLocalSyncTrack? = withContext(Dispatchers.IO) {
        val historyStats = readHistoryStats()
        songMutationCoordinator.withMutation {
            buildSyncLibraryUnlocked(historyStats).eligible.firstOrNull { it.track.syncId == syncId }
        }
    }

    suspend fun buildSyncLibrary(): ARMusicLocalSyncLibrary = withContext(Dispatchers.IO) {
        Log.i(LOG_TAG, "buildSyncLibrary reading history stats")
        val historyStats = readHistoryStats()
        Log.i(LOG_TAG, "buildSyncLibrary waiting for song mutation lock")
        songMutationCoordinator.withMutation {
            Log.i(LOG_TAG, "buildSyncLibrary acquired song mutation lock")
            buildSyncLibraryUnlocked(historyStats)
        }.also { Log.i(LOG_TAG, "buildSyncLibrary finished local track identities") }
    }

    private fun buildSyncLibraryUnlocked(historyStats: HistoryStats): ARMusicLocalSyncLibrary {
        val eligible = mutableListOf<ARMusicLocalSyncTrack>()
        val ignored = mutableListOf<ARMusicIgnoredSyncTrack>()
        buildLocalTracksUnlocked(historyStats).forEach { local ->
            val reason = local.track.syncExclusionReason()
            if (reason == null) {
                eligible += local
            } else {
                ignored += ARMusicIgnoredSyncTrack(
                    title = local.track.title,
                    relativePath = local.track.relativePath,
                    durationSeconds = local.track.durationSeconds,
                    reason = reason,
                    source = "android",
                )
            }
        }
        return ARMusicLocalSyncLibrary(eligible = eligible, ignored = ignored)
    }

    suspend fun buildLocalTracks(): List<ARMusicLocalSyncTrack> = withContext(Dispatchers.IO) {
        // Read history before taking the song lock. History export takes its own short history
        // snapshot only after this method returns, so the two coordinators are never nested.
        val historyStats = readHistoryStats()
        songMutationCoordinator.withMutation { buildLocalTracksUnlocked(historyStats) }
    }

    private suspend fun readHistoryStats(): HistoryStats {
        Log.i(LOG_TAG, "readHistoryStats waiting for duration query")
        val durationByMediaId = historyRepository
            .getHistoriesIdsMapWithDuration()
            .first()
            .mergeRenumberedDurations()
        Log.i(LOG_TAG, "readHistoryStats duration query ready; waiting for last-time query")
        val lastTimeByMediaId = historyRepository
            .getHistoriesIdsMapWithLastTime()
            .first()
            .mergeRenumberedLastTimes()
        Log.i(LOG_TAG, "readHistoryStats queries ready")
        return HistoryStats(
            durationByMediaId = durationByMediaId,
            lastTimeByMediaId = lastTimeByMediaId,
        )
    }

    private fun buildLocalTracksUnlocked(historyStats: HistoryStats): List<ARMusicLocalSyncTrack> {
        val failures = mutableListOf<String>()
        val tracks = LMedia.get<LSong>(blockFilter = false)
            .mapNotNull { song ->
                runCatching {
                    ARMusicLocalSyncTrack(
                        track = song.toSyncTrack(
                            playDurationMs = historyStats.durationByMediaId[song.id] ?: 0L,
                            lastPlayedAtMs = historyStats.lastTimeByMediaId[song.id] ?: 0L,
                        ),
                        song = song,
                    )
                }.getOrElse { error ->
                    failures += "${song.fileInfo.pathStr ?: song.name}: ${error.message ?: error.javaClass.simpleName}"
                    null
                }
            }
        check(failures.isEmpty()) {
            "有 ${failures.size} 首歌无法完整校验，同步已中止：${failures.take(3).joinToString("；")}"
        }
        return tracks
    }

    private fun LSong.toSyncTrack(
        playDurationMs: Long,
        lastPlayedAtMs: Long,
    ): ARMusicSyncTrack {
        val fileName = fileInfo.fileName
            ?: fileInfo.pathStr?.substringAfterLast('/')
            ?: "$id.audio"
        val directory = fileInfo.directoryPath
            .takeIf { it.isNotBlank() && it != "Unknown dir" }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        val relativePath = listOfNotNull(directory, fileName).joinToString("/")

        // A same-size in-place replacement can preserve MediaStore's second-level timestamp.
        // Sync previews therefore verify the actual bytes instead of trusting the cache key.
        val identity = audioIdentity.createUncached(uri, fileName)
        val title = metadata.title.ifBlank { name }
        historyIdentityStore.remember(id, title, identity.stableId)
        return ARMusicSyncTrack(
            syncId = identity.stableId,
            legacySyncIds = listOf(identity.legacyId),
            revisionHash = identity.revisionHash,
            title = title,
            artist = metadata.artist.ifBlank { "未知歌手" },
            album = metadata.album.ifBlank { "本地音乐" },
            durationSeconds = metadata.duration / 1000,
            sizeBytes = fileInfo.size,
            relativePath = relativePath,
            modifiedAt = metadata.dateModified
                .takeIf { it > 0 }
                ?.let { seconds -> formatDateTime(seconds * 1000L) },
            playSeconds = playDurationMs.coerceAtLeast(0L) / 1000L,
            lastPlayedAt = lastPlayedAtMs
                .takeIf { it > 0L }
                ?.let(::formatDateTime),
            source = "android",
        )
    }

    private fun formatDateTime(epochMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
            .format(Date(epochMillis))
    }

    private fun Map<String, Long>.mergeRenumberedDurations(): Map<String, Long> {
        return entries.fold(mutableMapOf()) { result, (mediaId, duration) ->
            val canonicalId = canonicalHistoryMediaId(mediaId)
            result[canonicalId] = (result[canonicalId] ?: 0L) + duration.coerceAtLeast(0L)
            result
        }
    }

    private fun Map<String, Long>.mergeRenumberedLastTimes(): Map<String, Long> {
        return entries.fold(mutableMapOf()) { result, (mediaId, lastTime) ->
            val canonicalId = canonicalHistoryMediaId(mediaId)
            result[canonicalId] = maxOf(result[canonicalId] ?: 0L, lastTime)
            result
        }
    }

    private companion object {
        const val LOG_TAG = "ARMusicAgent"
        /**
         * MediaStore can assign a new id after a file move or rescan. Keep confirmed aliases so
         * listening time remains attached to the same audio file when a desktop manifest is built.
         */
        val HISTORY_MEDIA_ID_ALIASES = mapOf(
            "1000000866" to "1000129139",
        )
    }

    fun canonicalHistoryMediaId(mediaId: String): String =
        HISTORY_MEDIA_ID_ALIASES[mediaId] ?: mediaId

    fun knownHistoryMediaIdAliases(): Map<String, String> = HISTORY_MEDIA_ID_ALIASES

    private data class HistoryStats(
        val durationByMediaId: Map<String, Long>,
        val lastTimeByMediaId: Map<String, Long>,
    )
}

internal fun ARMusicSyncTrack.syncExclusionReason(): String? = when {
    !relativePath.substringAfterLast('.', "").equals("mp3", ignoreCase = true) ->
        "仅 MP3 支持跨端同步"
    durationSeconds < 15L ->
        "时长少于 15 秒"
    else -> null
}

data class ARMusicLocalSyncTrack(
    val track: ARMusicSyncTrack,
    val song: LSong,
)

data class ARMusicLocalSyncLibrary(
    val eligible: List<ARMusicLocalSyncTrack>,
    val ignored: List<ARMusicIgnoredSyncTrack>,
)
