package com.lalilu.lartist.screen.artists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.core.screen.Screen
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.RemixIcon
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
import com.lalilu.lartist.R
import com.lalilu.lartist.viewmodel.ArtistsAction
import com.lalilu.lartist.viewmodel.ArtistsViewModel
import com.lalilu.remixicon.Design
import com.lalilu.remixicon.Editor
import com.lalilu.remixicon.System
import com.lalilu.remixicon.UserAndFaces
import com.lalilu.remixicon.design.editBoxLine
import com.lalilu.remixicon.design.focus3Line
import com.lalilu.remixicon.editor.sortDesc
import com.lalilu.remixicon.system.checkboxMultipleBlankLine
import com.lalilu.remixicon.system.checkboxMultipleLine
import com.lalilu.remixicon.system.menuSearchLine
import com.lalilu.remixicon.userandfaces.userLine
import com.zhangke.krouter.annotation.Destination

@Destination("/pages/artists")
object ArtistsScreen : Screen, ScreenInfoFactory, ScreenActionFactory, ScreenBarFactory {
    private fun readResolve(): Any = ArtistsScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { stringResource(id = R.string.artist_screen_title) },
            icon = RemixIcon.UserAndFaces.userLine
        )
    }

    @Composable
    override fun provideScreenActions(): List<ScreenAction> {
        val artistsViewModel = screenViewModel<ArtistsViewModel>()
        val state by artistsViewModel.state

        return remember {
            listOf(
                ScreenAction.Static(
                    title = { "排序" },
                    icon = { RemixIcon.Editor.sortDesc },
                    color = { Color(0xFF1793FF) },
                    onAction = { artistsViewModel.intent(ArtistsAction.ToggleSortPanel) }
                ),
                ScreenAction.Static(
                    title = { "选择" },
                    icon = { RemixIcon.Design.editBoxLine },
                    color = { Color(0xFF009673) },
                    onAction = { artistsViewModel.selector.isSelecting.value = true }
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
                        artistsViewModel.intent(ArtistsAction.ToggleSearcherPanel)
                        DialogWrapper.dismiss()
                    }
                ),
                ScreenAction.Static(
                    title = { "定位当前播放所属" },
                    icon = { RemixIcon.Design.focus3Line },
                    color = { Color(0xFF8700FF) },
                    onAction = { artistsViewModel.intent(ArtistsAction.LocateToPlayingItem) }
                ),
            )
        }
    }

    @Composable
    override fun Content() {
        val artistsViewModel = screenViewModel<ArtistsViewModel>()
        val state by artistsViewModel.state
        val artists by artistsViewModel.artists

        SongsSortPanelDialog(
            isVisible = { state.showSortPanel },
            onDismiss = { artistsViewModel.intent(ArtistsAction.HideSortPanel) },
            supportSortActions = artistsViewModel.supportSortActions,
            isSortActionSelected = { state.selectedSortAction == it },
            onSelectSortAction = { artistsViewModel.intent(ArtistsAction.SelectSortAction(it)) }
        )

        SongsHeaderJumperDialog(
            isVisible = { state.showJumperDialog },
            onDismiss = { artistsViewModel.intent(ArtistsAction.HideJumperDialog) },
            items = { artists.keys },
            onSelectItem = { artistsViewModel.intent(ArtistsAction.LocateToGroupItem(it)) }
        )

        SongsSearcherPanel(
            isVisible = { state.showSearcherPanel },
            onDismiss = { artistsViewModel.intent(ArtistsAction.HideSearcherPanel) },
            keyword = { state.searchKeyword },
            onUpdateKeyword = { artistsViewModel.intent(ArtistsAction.SearchFor(it)) }
        )

        SongsSelectorPanel(
            isVisible = { artistsViewModel.selector.isSelecting.value },
            onDismiss = { artistsViewModel.selector.isSelecting.value = false },
            screenActions = listOfNotNull(
                ScreenAction.Static(
                    title = { "全选" },
                    color = { Color(0xFF00ACF0) },
                    icon = { RemixIcon.System.checkboxMultipleLine },
                    onAction = { artistsViewModel.selector.selectAll(artists.values.flatten()) }
                ),
                ScreenAction.Static(
                    title = { "取消全选" },
                    icon = { RemixIcon.System.checkboxMultipleBlankLine },
                    color = { Color(0xFFFF5100) },
                    onAction = { artistsViewModel.selector.clear() }
                ),
                ScreenAction.Static(
                    title = { "添加到播放列表" },
                    icon = { RemixIcon.System.checkboxMultipleBlankLine },
                    color = { Color(0xFF002FB9) },
                    onAction = {
                        // TODO 选择歌手后将其列表下歌曲添加到播放列表
                        ToastUtils.showShort("开发中，敬请期待")
                    }
                ),
            )
        )

        ArtistsScreenContent(
            artists = artists,
            recorder = artistsViewModel.recorder,
            eventFlow = artistsViewModel.eventFlow(),
            keys = { artistsViewModel.recorder.list().filterNotNull() },
            isSelecting = { artistsViewModel.selector.isSelecting.value },
            isSelected = { artistsViewModel.selector.isSelected(it) },
            onSelect = { artistsViewModel.selector.onSelect(it) },
            onClickGroup = { artistsViewModel.intent(ArtistsAction.ToggleJumperDialog) }
        )
    }
}