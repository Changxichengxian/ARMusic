package com.lalilu.lalbum.viewModel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lalilu.common.MviWithIntent
import com.lalilu.common.ext.requestFor
import com.lalilu.common.mviImplWithIntent
import com.lalilu.component.extension.toState
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LAlbum
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.extension.GroupIdentity
import com.lalilu.lmedia.extension.ListAction
import com.lalilu.lmedia.extension.SortDynamicAction
import com.lalilu.lmedia.extension.SortStaticAction
import com.lalilu.lmedia.repository.SongWorkStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.qualifier.named

@Stable
@Immutable
data class AlbumsState(
    val albumIds: List<String> = emptyList(),

    // control flags
    val showText: Boolean = false,
    val showSortPanel: Boolean = false,
    val showSearcherPanel: Boolean = false,

    // control params
    val searchKeyWord: String = "",
    val selectedSortAction: ListAction = SortStaticAction.Normal,
) {
    val distinctKey: Int =
        albumIds.hashCode() + searchKeyWord.hashCode() + selectedSortAction.hashCode()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAlbumsFlow(songWorkStore: SongWorkStore): Flow<Map<GroupIdentity, List<LAlbum>>> {
        val source = combine(
            LMedia.getFlow<LSong>(),
            songWorkStore.changes,
        ) { songs, _ ->
            songWorkStore.buildWorks(songs)
                .let { works ->
                    if (albumIds.isEmpty()) works else works.filter { it.id in albumIds }
                }
        }

        val keywords: List<String> = when {
            searchKeyWord.isBlank() -> emptyList()
            searchKeyWord.contains(' ') -> searchKeyWord.split(' ')
            else -> listOf(searchKeyWord)
        }

        val searchResult = source.mapLatest { flow ->
            flow.filter { item -> keywords.all { item.getMatchStr().contains(it) } }
        }

        return when (selectedSortAction) {
            is SortStaticAction -> searchResult.mapLatest {
                selectedSortAction.doSort(it, false)
            }

            is SortDynamicAction -> selectedSortAction.doSort(searchResult, false)
            else -> flowOf(emptyMap())
        }
    }
}

sealed interface AlbumsEvent {
    data class ScrollToItem(val key: Any) : AlbumsEvent
}

sealed interface AlbumsAction {
    data object ToggleSortPanel : AlbumsAction
    data object ToggleSearcherPanel : AlbumsAction
    data object ToggleShowText : AlbumsAction

    data object HideSortPanel : AlbumsAction
    data object HideSearcherPanel : AlbumsAction
    data object HideShowText : AlbumsAction

    data object LocaleToPlayingItem : AlbumsAction
    data class SearchFor(val keyword: String) : AlbumsAction
    data class SelectSortAction(val action: ListAction) : AlbumsAction
}

@KoinViewModel
class AlbumsVM(
    val albumIds: List<String>,
    private val songWorkStore: SongWorkStore,
    private val application: Application,
) : ViewModel(),
    MviWithIntent<AlbumsState, AlbumsEvent, AlbumsAction> by mviImplWithIntent(AlbumsState(albumIds)) {
    private val sortPreferences = application.getSharedPreferences(
        "armusic_sort_preferences",
        Application.MODE_PRIVATE,
    )
    private val sortPreferenceKey = if (albumIds.isEmpty()) {
        "works.default"
    } else {
        "works.filtered"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val albums = stateFlow()
        .distinctUntilChangedBy { it.distinctKey }
        .flatMapLatest { it.getAlbumsFlow(songWorkStore) }
        .toState(emptyMap(), viewModelScope)
    val state = stateFlow()
        .toState(AlbumsState(), viewModelScope)

    val supportSortActions: Set<ListAction> =
        setOf<ListAction?>(
            SortStaticAction.Normal,
            SortStaticAction.Title,
            SortStaticAction.ItemsCount,
            SortStaticAction.Shuffle,
            SortStaticAction.Duration,
            requestFor(named("sort_rule_play_count")),
            requestFor(named("sort_rule_last_play_time")),
            requestFor(named("sort_rule_play_duration")),
        ).filterNotNull()
            .toSet()

    init {
        supportSortActions
            .firstOrNull { it.actionKey == sortPreferences.getString(sortPreferenceKey, "") }
            ?.let { action ->
                viewModelScope.launch {
                    reduce { it.copy(selectedSortAction = action) }
                }
            }
    }

    override fun intent(intent: AlbumsAction): Any = viewModelScope.launch {
        when (intent) {
            AlbumsAction.HideSearcherPanel -> reduce { it.copy(showSearcherPanel = false) }
            AlbumsAction.HideSortPanel -> reduce { it.copy(showSortPanel = false) }
            AlbumsAction.HideShowText -> reduce { it.copy(showText = false) }

            AlbumsAction.ToggleSearcherPanel -> reduce { it.copy(showSearcherPanel = !it.showSearcherPanel) }
            AlbumsAction.ToggleSortPanel -> reduce { it.copy(showSortPanel = !it.showSortPanel) }
            AlbumsAction.ToggleShowText -> reduce { it.copy(showText = !it.showText) }

            is AlbumsAction.SearchFor -> reduce { it.copy(searchKeyWord = intent.keyword) }
            is AlbumsAction.SelectSortAction -> {
                sortPreferences.edit()
                    .putString(sortPreferenceKey, intent.action.actionKey)
                    .apply()
                reduce { it.copy(selectedSortAction = intent.action) }
            }

            AlbumsAction.LocaleToPlayingItem -> {}
        }
    }
}
