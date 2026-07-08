package com.lalilu.lplaylist.screen.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import com.lalilu.RemixIcon
import com.lalilu.common.ext.requestFor
import com.lalilu.component.base.screen.ScreenAction
import com.lalilu.component.base.screen.ScreenActionFactory
import com.lalilu.component.base.screen.ScreenBarFactory
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.songs.SongsHeaderJumperDialog
import com.lalilu.component.base.songs.SongsSearcherPanel
import com.lalilu.component.base.songs.SongsSelectorPanel
import com.lalilu.component.base.songs.SongsSortPanelDialog
import com.lalilu.component.extension.DialogWrapper
import com.lalilu.component.extension.screenViewModel
import com.lalilu.lmedia.extension.SortStaticAction
import com.lalilu.lplaylist.R
import com.lalilu.lplaylist.viewmodel.PlaylistDetailAction
import com.lalilu.lplaylist.viewmodel.PlaylistDetailViewModel
import com.lalilu.remixicon.Design
import com.lalilu.remixicon.Editor
import com.lalilu.remixicon.System
import com.lalilu.remixicon.design.editBoxLine
import com.lalilu.remixicon.design.focus3Line
import com.lalilu.remixicon.editor.sortDesc
import com.lalilu.remixicon.system.checkboxMultipleBlankLine
import com.lalilu.remixicon.system.checkboxMultipleLine
import com.lalilu.remixicon.system.deleteBinLine
import com.lalilu.remixicon.system.menuSearchLine
import com.zhangke.krouter.annotation.Destination
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named

@Destination("/pages/playlist/detail")
data class PlaylistDetailScreen(
    val playlistId: String
) : Screen, ScreenInfoFactory, ScreenActionFactory, ScreenBarFactory {
    override val key: ScreenKey = "${super.key}:$playlistId"

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { stringResource(id = R.string.playlist_screen_detail) }
        )
    }

    @Composable
    override fun provideScreenActions(): List<ScreenAction> {
        val playlistDetailViewModel = screenViewModel<PlaylistDetailViewModel>(
            parameters = { parametersOf(playlistId) }
        )

        val state by playlistDetailViewModel.state

        return remember {
            listOf(
                ScreenAction.Static(
                    title = { "排序" },
                    icon = { RemixIcon.Editor.sortDesc },
                    color = { Color(0xFF1793FF) },
                    onAction = { playlistDetailViewModel.intent(PlaylistDetailAction.ToggleSortPanel) }
                ),
                ScreenAction.Static(
                    title = { "选择" },
                    icon = { RemixIcon.Design.editBoxLine },
                    color = { Color(0xFF009673) },
                    onAction = { playlistDetailViewModel.selector.isSelecting.value = true }
                ),
                ScreenAction.Static(
                    title = { "搜索" },
                    subTitle = {
                        val keyword = state.searchKeyword
                        if (keyword.isNotBlank()) "搜索中： $keyword" else null
                    },
                    icon = { RemixIcon.System.menuSearchLine },
                    color = { Color(0xFF8BC34A) },
                    dotColor = {
                        val keyword = state.searchKeyword
                        if (keyword.isNotBlank()) Color.Red else null
                    },
                    onAction = {
                        playlistDetailViewModel.intent(PlaylistDetailAction.ToggleSearcherPanel)
                        DialogWrapper.dismiss()
                    }
                ),
                ScreenAction.Static(
                    title = { "定位至当前播放歌曲" },
                    icon = { RemixIcon.Design.focus3Line },
                    color = { Color(0xFF8700FF) },
                    onAction = { playlistDetailViewModel.intent(PlaylistDetailAction.LocateToPlayingItem) }
                ),
            )
        }
    }

    @Composable
    override fun Content() {
        val playlistDetailViewModel = screenViewModel<PlaylistDetailViewModel>(
            parameters = { parametersOf(playlistId) }
        )

        val state by playlistDetailViewModel.state
        val songs by playlistDetailViewModel.songs
        val playlist by playlistDetailViewModel.playlist

        SongsSortPanelDialog(
            isVisible = { state.showSortPanel },
            onDismiss = { playlistDetailViewModel.intent(PlaylistDetailAction.HideSortPanel) },
            supportSortActions = playlistDetailViewModel.supportSortActions,
            isSortActionSelected = { state.selectedSortAction == it },
            onSelectSortAction = { playlistDetailViewModel.intent(PlaylistDetailAction.SelectSortAction(it)) }
        )

        SongsHeaderJumperDialog(
            isVisible = { state.showJumperDialog },
            onDismiss = { playlistDetailViewModel.intent(PlaylistDetailAction.HideJumperDialog) },
            items = { songs.keys },
            onSelectItem = { playlistDetailViewModel.intent(PlaylistDetailAction.LocateToGroupItem(it)) }
        )

        SongsSearcherPanel(
            isVisible = { state.showSearcherPanel },
            onDismiss = { playlistDetailViewModel.intent(PlaylistDetailAction.HideSearcherPanel) },
            keyword = { state.searchKeyword },
            onUpdateKeyword = { playlistDetailViewModel.intent(PlaylistDetailAction.SearchFor(it)) }
        )

        SongsSelectorPanel(
            isVisible = { playlistDetailViewModel.selector.isSelecting.value },
            onDismiss = { playlistDetailViewModel.selector.isSelecting.value = false },
            screenActions = listOfNotNull(
                ScreenAction.Static(
                    title = { "全选" },
                    color = { Color(0xFF00ACF0) },
                    icon = { RemixIcon.System.checkboxMultipleLine },
                    onAction = { playlistDetailViewModel.selector.selectAll(playlistDetailViewModel.songs.value.values.flatten()) }
                ),
                ScreenAction.Static(
                    title = { "取消全选" },
                    icon = { RemixIcon.System.checkboxMultipleBlankLine },
                    color = { Color(0xFFFF5100) },
                    onAction = { playlistDetailViewModel.selector.clear() }
                ),
                ScreenAction.Static(
                    title = { "删除" },
                    icon = { RemixIcon.System.deleteBinLine },
                    longClick = { true },
                    color = { Color(0xFFF5381D) },
                    onAction = {
                        val ids = playlistDetailViewModel.selector.selected().map { it.id }
                        playlistDetailViewModel.intent(PlaylistDetailAction.RemoveItems(ids))
                    }
                ),
                requestFor<ScreenAction>(
                    qualifier = named("add_to_playlist_action"),
                    parameters = { parametersOf(playlistDetailViewModel.selector::selected) }
                )
            )
        )

        PlaylistDetailScreenContent(
            songs = songs,
            playlist = playlist,
            enableDraggable = state.selectedSortAction is SortStaticAction.Normal,
            keys = { playlistDetailViewModel.recorder.list().filterNotNull() },
            recorder = playlistDetailViewModel.recorder,
            eventFlow = playlistDetailViewModel.eventFlow(),
            isSelecting = { playlistDetailViewModel.selector.isSelecting.value },
            isSelected = { playlistDetailViewModel.selector.isSelected(it) },
            selectedSortAction = state.selectedSortAction,
            onSelect = { playlistDetailViewModel.selector.onSelect(it) },
            onClickGroup = { playlistDetailViewModel.intent(PlaylistDetailAction.ToggleJumperDialog) },
            onUpdatePlaylist = { playlistDetailViewModel.intent(PlaylistDetailAction.UpdatePlaylist(it)) }
        )
    }
}
