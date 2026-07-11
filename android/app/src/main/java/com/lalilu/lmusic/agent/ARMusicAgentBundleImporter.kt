package com.lalilu.lmusic.agent

import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.HistoryMutationCoordinator
import com.lalilu.lhistory.repository.HistoryDao
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmusic.tag.SongGroupStore
import com.lalilu.lmusic.sync.ARMusicAndroidManifestBuilder
import com.lalilu.lmusic.sync.ARMusicHistoryIdentityStore
import com.lalilu.lmusic.sync.ARMusicLocalSyncTrack
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

class ARMusicAgentBundleImporter(
    private val files: ARMusicAgentFiles,
    private val historyDao: HistoryDao,
    private val songWorkStore: SongWorkStore,
    private val songGroupStore: SongGroupStore,
    private val manifestBuilder: ARMusicAndroidManifestBuilder,
    private val mutationCoordinator: HistoryMutationCoordinator,
    private val historyIdentityStore: ARMusicHistoryIdentityStore,
) {
    suspend fun importBundle(inputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val input = readInput(inputPath)
        val metadataRows = input.items + input.histories
        val historyRows = input.histories.ifEmpty { input.items }

        val works = importWorks(metadataRows)
        val groups = importGroups(metadataRows)
        val histories = importHistories(historyRows)

        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_IMPORT_BUNDLE,
            message = "Imported bundle from $inputPath: histories ${histories.imported}, works ${works.imported}, groups ${groups.imported}",
            importedHistories = histories.imported,
            importedWorks = works.imported,
            importedGroups = groups.imported,
            skipped = histories.skipped + works.skipped + groups.skipped,
            duplicates = histories.duplicates,
        )
    }

    suspend fun importHistory(inputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val input = readInput(inputPath)
        val rows = input.histories.ifEmpty { input.items }
        val histories = importHistories(rows)

        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_IMPORT_HISTORY,
            message = "Imported ${histories.imported} histories from $inputPath",
            importedHistories = histories.imported,
            skipped = histories.skipped,
            duplicates = histories.duplicates,
        )
    }

    suspend fun importWorks(inputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val rows = readInput(inputPath).allRows()
        val works = importWorks(rows)

        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_IMPORT_WORKS,
            message = "Imported ${works.imported} works from $inputPath",
            importedWorks = works.imported,
            skipped = works.skipped,
        )
    }

    suspend fun importGroups(inputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val rows = readInput(inputPath).allRows()
        val groups = importGroups(rows)

        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_IMPORT_GROUPS,
            message = "Imported ${groups.imported} song groups from $inputPath",
            importedGroups = groups.imported,
            skipped = groups.skipped,
        )
    }

    private fun importWorks(rows: List<JSONObject>): ImportCount {
        val index = SongIndex(LMedia.get(blockFilter = false))
        var imported = 0
        var skipped = 0

        rows.forEach { row ->
            val work = row.stringAny("work", "current_work", "suggested_work").trim()
            if (work.isBlank()) return@forEach

            val song = index.find(row)
            if (song == null) {
                skipped += 1
                return@forEach
            }

            if (songWorkStore.setWork(song, work, writeFile = true)) {
                imported += 1
            } else {
                skipped += 1
            }
        }

        return ImportCount(imported = imported, skipped = skipped)
    }

    private fun importGroups(rows: List<JSONObject>): ImportCount {
        val index = SongIndex(LMedia.get(blockFilter = false))
        var imported = 0
        var skipped = 0

        rows.forEach { row ->
            val group = row.stringAny("sameSongGroup", "same_song_group", "songGroup", "group").trim()
            if (group.isBlank()) return@forEach

            val song = index.find(row)
            if (song == null) {
                skipped += 1
                return@forEach
            }

            songGroupStore.setGroup(song, group)
            imported += 1
        }

        return ImportCount(imported = imported, skipped = skipped)
    }

    private suspend fun importHistories(rows: List<JSONObject>): ImportCount {
        // Normalize the known MediaStore renumbering before the expensive identity scan. This
        // prevents the old/new copies of one event from inflating the history totals used while
        // the local sync library is built.
        val aliasStatsBeforeImport = mutationCoordinator.withMutation {
            historyDao.mergeKnownContentIdAliases(manifestBuilder.knownHistoryMediaIdAliases())
        }
        val songs = LMedia.get<LSong>(blockFilter = false)
        val cachedBySyncId = songs.mapNotNull { song ->
            historyIdentityStore.resolve(song.id, song.metadata.title.ifBlank { song.name })
                ?.let { syncId -> syncId to song }
        }.toMap()
        val requestedSyncIds = rows.map { row ->
            row.stringAny("syncId", "sync_id").trim()
        }
        val syncTracks = if (needsHistoryIdentityScan(requestedSyncIds, cachedBySyncId.keys)) {
            manifestBuilder.buildLocalTracks()
        } else {
            emptyList()
        }
        val index = SongIndex(
            songs = songs,
            syncTracks = syncTracks,
            cachedBySyncId = cachedBySyncId,
        )
        var skipped = 0
        val pending = mutableListOf<LHistory>()

        rows.forEach { row ->
            val song = index.find(row)
            if (song == null) {
                skipped += 1
                return@forEach
            }

            val playCount = row.intAny("playCount", "play_count", "count", "plays")
                ?.coerceAtLeast(1)
            val repeatCount = row.intAny("repeatCount", "repeat_count")
                ?: playCount?.minus(1)?.coerceAtLeast(0)
                ?: 0
            val singleDuration = row.longAny("durationMs", "duration_ms", "trackDurationMs", "track_duration_ms")
                ?.takeIf { it > 0L }
                ?: song.metadata.duration.takeIf { it > 0L }
                ?: 0L
            val playedDuration = row.longAny(
                "listenDurationMs",
                "listen_duration_ms",
                "playedDurationMs",
                "played_duration_ms",
                "playDurationMs",
                "play_duration_ms",
                "totalDurationMs",
                "total_duration_ms",
            )?.takeIf { it > 0L } ?: singleDuration * (playCount ?: 1)

            if (playedDuration <= 0L) {
                skipped += 1
                return@forEach
            }

            val statIdentity = songGroupStore.resolve(song.id, song.name)
            val hasParent = statIdentity.id != song.id
            val startTime = row.longAny("startedAtMs", "started_at_ms", "startTime", "start_time", "playedAt", "played_at")
                ?.takeIf { it > 0L }
                ?: stableHistoryStartTime(song, row)
            val history = LHistory(
                contentId = song.id,
                contentTitle = song.name,
                parentId = if (hasParent) statIdentity.id else "",
                parentTitle = if (hasParent) statIdentity.title else "",
                duration = playedDuration,
                repeatCount = repeatCount,
                startTime = startTime,
            )

            pending += history
        }

        val (stats, aliasStatsDuringImport) = mutationCoordinator.withMutation {
            val canonicalPending = pending.map { history ->
                history.copy(
                    contentId = manifestBuilder.canonicalHistoryMediaId(history.contentId),
                )
            }
            val merged = historyDao.mergeHistories(canonicalPending)
            val aliases = historyDao.mergeKnownContentIdAliases(
                manifestBuilder.knownHistoryMediaIdAliases()
            )
            merged to aliases
        }
        return ImportCount(
            imported = stats.inserted,
            skipped = skipped + stats.skipped,
            duplicates = stats.merged + stats.unchanged +
                aliasStatsBeforeImport.removedDuplicates +
                aliasStatsDuringImport.removedDuplicates,
        )
    }

    private fun readInput(path: String): AgentInput {
        val text = files.readTextFile(path).trim { it <= ' ' || it == '\uFEFF' }
        if (text.isBlank()) return AgentInput()

        if (!text.startsWith("{") && !text.startsWith("[")) {
            return AgentInput(items = parseTsv(text))
        }

        if (text.startsWith("[")) {
            return AgentInput(items = JSONArray(text).toObjects())
        }

        val root = JSONObject(text)
        val items = root.firstArray("songs", "items", "tracks").toObjects()
        val histories = root.firstArray("histories", "history", "sessions").toObjects()
        if (items.isEmpty() && histories.isEmpty()) {
            return AgentInput(items = listOf(root))
        }

        return AgentInput(items = items, histories = histories)
    }

    private fun parseTsv(text: String): List<JSONObject> {
        val lines = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return emptyList()

        val header = lines.first()
            .removePrefix("\uFEFF")
            .split('\t')
            .map { it.trim() }

        return lines.drop(1).map { line ->
            val columns = line.split('\t')
            JSONObject().also { row ->
                header.forEachIndexed { index, key ->
                    if (key.isNotBlank()) row.put(key, columns.getOrNull(index).orEmpty().trim())
                }
            }
        }
    }

    private fun stableHistoryStartTime(song: LSong, row: JSONObject): Long {
        val hash = "${song.id}:${row}".hashCode().toLong() and 0x7FFFFFFFL
        return AGENT_HISTORY_START_TIME - hash
    }

    private data class AgentInput(
        val items: List<JSONObject> = emptyList(),
        val histories: List<JSONObject> = emptyList(),
    ) {
        fun allRows(): List<JSONObject> = items + histories
    }

    private data class ImportCount(
        val imported: Int = 0,
        val skipped: Int = 0,
        val duplicates: Int = 0,
    )

    private companion object {
        const val AGENT_HISTORY_START_TIME = 1782144000000L
    }
}

private class SongIndex(
    private val songs: List<LSong>,
    syncTracks: List<ARMusicLocalSyncTrack> = emptyList(),
    cachedBySyncId: Map<String, LSong> = emptyMap(),
) {
    private val byId = songs.associateBy { it.id }
    private val byPath = songs
        .mapNotNull { song -> song.fileInfo.pathStr?.takeIf(String::isNotBlank)?.let { it to song } }
        .toMap()
    private val bySyncId = buildMap {
        putAll(cachedBySyncId)
        syncTracks.forEach { local ->
            put(local.track.syncId, local.song)
            local.track.legacySyncIds.forEach { id -> putIfAbsent(id, local.song) }
        }
    }

    fun find(row: JSONObject): LSong? {
        row.stringAny("syncId", "sync_id")
            .takeIf(String::isNotBlank)
            ?.let { bySyncId[it] }
            ?.let { return it }

        row.stringAny("mediaId", "media_id", "id", "contentId", "content_id")
            .takeIf(String::isNotBlank)
            ?.let { byId[it] }
            ?.let { return it }

        row.stringAny("filePath", "file_path", "path")
            .takeIf(String::isNotBlank)
            ?.let { byPath[it] }
            ?.let { return it }

        val title = row.stringAny("title", "songTitle", "song_title", "contentTitle", "content_title", "name")
            .normalizedForAgentMatch()
        if (title.isBlank()) return null

        val artist = row.stringAny("artist", "artists").normalizedForAgentMatch()
        val duration = row.longAny("durationMs", "duration_ms", "trackDurationMs", "track_duration_ms")

        val candidates = songs.filter { song ->
            val songTitle = song.metadata.title.normalizedForAgentMatch()
            songTitle == title ||
                    (title.length >= 3 && songTitle.contains(title)) ||
                    (songTitle.length >= 3 && title.contains(songTitle))
        }
        if (candidates.isEmpty()) return null

        return candidates
            .sortedWith(
                compareByDescending<LSong> { song ->
                    artist.isNotBlank() && song.metadata.artist.normalizedForAgentMatch().let {
                        it == artist || it.contains(artist) || artist.contains(it)
                    }
                }.thenBy { song ->
                    duration?.let { kotlin.math.abs(song.metadata.duration - it) } ?: Long.MAX_VALUE
                }
            )
            .firstOrNull()
    }
}

internal fun needsHistoryIdentityScan(
    requestedSyncIds: Collection<String>,
    cachedSyncIds: Set<String>,
): Boolean = requestedSyncIds.any { requested ->
    requested.isNotBlank() && requested !in cachedSyncIds
}

private fun JSONObject.firstArray(vararg keys: String): JSONArray {
    keys.forEach { key -> optJSONArray(key)?.let { return it } }
    return JSONArray()
}

private fun JSONArray.toObjects(): List<JSONObject> {
    return (0 until length()).mapNotNull { index -> optJSONObject(index) }
}

private fun JSONObject.stringAny(vararg keys: String): String {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

private fun JSONObject.longAny(vararg keys: String): Long? {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        val value = opt(key)
        when (value) {
            is Number -> return value.toLong()
            is String -> value.trim().toLongOrNull()?.let { return it }
        }
    }
    return null
}

private fun JSONObject.intAny(vararg keys: String): Int? {
    return longAny(*keys)?.toInt()
}

private fun String.normalizedForAgentMatch(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}"), "")
        .replace(Regex("[\\s_\\-./,;:：，。'\"“”‘’·・、|\\\\]+"), "")
        .trim()
}
