package com.lalilu.lmusic.agent

import android.app.Application
import android.util.Log
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.repository.HistoryDao
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmusic.tag.SongGroupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume

class ARMusicAgentManager(
    private val application: Application,
    private val historyDao: HistoryDao,
    private val songWorkStore: SongWorkStore,
    private val songGroupStore: SongGroupStore,
) {
    suspend fun execute(
        command: String,
        path: String?,
        resultPath: String?,
    ): AgentCommandResult = withContext(Dispatchers.IO) {
        val normalizedCommand = command.trim().lowercase(Locale.ROOT).ifBlank { COMMAND_HELP }
        val finalResultPath = resultPath.orDefaultResultPath()

        val result = runCatching {
            when (normalizedCommand) {
                COMMAND_HELP -> helpResult()
                COMMAND_EXPORT_LIBRARY -> exportLibrary(path.orDefaultLibraryPath())
                COMMAND_IMPORT_BUNDLE -> importBundle(path.requirePath())
                COMMAND_IMPORT_HISTORY -> importHistory(path.requirePath())
                COMMAND_IMPORT_WORKS -> importWorks(path.requirePath())
                COMMAND_IMPORT_GROUPS -> importGroups(path.requirePath())
                else -> AgentCommandResult(
                    ok = false,
                    command = normalizedCommand,
                    message = "Unknown command: $normalizedCommand",
                )
            }
        }.getOrElse { throwable ->
            AgentCommandResult(
                ok = false,
                command = normalizedCommand,
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
        }.copy(resultPath = finalResultPath)

        writeResult(finalResultPath, result)
        Log.i(LOG_TAG, result.message)
        result
    }

    private suspend fun exportLibrary(outputPath: String): AgentCommandResult {
        awaitLibraryReady()
        val songs = LMedia.get<LSong>(blockFilter = false)
            .sortedWith(compareBy<LSong> { it.metadata.title.lowercase(Locale.ROOT) }
                .thenBy { it.metadata.artist.lowercase(Locale.ROOT) })

        val root = JSONObject()
            .put("schema", SCHEMA_LIBRARY)
            .put("packageName", application.packageName)
            .put("exportedAt", System.currentTimeMillis())
            .put("defaultAgentDir", agentDir().absolutePath)
            .put("songs", JSONArray().also { array ->
                songs.forEach { song ->
                    array.put(
                        JSONObject()
                            .put("mediaId", song.id)
                            .put("title", song.metadata.title)
                            .put("artist", song.metadata.artist)
                            .put("album", song.metadata.album)
                            .put("work", songWorkStore.getWork(song))
                            .put("sameSongGroup", songGroupStore.getGroup(song))
                            .put("durationMs", song.metadata.duration)
                            .put("filePath", song.fileInfo.pathStr.orEmpty())
                            .put("directoryPath", song.fileInfo.directoryPath)
                            .put("mimeType", song.fileInfo.mimeType)
                    )
                }
            })

        writeTextFile(outputPath, root.toString(2))

        return AgentCommandResult(
            ok = true,
            command = COMMAND_EXPORT_LIBRARY,
            message = "Exported ${songs.size} songs to $outputPath",
            outputPath = outputPath,
            exportedSongs = songs.size,
        )
    }

    private suspend fun importBundle(inputPath: String): AgentCommandResult {
        awaitLibraryReady()
        val input = readInput(inputPath)
        val metadataRows = input.items + input.histories
        val historyRows = input.histories.ifEmpty { input.items }

        val works = importWorks(metadataRows)
        val groups = importGroups(metadataRows)
        val histories = importHistories(historyRows)

        return AgentCommandResult(
            ok = true,
            command = COMMAND_IMPORT_BUNDLE,
            message = "Imported bundle from $inputPath: histories ${histories.imported}, works ${works.imported}, groups ${groups.imported}",
            importedHistories = histories.imported,
            importedWorks = works.imported,
            importedGroups = groups.imported,
            skipped = histories.skipped + works.skipped + groups.skipped,
            duplicates = histories.duplicates,
        )
    }

    private suspend fun importHistory(inputPath: String): AgentCommandResult {
        awaitLibraryReady()
        val input = readInput(inputPath)
        val rows = input.histories.ifEmpty { input.items }
        val histories = importHistories(rows)

        return AgentCommandResult(
            ok = true,
            command = COMMAND_IMPORT_HISTORY,
            message = "Imported ${histories.imported} histories from $inputPath",
            importedHistories = histories.imported,
            skipped = histories.skipped,
            duplicates = histories.duplicates,
        )
    }

    private suspend fun importWorks(inputPath: String): AgentCommandResult {
        awaitLibraryReady()
        val rows = readInput(inputPath).allRows()
        val works = importWorks(rows)

        return AgentCommandResult(
            ok = true,
            command = COMMAND_IMPORT_WORKS,
            message = "Imported ${works.imported} works from $inputPath",
            importedWorks = works.imported,
            skipped = works.skipped,
        )
    }

    private suspend fun importGroups(inputPath: String): AgentCommandResult {
        awaitLibraryReady()
        val rows = readInput(inputPath).allRows()
        val groups = importGroups(rows)

        return AgentCommandResult(
            ok = true,
            command = COMMAND_IMPORT_GROUPS,
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

            songWorkStore.setWork(song, work)
            imported += 1
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

    private fun importHistories(rows: List<JSONObject>): ImportCount {
        val index = SongIndex(LMedia.get(blockFilter = false))
        var imported = 0
        var skipped = 0
        var duplicates = 0

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
            val startTime = row.longAny("startTime", "start_time", "playedAt", "played_at")
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

            if (historyDao.countSimilar(history.contentId, history.startTime, history.duration) > 0) {
                duplicates += 1
                return@forEach
            }

            historyDao.save(history)
            imported += 1
        }

        return ImportCount(imported = imported, skipped = skipped, duplicates = duplicates)
    }

    private fun readInput(path: String): AgentInput {
        val text = readTextFile(path).trim { it <= ' ' || it == '\uFEFF' }
        if (text.isBlank()) return AgentInput()

        if (!text.startsWith("{") && !text.startsWith("[")) {
            return AgentInput(items = parseTsv(text))
        }

        if (text.startsWith("[")) {
            return AgentInput(items = JSONArray(text).toObjects())
        }

        val root = JSONObject(text)
        val items = root.firstArray("songs", "items", "tracks").toObjects()
        val histories = root.firstArray("histories", "history").toObjects()
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

    private suspend fun awaitLibraryReady() {
        try {
            withTimeout(LIBRARY_READY_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    LMedia.whenReady {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        } catch (throwable: TimeoutCancellationException) {
            error("Music library is not ready. Open ARMusic once and grant audio permission first.")
        }
    }

    private fun helpResult(): AgentCommandResult {
        val path = defaultLibraryPath()
        return AgentCommandResult(
            ok = true,
            command = COMMAND_HELP,
            message = "ARMusic agent commands: export_library, import_bundle, import_history, import_works, import_groups. Default agent dir: ${agentDir().absolutePath}",
            outputPath = path,
        )
    }

    private fun stableHistoryStartTime(song: LSong, row: JSONObject): Long {
        val hash = "${song.id}:${row}".hashCode().toLong() and 0x7FFFFFFFL
        return AGENT_HISTORY_START_TIME - hash
    }

    private fun readTextFile(path: String): String {
        return File(path).readText(Charsets.UTF_8)
    }

    private fun writeTextFile(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun writeResult(path: String, result: AgentCommandResult) {
        runCatching {
            writeTextFile(path, result.toJson().toString(2))
        }.onFailure {
            Log.e(LOG_TAG, "Failed to write result file: $path", it)
        }
    }

    private fun String?.requirePath(): String {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) error("Missing path")
        return value
    }

    private fun String?.orDefaultLibraryPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: defaultLibraryPath()
    }

    private fun String?.orDefaultResultPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() }
            ?: File(agentDir(), DEFAULT_RESULT_FILE).absolutePath
    }

    private fun defaultLibraryPath(): String {
        return File(agentDir(), DEFAULT_LIBRARY_FILE).absolutePath
    }

    private fun agentDir(): File {
        return (application.getExternalFilesDir("agent") ?: File(application.filesDir, "agent"))
            .apply { mkdirs() }
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

    companion object {
        const val COMMAND_HELP = "help"
        const val COMMAND_EXPORT_LIBRARY = "export_library"
        const val COMMAND_IMPORT_BUNDLE = "import_bundle"
        const val COMMAND_IMPORT_HISTORY = "import_history"
        const val COMMAND_IMPORT_WORKS = "import_works"
        const val COMMAND_IMPORT_GROUPS = "import_groups"

        private const val LOG_TAG = "ARMusicAgent"
        private const val SCHEMA_LIBRARY = "armusic-agent-library-v1"
        private const val DEFAULT_LIBRARY_FILE = "armusic-library.json"
        private const val DEFAULT_RESULT_FILE = "armusic-agent-result.json"
        private const val LIBRARY_READY_TIMEOUT_MS = 60_000L
        private const val AGENT_HISTORY_START_TIME = 1782144000000L
    }
}

data class AgentCommandResult(
    val ok: Boolean,
    val command: String,
    val message: String,
    val outputPath: String? = null,
    val resultPath: String? = null,
    val exportedSongs: Int = 0,
    val importedHistories: Int = 0,
    val importedWorks: Int = 0,
    val importedGroups: Int = 0,
    val skipped: Int = 0,
    val duplicates: Int = 0,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("ok", ok)
            .put("command", command)
            .put("message", message)
            .put("outputPath", outputPath.orEmpty())
            .put("resultPath", resultPath.orEmpty())
            .put("exportedSongs", exportedSongs)
            .put("importedHistories", importedHistories)
            .put("importedWorks", importedWorks)
            .put("importedGroups", importedGroups)
            .put("skipped", skipped)
            .put("duplicates", duplicates)
    }
}

private class SongIndex(
    private val songs: List<LSong>,
) {
    private val byId = songs.associateBy { it.id }
    private val byPath = songs
        .mapNotNull { song -> song.fileInfo.pathStr?.takeIf(String::isNotBlank)?.let { it to song } }
        .toMap()

    fun find(row: JSONObject): LSong? {
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
