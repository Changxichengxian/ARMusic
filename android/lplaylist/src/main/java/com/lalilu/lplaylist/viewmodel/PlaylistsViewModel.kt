package com.lalilu.lplaylist.viewmodel

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lalilu.common.MviWithIntent
import com.lalilu.common.mviImplWithIntent
import com.lalilu.component.extension.ItemSelector
import com.lalilu.component.extension.toState
import com.lalilu.lplaylist.entity.LPlaylist
import com.lalilu.lplaylist.repository.PlaylistRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@Stable
@Immutable
data class PlaylistsState(
    // control flags
    val showSearcherPanel: Boolean = false,

    // control params
    val searchKeyword: String = "",
) {
    val distinctKey: Int = searchKeyword.hashCode()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun buildPlaylistsFlow(playlistRepo: PlaylistRepository): Flow<List<LPlaylist>> {
        val sources = playlistRepo.getPlaylistsFlow()

        val keywords: List<String> = when {
            searchKeyword.isBlank() -> emptyList()
            searchKeyword.contains(' ') -> searchKeyword.split(' ')
            else -> listOf(searchKeyword)
        }

        val searchResult = sources.mapLatest { flow ->
            flow.filter { item -> keywords.all { item.getMatchStr().contains(it) } }
        }

        return searchResult
    }
}

sealed interface PlaylistsAction {
    data class UpdatePlaylist(val playlists: List<LPlaylist>) : PlaylistsAction
    data class TryRemovePlaylist(val playlists: Collection<LPlaylist>) : PlaylistsAction
    data class SearchFor(val keyword: String) : PlaylistsAction
    data object HideSearcherPanel : PlaylistsAction
    data object ShowSearcherPanel : PlaylistsAction
}

sealed interface PlaylistsEvent {

}

@KoinViewModel
class PlaylistsViewModel(private val playlistRepo: PlaylistRepository) : ViewModel(),
    MviWithIntent<PlaylistsState, PlaylistsEvent, PlaylistsAction>
    by mviImplWithIntent(PlaylistsState()) {

    val selector = ItemSelector<LPlaylist>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlists = stateFlow()
        .distinctUntilChangedBy { it.distinctKey }
        .flatMapLatest { it.buildPlaylistsFlow(playlistRepo) }
        .toState(emptyList(), viewModelScope)

    val state = stateFlow().toState(PlaylistsState(), viewModelScope)

    override fun intent(intent: PlaylistsAction) = viewModelScope.launch {
        when (intent) {
            is PlaylistsAction.UpdatePlaylist -> {
                playlistRepo.setPlaylists(intent.playlists)
            }

            is PlaylistsAction.TryRemovePlaylist -> {
                playlistRepo.removeByIds(intent.playlists.map { it.id })
            }

            is PlaylistsAction.SearchFor -> {
                reduce { it.copy(searchKeyword = intent.keyword) }
            }

            is PlaylistsAction.HideSearcherPanel -> {
                reduce { it.copy(showSearcherPanel = false) }
            }

            is PlaylistsAction.ShowSearcherPanel -> {
                reduce { it.copy(showSearcherPanel = true) }
            }

            else -> {}
        }
    }
}