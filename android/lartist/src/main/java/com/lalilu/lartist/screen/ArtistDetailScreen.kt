package com.lalilu.lartist.screen

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
import com.lalilu.component.base.screen.ScreenType
import com.lalilu.component.base.songs.SongsHeaderJumperDialog
import com.lalilu.component.base.songs.SongsSearcherPanel
import com.lalilu.component.base.songs.SongsSelectorPanel
import com.lalilu.component.base.songs.SongsSortPanelDialog
import com.lalilu.component.extension.DialogWrapper
import com.lalilu.component.extension.screenViewModel
import com.lalilu.lartist.R
import com.lalilu.lartist.viewmodel.ArtistDetailAction
import com.lalilu.lartist.viewmodel.ArtistDetailViewModel
import com.lalilu.remixicon.Design
import com.lalilu.remixicon.Editor
import com.lalilu.remixicon.System
import com.lalilu.remixicon.design.editBoxLine
import com.lalilu.remixicon.design.focus3Line
import com.lalilu.remixicon.editor.sortDesc
import com.lalilu.remixicon.system.checkboxMultipleBlankLine
import com.lalilu.remixicon.system.checkboxMultipleLine
import com.lalilu.remixicon.system.menuSearchLine
import com.zhangke.krouter.annotation.Destination
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named


@Destination("/pages/artist/detail")
data class ArtistDetailScreen(
    private val artistName: String
) : Screen, ScreenInfoFactory, ScreenActionFactory, ScreenBarFactory, ScreenType.List {
    override val key: ScreenKey = "ARTIST_DETAIL_$artistName"

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { stringResource(id = R.string.artist_screen_detail) },
        )
    }

    @Composable
    override fun provideScreenActions(): List<ScreenAction> {
        val artistDetailViewModel = screenViewModel<ArtistDetailViewModel>(
            parameters = { parametersOf(artistName) }
        )
        val state by artistDetailViewModel.state

        return remember {
            listOf(
                ScreenAction.Static(
                    title = { "排序" },
                    icon = { RemixIcon.Editor.sortDesc },
                    color = { Color(0xFF1793FF) },
                    onAction = { artistDetailViewModel.intent(ArtistDetailAction.ToggleSortPanel) }
                ),
                ScreenAction.Static(
                    title = { "选择" },
                    icon = { RemixIcon.Design.editBoxLine },
                    color = { Color(0xFF009673) },
                    onAction = { artistDetailViewModel.selector.isSelecting.value = true }
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
                        artistDetailViewModel.intent(ArtistDetailAction.ToggleSearcherPanel)
                        DialogWrapper.dismiss()
                    }
                ),
                ScreenAction.Static(
                    title = { "定位至当前播放歌曲" },
                    icon = { RemixIcon.Design.focus3Line },
                    color = { Color(0xFF8700FF) },
                    onAction = { artistDetailViewModel.intent(ArtistDetailAction.LocateToPlayingItem) }
                ),
            )
        }
    }

    @Composable
    override fun Content() {
        val artistDetailViewModel = screenViewModel<ArtistDetailViewModel>(
            parameters = { parametersOf(artistName) }
        )
        val songs by artistDetailViewModel.songs
        val state by artistDetailViewModel.state
        val artist by artistDetailViewModel.artist

        SongsSortPanelDialog(
            isVisible = { state.showSortPanel },
            onDismiss = { artistDetailViewModel.intent(ArtistDetailAction.HideSortPanel) },
            supportSortActions = artistDetailViewModel.supportSortActions,
            isSortActionSelected = { state.selectedSortAction == it },
            onSelectSortAction = { artistDetailViewModel.intent(ArtistDetailAction.SelectSortAction(it)) }
        )

        SongsHeaderJumperDialog(
            isVisible = { state.showJumperDialog },
            onDismiss = { artistDetailViewModel.intent(ArtistDetailAction.HideJumperDialog) },
            items = { songs.keys },
            onSelectItem = { artistDetailViewModel.intent(ArtistDetailAction.LocateToGroupItem(it)) }
        )

        SongsSearcherPanel(
            isVisible = { state.showSearcherPanel },
            onDismiss = { artistDetailViewModel.intent(ArtistDetailAction.HideSearcherPanel) },
            keyword = { state.searchKeyword },
            onUpdateKeyword = { artistDetailViewModel.intent(ArtistDetailAction.SearchFor(it)) }
        )

        SongsSelectorPanel(
            isVisible = { artistDetailViewModel.selector.isSelecting.value },
            onDismiss = { artistDetailViewModel.selector.isSelecting.value = false },
            screenActions = listOfNotNull(
                ScreenAction.Static(
                    title = { "全选" },
                    color = { Color(0xFF00ACF0) },
                    icon = { RemixIcon.System.checkboxMultipleLine },
                    onAction = { artistDetailViewModel.selector.selectAll(songs.values.flatten()) }
                ),
                ScreenAction.Static(
                    title = { "取消全选" },
                    icon = { RemixIcon.System.checkboxMultipleBlankLine },
                    color = { Color(0xFFFF5100) },
                    onAction = { artistDetailViewModel.selector.clear() }
                ),
                requestFor<ScreenAction>(
                    qualifier = named("add_to_playlist_action"),
                    parameters = { parametersOf(artistDetailViewModel.selector::selected) }
                )
            )
        )

        ArtistDetailScreenContent(
            songs = songs,
            artist = artist,
            recorder = artistDetailViewModel.recorder,
            eventFlow = artistDetailViewModel.eventFlow(),
            keys = { artistDetailViewModel.recorder.list().filterNotNull() },
            isSelecting = { artistDetailViewModel.selector.isSelecting.value },
            isSelected = { artistDetailViewModel.selector.isSelected(it) },
            selectedSortAction = state.selectedSortAction,
            onSelect = { artistDetailViewModel.selector.onSelect(it) },
            onClickGroup = { artistDetailViewModel.intent(ArtistDetailAction.ToggleJumperDialog) }
        )
    }
}

fun Long.durationToTime(): String {
    val hour = this / 3600000
    val minute = this / 60000 % 60
    val second = this / 1000 % 60
    return if (hour > 0L) "%02d:%02d:%02d".format(hour, minute, second)
    else "%02d:%02d".format(minute, second)
}
