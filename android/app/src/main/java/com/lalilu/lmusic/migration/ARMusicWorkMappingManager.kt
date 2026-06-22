package com.lalilu.lmusic.migration

import android.app.Application
import android.net.Uri
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale

data class ARMusicWorkMappingResult(
    val imported: Int = 0,
    val exported: Int = 0,
    val skipped: Int = 0,
    val message: String,
)

class ARMusicWorkMappingManager(
    private val application: Application,
    private val songWorkStore: SongWorkStore,
) {
    suspend fun exportToUri(uri: Uri): ARMusicWorkMappingResult = withContext(Dispatchers.IO) {
        val songs = LMedia.get<LSong>(blockFilter = false)
            .sortedBy { it.metadata.title }

        runCatching {
            application.contentResolver.openOutputStream(uri, "wt")
                ?.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        writer.appendLine(TSV_HEADER.joinToString("\t"))

                        songs.forEach { song ->
                            writer.appendLine(
                                listOf(
                                    song.id,
                                    song.metadata.title,
                                    song.metadata.artist,
                                    song.metadata.album,
                                    song.fileInfo.pathStr.orEmpty(),
                                    song.metadata.duration.toString(),
                                    songWorkStore.getWork(song),
                                    "",
                                    "",
                                    "",
                                    "",
                                ).joinToString("\t") { it.toTsvCell() }
                            )
                        }
                    }
                } ?: error(MSG_CANNOT_WRITE_FILE)

            ARMusicWorkMappingResult(
                exported = songs.size,
                message = msgExported(songs.size),
            )
        }.getOrElse {
            ARMusicWorkMappingResult(message = it.message ?: MSG_EXPORT_FAILED)
        }
    }

    suspend fun importFromUri(uri: Uri): ARMusicWorkMappingResult = withContext(Dispatchers.IO) {
        val rows = application.contentResolver.openInputStream(uri)
            ?.use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    reader.readLines()
                }
            }
            .orEmpty()

        importRows(rows)
    }

    suspend fun importFromFilePath(path: String): ARMusicWorkMappingResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return@withContext ARMusicWorkMappingResult(message = msgFileNotFound(path))
        }

        importRows(file.readLines(Charsets.UTF_8))
    }

    private fun importRows(rows: List<String>): ARMusicWorkMappingResult {
        val songs = LMedia.get<LSong>(blockFilter = false)
        val byId = songs.associateBy { it.id }
        val byPath = songs
            .mapNotNull { song -> song.fileInfo.pathStr?.takeIf(String::isNotBlank)?.let { it to song } }
            .toMap()

        return runCatching {
            if (rows.isEmpty()) error(MSG_EMPTY_FILE)

            val header = rows.first().split('\t').map {
                it.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT)
            }
            val imported = rows.drop(1).fold(0) { count, line ->
                val columns = line.split('\t')
                val row = header.mapIndexedNotNull { index, key ->
                    key.takeIf(String::isNotBlank)?.let { it to columns.getOrNull(index).orEmpty().trim() }
                }.toMap()

                val work = listOf(
                    row["suggested_work"],
                    row["work"],
                    row["current_work"],
                ).firstOrNull { !it.isNullOrBlank() }
                    .orEmpty()
                    .trim()

                if (work.isBlank()) return@fold count

                val song = byId[row["media_id"].orEmpty()]
                    ?: byPath[row["file_path"].orEmpty()]
                    ?: songs.findByTitleArtist(
                        title = row["title"].orEmpty(),
                        artist = row["artist"].orEmpty(),
                    )
                    ?: return@fold count

                songWorkStore.setWork(song, work)
                count + 1
            }

            ARMusicWorkMappingResult(
                imported = imported,
                skipped = (rows.size - 1 - imported).coerceAtLeast(0),
                message = msgImported(imported),
            )
        }.getOrElse {
            ARMusicWorkMappingResult(message = it.message ?: MSG_IMPORT_FAILED)
        }
    }

    private fun List<LSong>.findByTitleArtist(title: String, artist: String): LSong? {
        val normalizedTitle = title.normalizedForWorkImport()
        val normalizedArtist = artist.normalizedForWorkImport()
        if (normalizedTitle.isBlank()) return null

        return firstOrNull { song ->
            val songTitle = song.metadata.title.normalizedForWorkImport()
            val songArtist = song.metadata.artist.normalizedForWorkImport()
            songTitle == normalizedTitle &&
                    (normalizedArtist.isBlank() || songArtist.contains(normalizedArtist) || normalizedArtist.contains(songArtist))
        }
    }

    private fun String.normalizedForWorkImport(): String {
        return lowercase(Locale.ROOT)
            .replace(BRACKETED_SUFFIX_REGEX, "")
            .replace(TITLE_SEPARATOR_REGEX, "")
            .replace("\u201c", "")
            .replace("\u201d", "")
            .replace("\u2018", "")
            .replace("\u2019", "")
            .trim()
    }

    private fun String.toTsvCell(): String {
        return replace("\t", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
    }

    companion object {
        private val TSV_HEADER = listOf(
            "media_id",
            "title",
            "artist",
            "album",
            "file_path",
            "duration_ms",
            "current_work",
            "suggested_work",
            "confidence",
            "evidence",
            "notes",
        )

        private val BRACKETED_SUFFIX_REGEX = Regex("\\([^)]*\\)|\\uFF08[^\\uFF09]*\\uFF09|\\[[^\\]]*]")
        private val TITLE_SEPARATOR_REGEX = Regex("[\\s_\\-\\u00B7\\u30FB\\u3001\\uFF0C\\u3002:\\uFF1A'\"\\\\]+")

        private const val MSG_CANNOT_WRITE_FILE = "\u65e0\u6cd5\u5199\u5165\u6587\u4ef6"
        private const val MSG_EXPORT_FAILED = "\u5bfc\u51fa\u4f5c\u54c1\u6620\u5c04\u5931\u8d25"
        private const val MSG_IMPORT_FAILED = "\u5bfc\u5165\u4f5c\u54c1\u6620\u5c04\u5931\u8d25"
        private const val MSG_EMPTY_FILE = "\u6587\u4ef6\u4e3a\u7a7a"

        private fun msgExported(count: Int) = "\u5df2\u5bfc\u51fa $count \u9996\u6b4c\u7684\u4f5c\u54c1\u6620\u5c04\u8868\u3002"
        private fun msgImported(count: Int) = "\u5df2\u5bfc\u5165 $count \u6761\u4f5c\u54c1\u6620\u5c04\u3002"
        private fun msgFileNotFound(path: String) = "\u6ca1\u6709\u627e\u5230\u4f5c\u54c1\u6620\u5c04\u6587\u4ef6\uff1a$path"
    }
}
