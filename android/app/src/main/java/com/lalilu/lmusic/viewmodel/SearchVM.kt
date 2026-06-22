package com.lalilu.lmusic.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lalilu.component.extension.toState
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.Item
import com.lalilu.lmedia.entity.LAlbum
import com.lalilu.lmedia.entity.LArtist
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.extension.SearchTextManager
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmedia.wrapper.Taglib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

sealed interface SearchScreenState {
    data object Idle : SearchScreenState
    data object Empty : SearchScreenState
    data class Searching(
        val songs: List<LSong>,
        val works: List<LAlbum>,
        val artists: List<LArtist>,
        val lyricSongs: List<LSong>,
    ) : SearchScreenState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Single
class SearchVM(
    private val application: Application,
    private val songWorkStore: SongWorkStore,
) : ViewModel() {
    private val _keywordStr = mutableStateOf("")
    var keywordStr: String
        get() = _keywordStr.value
        set(value) {
            _keywordStr.value = value
            _keywordsFlow.value = value
        }

    private val _keywordsFlow = MutableStateFlow("")
    private val keywords = _keywordsFlow.debounce(200).mapLatest {
        if (it.isEmpty()) return@mapLatest emptyList()
        it.trim().uppercase().split(' ')
    }

    private val _searchOptions = MutableStateFlow(SearchOptions())
    private val lyricCache = mutableMapOf<String, String>()

    private val _includeSongs = mutableStateOf(true)
    var includeSongs: Boolean
        get() = _includeSongs.value
        set(value) = updateOptions { copy(includeSongs = value) }

    private val _includeWorks = mutableStateOf(true)
    var includeWorks: Boolean
        get() = _includeWorks.value
        set(value) = updateOptions { copy(includeWorks = value) }

    private val _includeArtists = mutableStateOf(false)
    var includeArtists: Boolean
        get() = _includeArtists.value
        set(value) = updateOptions { copy(includeArtists = value) }

    private val _includeLyrics = mutableStateOf(false)
    var includeLyrics: Boolean
        get() = _includeLyrics.value
        set(value) = updateOptions { copy(includeLyrics = value) }

    private val searchParams = combine(keywords, _searchOptions) { keywordList, options ->
        SearchParams(keywordList, options)
    }

    val searchState = combine(
        flow = searchParams,
        flow2 = LMedia.getFlow<LSong>(),
        flow3 = LMedia.getFlow<LArtist>(),
        flow4 = songWorkStore.changes,
    ) { params, allSongs, allArtists, _ ->
        val keywordList = params.keywords
        val options = params.options
        val allWorks = songWorkStore.buildWorks(allSongs)

        if (keywordList.isEmpty()) return@combine SearchScreenState.Idle

        val songs = if (options.includeSongs) {
            allSongs.filter { it.matchesSong(keywordList) }
        } else {
            emptyList()
        }

        val works = if (options.includeWorks) {
            allWorks.filter { it.matchesWork(keywordList) }
        } else {
            emptyList()
        }

        val artists = if (options.includeArtists) {
            allArtists.filter { it.name.matchesKeywords(keywordList) }
        } else {
            emptyList()
        }

        val lyricSongs = if (options.includeLyrics) {
            allSongs.filterLyrics(keywordList)
        } else {
            emptyList()
        }

        if (songs.isEmpty() && works.isEmpty() && artists.isEmpty() && lyricSongs.isEmpty()) {
            return@combine SearchScreenState.Empty
        }

        SearchScreenState.Searching(
            songs = songs,
            works = works,
            artists = artists,
            lyricSongs = lyricSongs,
        )
    }.toState(SearchScreenState.Idle, viewModelScope)

    private fun updateOptions(block: SearchOptions.() -> SearchOptions) {
        val next = _searchOptions.value.block()
        _searchOptions.value = next
        _includeSongs.value = next.includeSongs
        _includeWorks.value = next.includeWorks
        _includeArtists.value = next.includeArtists
        _includeLyrics.value = next.includeLyrics
    }

    private fun LSong.matchesSong(keywordList: Collection<String>): Boolean {
        return "${metadata.title} ${metadata.artist} ${metadata.album}".matchesKeywords(keywordList)
    }

    private fun LAlbum.matchesWork(keywordList: Collection<String>): Boolean {
        return "$name ${artistName.orEmpty()}".matchesKeywords(keywordList)
    }

    private suspend fun List<LSong>.filterLyrics(keywordList: Collection<String>): List<LSong> =
        withContext(Dispatchers.IO) {
            filter { song ->
                val lyric = lyricCache.getOrPut(song.id) {
                    runCatching {
                        application.contentResolver.openFileDescriptor(song.uri, "r")?.use {
                            Taglib.getLyricWithFD(it.detachFd())
                        }.orEmpty()
                    }.getOrDefault("")
                }

                lyric.isNotBlank() && lyric.matchesKeywords(keywordList)
            }
        }

    private fun String.matchesKeywords(keywordList: Collection<String>): Boolean {
        if (keywordList.isEmpty()) return false
        val pattern = SearchTextManager.createPatternString(this)
        return keywordList.all { pattern.contains(it) }
    }

    private data class SearchOptions(
        val includeSongs: Boolean = true,
        val includeWorks: Boolean = true,
        val includeArtists: Boolean = false,
        val includeLyrics: Boolean = false,
    )

    private data class SearchParams(
        val keywords: Collection<String>,
        val options: SearchOptions,
    )
}
