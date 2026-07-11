package com.lalilu.lmedia.repository

import android.app.Application
import android.net.Uri
import com.lalilu.lmedia.entity.LAlbum
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.wrapper.Taglib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SongWorkStore(
    private val application: Application,
) {
    private val sp = application.getSharedPreferences("armusic_song_works", Application.MODE_PRIVATE)
    private val sessionWorks = ConcurrentHashMap<String, String>()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    fun getManualWork(song: LSong): String {
        return getWork(song)
    }

    fun getWork(song: LSong): String {
        return getSessionWork(song)
            ?: song.metadata.work.trim()
    }

    fun setWork(song: LSong, work: String, writeFile: Boolean = false): Boolean {
        val normalized = work.trim()

        if (writeFile && !writeWorkTag(song, normalized)) {
            return false
        }

        val mediaIdKey = idKey(song.id)
        sessionWorks[mediaIdKey] = normalized
        sp.edit().apply {
            remove(mediaIdKey)
            song.fileInfo.pathStr?.takeIf { it.isNotBlank() && it != "<unknown_path>" }?.let {
                val filePathKey = pathKey(it)
                sessionWorks[filePathKey] = normalized
                remove(filePathKey)
            }
        }.apply()

        _changes.value = _changes.value + 1
        return true
    }

    fun buildWorks(songs: Collection<LSong>): List<LAlbum> {
        return songs
            .groupBy { getWork(it).workKey() }
            .values
            .map { group ->
                val name = getWork(group.first()).ifBlank { UNKNOWN_WORK }
                LAlbum(
                    id = workId(name),
                    name = name,
                    songs = group,
                    artistName = null,
                    coverOverride = getWorkCover(name),
                )
            }
            .sortedWith(
                compareBy<LAlbum> { it.name == UNKNOWN_WORK }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    fun findWork(songs: Collection<LSong>, workId: String): LAlbum? {
        return buildWorks(songs).firstOrNull { it.id == workId }
    }

    fun workForSong(song: LSong): LAlbum {
        val name = getWork(song).ifBlank { UNKNOWN_WORK }
        return LAlbum(
            id = workId(name),
            name = name,
            songs = listOf(song),
            artistName = null,
            coverOverride = getWorkCover(name),
        )
    }

    fun getWorkCover(workName: String): String? {
        return sp.getString(workCoverKey(workName), null)
    }

    fun setWorkCoverUri(workName: String, uri: String) {
        setWorkCover(workName, "$COVER_URI_PREFIX$uri")
    }

    fun setWorkCoverSong(workName: String, song: LSong) {
        setWorkCover(workName, "$COVER_SONG_PREFIX${song.id}")
    }

    fun clearWorkCover(workName: String) {
        sp.edit().remove(workCoverKey(workName)).apply()
        _changes.value = _changes.value + 1
    }

    private fun setWorkCover(workName: String, value: String) {
        sp.edit()
            .putString(workCoverKey(workName), value)
            .apply()
        _changes.value = _changes.value + 1
    }

    private fun getSessionWork(song: LSong): String? {
        val mediaIdKey = idKey(song.id)
        if (sessionWorks.containsKey(mediaIdKey)) return sessionWorks[mediaIdKey].orEmpty()

        val path = song.fileInfo.pathStr
            ?.takeIf { it.isNotBlank() && it != "<unknown_path>" }
            ?: return null
        val filePathKey = pathKey(path)
        return if (sessionWorks.containsKey(filePathKey)) {
            sessionWorks[filePathKey].orEmpty()
        } else {
            null
        }
    }

    private fun writeWorkTag(song: LSong, work: String): Boolean {
        return runCatching {
            application.contentResolver
                .openFileDescriptor(song.uri, "rw")
                ?.use { Taglib.writeWorkWithFD(it.detachFd(), work) } == true
        }.getOrDefault(false)
    }

    private fun workId(name: String): String {
        return "armusic-work:${Uri.encode(name.workKey())}"
    }

    private fun idKey(mediaId: String): String = "id:$mediaId"
    private fun pathKey(path: String): String = "path:$path"
    private fun workCoverKey(workName: String): String = "work-cover:${workName.workKey()}"

    private fun String.workKey(): String {
        return trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
            .ifBlank { UNKNOWN_WORK.lowercase(Locale.ROOT) }
    }

    private companion object {
        const val UNKNOWN_WORK = "\u65e0"
        const val COVER_URI_PREFIX = "uri:"
        const val COVER_SONG_PREFIX = "song:"
    }
}
