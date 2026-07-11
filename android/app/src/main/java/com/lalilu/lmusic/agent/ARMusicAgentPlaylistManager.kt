package com.lalilu.lmusic.agent

import android.app.Application
import android.os.Build
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.sync.ARMusicAndroidManifestBuilder
import com.lalilu.lmusic.sync.ARMusicHistoryIdentityStore
import com.lalilu.lplaylist.entity.LPlaylist
import com.lalilu.lplaylist.repository.PlaylistRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB bridge for the app's real lplaylist data. The repository remains the only owner of visible
 * playlists; the small private mirror stores only sync identities/tombstones that lplaylist cannot
 * represent while a referenced song is temporarily absent from this phone.
 */
class ARMusicAgentPlaylistManager(
    application: Application,
    private val files: ARMusicAgentFiles,
    private val playlistRepository: PlaylistRepository,
    private val manifestBuilder: ARMusicAndroidManifestBuilder,
    private val historyIdentityStore: ARMusicHistoryIdentityStore,
) {
    private val preferences = application.getSharedPreferences(
        "armusic_playlist_sync_v1",
        Application.MODE_PRIVATE,
    )
    private val operationLock = Any()

    suspend fun exportPlaylists(outputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val initial = playlistRepository.getPlaylists()
        val index = buildIdentityIndex(initial.flatMap { it.mediaIds }.toSet())
        return synchronized(operationLock) {
            val playlists = playlistRepository.getPlaylists()
            val payload = buildCurrentPayload(playlists, index, detectDeletions = true)
            persistMirror(payload)
            files.writeTextFile(outputPath, encodePayload(payload).toString(2))
            AgentCommandResult(
                ok = true,
                command = ARMusicAgentManager.COMMAND_EXPORT_PLAYLISTS,
                message = "Exported ${payload.playlists.size} playlists with ${trackCount(payload)} entries to $outputPath",
                outputPath = outputPath,
                playlistCount = payload.playlists.size,
                playlistItems = trackCount(payload),
                playlistSnapshotId = payload.snapshotId,
            )
        }
    }

    suspend fun importPlaylists(inputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val text = files.readTextFile(inputPath)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "歌单同步文件超过 16 MB，手机原歌单没有改动"
        }
        val incoming = parsePayload(text)
        val referencedIds = (
            playlistRepository.getPlaylists().flatMap { it.mediaIds }
                + incoming.playlists.flatMap { it.trackIds }
            ).toSet()
        val index = buildIdentityIndex(referencedIds)
        return synchronized(operationLock) {
            // The library was exported immediately before playlists in the USB flow, so this is a
            // memory/index lookup. Only a genuinely missing identity triggers one manifest pass.
            var desired = incoming
            repeat(2) {
                var mergedPayload: PlaylistPayload? = null
                playlistRepository.mutatePlaylists { current ->
                    val phone = buildCurrentPayload(current, index, detectDeletions = true)
                    val merged = mergePayloads(phone, desired)
                    mergedPayload = merged
                    merged.playlists.map { playlist ->
                        LPlaylist(
                            id = playlist.id,
                            title = playlist.title,
                            subTitle = playlist.subTitle,
                            coverUri = playlist.coverUri,
                            mediaIds = playlist.trackIds.mapNotNull { index.mediaIdFor(it) }.distinct(),
                            createTime = playlist.createTime,
                            modifyTime = playlist.modifyTime,
                        )
                    }
                }
                desired = checkNotNull(mergedPayload)
                persistMirror(desired)
            }

            val persisted = buildCurrentPayload(
                playlistRepository.getPlaylists(),
                index,
                detectDeletions = true,
            ).let { mergePayloads(it, desired) }
            persistMirror(persisted)
            val reread = parsePayload(encodePayload(persisted).toString())
            check(reread.snapshotId == persisted.snapshotId && reread == persisted) {
                "歌单写入后快照复核失败；同步文件和手机原数据均未被静默删除"
            }
            AgentCommandResult(
                ok = true,
                command = ARMusicAgentManager.COMMAND_IMPORT_PLAYLISTS,
                message = "Merged ${persisted.playlists.size} playlists with ${trackCount(persisted)} entries",
                outputPath = inputPath,
                playlistCount = persisted.playlists.size,
                playlistItems = trackCount(persisted),
                playlistSnapshotId = persisted.snapshotId,
            )
        }
    }

    private suspend fun buildIdentityIndex(requiredMediaIds: Set<String>): IdentityIndex {
        val songs = LMedia.get<LSong>(blockFilter = false)
        val songById = songs.associateBy { it.id }
        val stableByMediaId = songs.mapNotNull { song ->
            historyIdentityStore.resolve(song.id, song.metadata.title.ifBlank { song.name })
                ?.let { song.id to it }
        }.toMap().toMutableMap()
        var legacyToMediaId = emptyMap<String, String>()
        val knownStableIds = stableByMediaId.values.toSet()
        val needsManifest = requiredMediaIds.any { required ->
            when {
                required.startsWith(MEDIA_ID_PREFIX) -> {
                    val mediaId = required.removePrefix(MEDIA_ID_PREFIX)
                    mediaId in songById && mediaId !in stableByMediaId
                }
                required in songById -> required !in stableByMediaId
                else -> required !in knownStableIds
            }
        }
        if (needsManifest) {
            val library = manifestBuilder.buildSyncLibrary()
            stableByMediaId.putAll(library.eligible.associate { it.song.id to it.track.syncId })
            legacyToMediaId = library.eligible.flatMap { local ->
                local.track.legacySyncIds.map { legacy -> legacy to local.song.id }
            }.toMap()
        }
        val mediaByStable = stableByMediaId.entries.associate { (mediaId, stableId) -> stableId to mediaId }
        return IdentityIndex(
            stableByMediaId = stableByMediaId,
            mediaByTrackId = mediaByStable + legacyToMediaId,
            validMediaIds = songById.keys,
        )
    }

    private fun buildCurrentPayload(
        source: List<LPlaylist>,
        index: IdentityIndex,
        detectDeletions: Boolean,
    ): PlaylistPayload {
        val previous = readMirror()
        val previousById = previous?.playlists.orEmpty().associateBy { it.id }
        val knownStableIds = index.stableByMediaId.values.toSet()
        val removedTracks = previous?.removedTracks.orEmpty().toMutableList()
        val playlists = source.map { playlist ->
            val currentTrackIds = playlist.mediaIds.map { mediaId ->
                index.stableByMediaId[mediaId] ?: "$MEDIA_ID_PREFIX$mediaId"
            }
            val unresolvedPrevious = previousById[playlist.id]?.trackIds.orEmpty().filter { trackId ->
                trackId !in knownStableIds && index.mediaIdFor(trackId) == null
            }
            if (detectDeletions) {
                val removedAt = System.currentTimeMillis().coerceAtLeast(1L)
                previousById[playlist.id]?.trackIds.orEmpty()
                    .filter { trackId -> index.mediaIdFor(trackId) != null && trackId !in currentTrackIds }
                    .forEach { trackId ->
                        val existing = removedTracks.indexOfFirst {
                            it.playlistId == playlist.id && it.trackId == trackId
                        }
                        if (existing >= 0) removedTracks[existing] = removedTracks[existing].copy(
                            removedAt = maxOf(removedTracks[existing].removedAt, removedAt),
                        ) else removedTracks += PlaylistTrackTombstone(playlist.id, trackId, removedAt)
                    }
            }
            PlaylistRecord(
                id = playlist.id,
                title = playlist.title,
                subTitle = playlist.subTitle,
                coverUri = playlist.coverUri,
                createTime = playlist.createTime.coerceAtLeast(1L),
                modifyTime = playlist.modifyTime.coerceAtLeast(playlist.createTime.coerceAtLeast(1L)),
                trackIds = orderedUnion(currentTrackIds, unresolvedPrevious),
            )
        }
        val currentIds = playlists.mapTo(mutableSetOf()) { it.id }
        val tombstones = previous?.deletedPlaylists.orEmpty().toMutableList()
        if (detectDeletions && previous != null) {
            val deletedAt = System.currentTimeMillis().coerceAtLeast(1L)
            previous.playlists.filter { it.id !in currentIds }.forEach { removed ->
                val indexOfExisting = tombstones.indexOfFirst { it.id == removed.id }
                if (indexOfExisting >= 0) {
                    tombstones[indexOfExisting] = tombstones[indexOfExisting].copy(
                        deletedAt = maxOf(tombstones[indexOfExisting].deletedAt, deletedAt),
                    )
                } else {
                    tombstones += PlaylistTombstone(removed.id, deletedAt)
                }
            }
        }
        val effectiveTombstones = normalizeTombstones(tombstones).filterNot { tombstone ->
            playlists.any { it.id == tombstone.id && it.modifyTime > tombstone.deletedAt }
        }
        val effectiveTrackTombstones = normalizeTrackTombstones(removedTracks).filterNot { tombstone ->
            playlists.any { playlist ->
                playlist.id == tombstone.playlistId
                    && tombstone.trackId in playlist.trackIds
                    && playlist.modifyTime > tombstone.removedAt
            }
        }
        return payload(playlists, effectiveTombstones, effectiveTrackTombstones)
    }

    private fun mergePayloads(phone: PlaylistPayload, incoming: PlaylistPayload): PlaylistPayload {
        val merged = phone.playlists.toMutableList()
        incoming.playlists.forEach { candidate ->
            val index = merged.indexOfFirst { it.id == candidate.id }
            if (index < 0) {
                merged += candidate
            } else {
                val current = merged[index]
                merged[index] = when {
                    candidate.modifyTime > current.modifyTime -> candidate.copy(
                        trackIds = orderedUnion(candidate.trackIds, current.trackIds),
                    )
                    candidate.modifyTime < current.modifyTime -> current.copy(
                        trackIds = orderedUnion(current.trackIds, candidate.trackIds),
                    )
                    else -> current.copy(trackIds = orderedUnion(current.trackIds, candidate.trackIds))
                }.let { it.copy(createTime = minOf(it.createTime, current.createTime)) }
            }
        }
        val tombstones = normalizeTombstones(phone.deletedPlaylists + incoming.deletedPlaylists)
            .toMutableList()
        merged.removeAll { playlist ->
            tombstones.any { it.id == playlist.id && it.deletedAt >= playlist.modifyTime }
        }
        tombstones.removeAll { tombstone ->
            merged.any { it.id == tombstone.id && it.modifyTime > tombstone.deletedAt }
        }
        val trackTombstones = normalizeTrackTombstones(phone.removedTracks + incoming.removedTracks)
            .toMutableList()
        merged.indices.forEach { playlistIndex ->
            val playlist = merged[playlistIndex]
            merged[playlistIndex] = playlist.copy(trackIds = playlist.trackIds.filter { trackId ->
                val presentAt = trackPresenceTime(playlist.id, trackId, phone.playlists, incoming.playlists)
                trackTombstones.none {
                    it.playlistId == playlist.id && it.trackId == trackId && it.removedAt >= presentAt
                }
            })
        }
        trackTombstones.removeAll { tombstone ->
            trackPresenceTime(
                tombstone.playlistId,
                tombstone.trackId,
                phone.playlists,
                incoming.playlists,
            ) > tombstone.removedAt
        }
        return payload(merged, tombstones, trackTombstones)
    }

    private fun trackPresenceTime(
        playlistId: String,
        trackId: String,
        left: List<PlaylistRecord>,
        right: List<PlaylistRecord>,
    ): Long = (left + right)
        .filter { it.id == playlistId && trackId in it.trackIds }
        .maxOfOrNull { it.modifyTime }
        ?: 0L

    private fun parsePayload(text: String): PlaylistPayload {
        val root = JSONObject(text.trim { it <= ' ' || it == '\uFEFF' })
        require(root.optString("schema") == SCHEMA) { "不支持的歌单同步格式" }
        val playlistsArray = root.optJSONArray("playlists") ?: JSONArray()
        require(playlistsArray.length() <= MAX_PLAYLISTS) { "歌单超过 $MAX_PLAYLISTS 个" }
        val playlists = buildList {
            repeat(playlistsArray.length()) { index ->
                val item = playlistsArray.getJSONObject(index)
                val tracks = item.optJSONArray("trackIds") ?: JSONArray()
                add(PlaylistRecord(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    subTitle = item.optString("subTitle"),
                    coverUri = item.optString("coverUri"),
                    createTime = item.optLong("createTime"),
                    modifyTime = item.optLong("modifyTime"),
                    trackIds = buildList { repeat(tracks.length()) { add(tracks.getString(it)) } },
                ))
            }
        }
        val tombstonesArray = root.optJSONArray("deletedPlaylists") ?: JSONArray()
        val tombstones = buildList {
            repeat(tombstonesArray.length()) { index ->
                val item = tombstonesArray.getJSONObject(index)
                add(PlaylistTombstone(item.optString("id"), item.optLong("deletedAt")))
            }
        }
        val removedTracksArray = root.optJSONArray("removedTracks") ?: JSONArray()
        val removedTracks = buildList {
            repeat(removedTracksArray.length()) { index ->
                val item = removedTracksArray.getJSONObject(index)
                add(PlaylistTrackTombstone(
                    playlistId = item.optString("playlistId"),
                    trackId = item.optString("trackId"),
                    removedAt = item.optLong("removedAt"),
                ))
            }
        }
        val parsed = payload(playlists, tombstones, removedTracks)
        require(root.optString("snapshotId") == parsed.snapshotId) {
            "歌单快照校验失败，手机原歌单没有改动"
        }
        return parsed.copy(
            deviceId = root.optString("deviceId").ifBlank { "unknown" },
            generatedAt = root.optString("generatedAt").ifBlank { isoNow() },
        )
    }

    private fun payload(
        source: List<PlaylistRecord>,
        tombstones: List<PlaylistTombstone>,
        removedTracks: List<PlaylistTrackTombstone>,
    ): PlaylistPayload {
        require(source.size <= MAX_PLAYLISTS) { "歌单超过 $MAX_PLAYLISTS 个" }
        val ids = mutableSetOf<String>()
        var totalTracks = 0
        val playlists = source.mapIndexed { index, playlist ->
            val id = playlist.id.trim()
            val title = playlist.title.trim()
            require(id.isNotBlank() && id.toByteArray().size <= MAX_ID_BYTES && ids.add(id)) {
                "第 ${index + 1} 个歌单 ID 为空、重复或过长"
            }
            require(title.isNotBlank() && title.toByteArray().size <= MAX_TEXT_BYTES) {
                "歌单名称为空或过长"
            }
            require(playlist.subTitle.toByteArray().size <= MAX_TEXT_BYTES && playlist.coverUri.toByteArray().size <= MAX_TEXT_BYTES) {
                "歌单说明或封面地址过长"
            }
            val trackIds = playlist.trackIds.map(String::trim).filter(String::isNotBlank).distinct()
            require(trackIds.all { it.toByteArray().size <= MAX_ID_BYTES }) { "歌单包含过长的歌曲标识" }
            totalTracks += trackIds.size
            require(totalTracks <= MAX_TRACKS) { "歌单歌曲条目超过 $MAX_TRACKS 条" }
            val createTime = playlist.createTime.takeIf { it > 0L }
                ?: playlist.modifyTime.coerceAtLeast(1L)
            val modifyTime = playlist.modifyTime.takeIf { it > 0L } ?: createTime
            playlist.copy(
                id = id,
                title = title,
                subTitle = playlist.subTitle.trim(),
                coverUri = playlist.coverUri.trim(),
                createTime = createTime,
                modifyTime = modifyTime,
                trackIds = trackIds,
            )
        }
        val normalizedTombstones = normalizeTombstones(tombstones)
        val normalizedTrackTombstones = normalizeTrackTombstones(removedTracks)
        return PlaylistPayload(
            schema = SCHEMA,
            deviceId = androidDeviceId(),
            generatedAt = isoNow(),
            snapshotId = armusicPlaylistSnapshotId(
                playlists,
                normalizedTombstones,
                normalizedTrackTombstones,
            ),
            playlists = playlists,
            deletedPlaylists = normalizedTombstones,
            removedTracks = normalizedTrackTombstones,
        )
    }

    private fun normalizeTombstones(source: List<PlaylistTombstone>): List<PlaylistTombstone> {
        val result = mutableListOf<PlaylistTombstone>()
        source.forEach { tombstone ->
            val id = tombstone.id.trim()
            require(id.isNotBlank() && id.toByteArray().size <= MAX_ID_BYTES && tombstone.deletedAt > 0L) {
                "歌单删除记录无效"
            }
            val index = result.indexOfFirst { it.id == id }
            if (index >= 0) result[index] = result[index].copy(
                deletedAt = maxOf(result[index].deletedAt, tombstone.deletedAt),
            ) else result += tombstone.copy(id = id)
        }
        return result
    }

    private fun normalizeTrackTombstones(
        source: List<PlaylistTrackTombstone>,
    ): List<PlaylistTrackTombstone> {
        val result = mutableListOf<PlaylistTrackTombstone>()
        source.forEach { tombstone ->
            val playlistId = tombstone.playlistId.trim()
            val trackId = tombstone.trackId.trim()
            require(
                playlistId.isNotBlank() && trackId.isNotBlank()
                    && playlistId.toByteArray().size <= MAX_ID_BYTES
                    && trackId.toByteArray().size <= MAX_ID_BYTES
                    && tombstone.removedAt > 0L
            ) { "歌单歌曲删除记录无效" }
            val index = result.indexOfFirst {
                it.playlistId == playlistId && it.trackId == trackId
            }
            if (index >= 0) result[index] = result[index].copy(
                removedAt = maxOf(result[index].removedAt, tombstone.removedAt),
            ) else result += tombstone.copy(playlistId = playlistId, trackId = trackId)
        }
        return result
    }

    private fun encodePayload(payload: PlaylistPayload): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("deviceId", payload.deviceId)
        .put("generatedAt", payload.generatedAt)
        .put("snapshotId", payload.snapshotId)
        .put("playlists", JSONArray().also { array ->
            payload.playlists.forEach { playlist ->
                array.put(JSONObject()
                    .put("id", playlist.id)
                    .put("title", playlist.title)
                    .put("subTitle", playlist.subTitle)
                    .put("coverUri", playlist.coverUri)
                    .put("createTime", playlist.createTime)
                    .put("modifyTime", playlist.modifyTime)
                    .put("trackIds", JSONArray(playlist.trackIds)))
            }
        })
        .put("deletedPlaylists", JSONArray().also { array ->
            payload.deletedPlaylists.forEach { tombstone ->
                array.put(JSONObject().put("id", tombstone.id).put("deletedAt", tombstone.deletedAt))
            }
        })
        .put("removedTracks", JSONArray().also { array ->
            payload.removedTracks.forEach { tombstone ->
                array.put(JSONObject()
                    .put("playlistId", tombstone.playlistId)
                    .put("trackId", tombstone.trackId)
                    .put("removedAt", tombstone.removedAt))
            }
        })

    private fun persistMirror(payload: PlaylistPayload) {
        check(preferences.edit().putString(MIRROR_KEY, encodePayload(payload).toString()).commit()) {
            "无法持久保存歌单同步镜像"
        }
    }

    private fun readMirror(): PlaylistPayload? = preferences.getString(MIRROR_KEY, null)
        ?.let { runCatching { parsePayload(it) }.getOrNull() }

    private fun trackCount(payload: PlaylistPayload): Int = payload.playlists.sumOf { it.trackIds.size }
    private fun orderedUnion(base: List<String>, additional: List<String>): List<String> =
        (base + additional).distinct()

    private fun androidDeviceId(): String = "android-${Build.MANUFACTURER}-${Build.MODEL}"
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "-")

    private fun isoNow(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        Locale.ROOT,
    ).format(Date())

    private data class IdentityIndex(
        val stableByMediaId: Map<String, String>,
        val mediaByTrackId: Map<String, String>,
        val validMediaIds: Set<String>,
    ) {
        fun mediaIdFor(trackId: String): String? = when {
            trackId.startsWith(MEDIA_ID_PREFIX) -> trackId.removePrefix(MEDIA_ID_PREFIX)
                .takeIf { it in validMediaIds }
            else -> mediaByTrackId[trackId]
        }
    }

    private data class PlaylistPayload(
        val schema: String,
        val deviceId: String,
        val generatedAt: String,
        val snapshotId: String,
        val playlists: List<PlaylistRecord>,
        val deletedPlaylists: List<PlaylistTombstone>,
        val removedTracks: List<PlaylistTrackTombstone>,
    )

    private companion object {
        const val SCHEMA = "armusic-playlists-v1"
        const val MIRROR_KEY = "verified_payload"
        const val MEDIA_ID_PREFIX = "android-media-id:"
        const val MAX_PLAYLISTS = 500
        const val MAX_TRACKS = 100_000
        const val MAX_ID_BYTES = 4 * 1024
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
    }
}
