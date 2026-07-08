package com.lalilu.lalbum.viewmodel


import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.LogUtils
import com.lalilu.common.MviWithIntent
import com.lalilu.common.ext.requestFor
import com.lalilu.common.mviImplWithIntent
import com.lalilu.component.extension.ItemRecorder
import com.lalilu.component.extension.ItemSelector
import com.lalilu.component.extension.toState
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LAlbum
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.extension.GroupIdentity
import com.lalilu.lmedia.extension.ListAction
import com.lalilu.lmedia.extension.SortDynamicAction
import com.lalilu.lmedia.extension.SortStaticAction
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lplayer.MPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.qualifier.named


@Stable
@Immutable
data class AlbumDetailState(
    val albumId: String,

    // control flags
    val showSortPanel: Boolean = false,
    val showJumperDialog: Boolean = false,
    val showSearcherPanel: Boolean = false,

    // control params
    val searchKeyword: String = "",
    val selectedSortAction: ListAction = SortStaticAction.Normal,
) {
    val distinctKey: Int =
        albumId.hashCode() + searchKeyword.hashCode() + selectedSortAction.hashCode()

    fun buildAlbumFlow(songWorkStore: SongWorkStore): Flow<LAlbum?> {
        return combine(
            LMedia.getFlow<LSong>(),
            songWorkStore.changes,
        ) { songs, _ ->
            songWorkStore.findWork(songs, albumId)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun buildSongsFlow(songWorkStore: SongWorkStore): Flow<Map<GroupIdentity, List<LSong>>> {
        val source = buildAlbumFlow(songWorkStore)
            .map { it?.songs ?: emptyList() }

        val keywords: List<String> = when {
            searchKeyword.isBlank() -> emptyList()
            searchKeyword.contains(' ') -> searchKeyword.split(' ')
            else -> listOf(searchKeyword)
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

sealed interface AlbumDetailEvent {
    data class ScrollToItem(val key: Any) : AlbumDetailEvent
}

sealed interface AlbumDetailAction {
    data object ToggleSortPanel : AlbumDetailAction
    data object ToggleSearcherPanel : AlbumDetailAction
    data object ToggleJumperDialog : AlbumDetailAction

    data object HideSortPanel : AlbumDetailAction
    data object HideSearcherPanel : AlbumDetailAction
    data object HideJumperDialog : AlbumDetailAction

    data object LocateToPlayingItem : AlbumDetailAction
    data class LocateToGroupItem(val item: GroupIdentity) : AlbumDetailAction
    data class SearchFor(val keyword: String) : AlbumDetailAction
    data class SelectSortAction(val action: ListAction) : AlbumDetailAction
    data class SetCoverUri(val uri: String) : AlbumDetailAction
    data class SetCoverSong(val song: LSong) : AlbumDetailAction
    data object ClearCover : AlbumDetailAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@KoinViewModel
class AlbumDetailViewModel(
    private val albumId: String,
    private val songWorkStore: SongWorkStore,
    private val application: Application,
) : ViewModel(),
    MviWithIntent<AlbumDetailState, AlbumDetailEvent, AlbumDetailAction> by
    mviImplWithIntent(AlbumDetailState(albumId)) {
    val selector = ItemSelector<LSong>()
    val recorder = ItemRecorder()
    private val sortPreferences = application.getSharedPreferences(
        "armusic_sort_preferences",
        Application.MODE_PRIVATE,
    )
    private val sortPreferenceKey = "work.detail"

    val songs = stateFlow()
        .distinctUntilChangedBy { it.distinctKey }
        .flatMapLatest { it.buildSongsFlow(songWorkStore) }
        .toState(emptyMap(), viewModelScope)
    val album = stateFlow()
        .flatMapLatest { it.buildAlbumFlow(songWorkStore) }
        .toState(viewModelScope)
    val state = stateFlow()
        .toState(AlbumDetailState(albumId), viewModelScope)

    val supportSortActions: Set<ListAction> =
        setOf<ListAction?>(
            SortStaticAction.Normal,
            SortStaticAction.Title,
            SortStaticAction.AddTime,
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

    override fun intent(intent: AlbumDetailAction) = viewModelScope.launch {
        when (intent) {
            AlbumDetailAction.ToggleJumperDialog -> reduce { it.copy(showJumperDialog = !it.showJumperDialog) }
            AlbumDetailAction.ToggleSearcherPanel -> reduce { it.copy(showSearcherPanel = !it.showSearcherPanel) }
            AlbumDetailAction.ToggleSortPanel -> reduce { it.copy(showSortPanel = !it.showSortPanel) }
            AlbumDetailAction.HideSortPanel -> reduce { it.copy(showSortPanel = false) }
            AlbumDetailAction.HideSearcherPanel -> reduce { it.copy(showSearcherPanel = false) }
            AlbumDetailAction.HideJumperDialog -> reduce { it.copy(showJumperDialog = false) }
            is AlbumDetailAction.SearchFor -> reduce { it.copy(searchKeyword = intent.keyword) }
            is AlbumDetailAction.SelectSortAction -> {
                sortPreferences.edit()
                    .putString(sortPreferenceKey, intent.action.actionKey)
                    .apply()
                reduce { it.copy(selectedSortAction = intent.action) }
            }
            is AlbumDetailAction.SetCoverUri -> {
                album.value?.name?.let { songWorkStore.setWorkCoverUri(it, intent.uri) }
            }

            is AlbumDetailAction.SetCoverSong -> {
                album.value?.name?.let { songWorkStore.setWorkCoverSong(it, intent.song) }
            }

            AlbumDetailAction.ClearCover -> {
                album.value?.name?.let { songWorkStore.clearWorkCover(it) }
            }

            is AlbumDetailAction.LocateToGroupItem -> postEvent {
                AlbumDetailEvent.ScrollToItem(
                    intent.item
                )
            }

            is AlbumDetailAction.LocateToPlayingItem -> {
                val mediaId = MPlayer.currentMediaItem?.mediaId ?: run {
                    LogUtils.e("can not find playing item's mediaId")
                    return@launch
                }
                postEvent { AlbumDetailEvent.ScrollToItem(mediaId) }
            }

            else -> {
                LogUtils.i("Not implemented action: $intent")
            }
        }
    }
}
