package com.lalilu.lalbum.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.core.screen.Screen
import com.lalilu.RemixIcon
import com.lalilu.component.base.screen.ScreenAction
import com.lalilu.component.base.screen.ScreenActionFactory
import com.lalilu.component.base.screen.ScreenBarFactory
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.songs.SongsSearcherPanel
import com.lalilu.component.base.songs.SongsSortPanelDialog
import com.lalilu.component.extension.DialogWrapper
import com.lalilu.component.extension.screenViewModel
import com.lalilu.component.work.rememberWorkLabel
import com.lalilu.lalbum.R
import com.lalilu.lalbum.viewmodel.AlbumsAction
import com.lalilu.lalbum.viewmodel.AlbumsViewModel
import com.lalilu.remixicon.Editor
import com.lalilu.remixicon.Media
import com.lalilu.remixicon.System
import com.lalilu.remixicon.editor.formatClear
import com.lalilu.remixicon.editor.sortDesc
import com.lalilu.remixicon.editor.text
import com.lalilu.remixicon.media.albumFill
import com.lalilu.remixicon.system.menuSearchLine
import com.zhangke.krouter.annotation.Destination
import org.koin.core.parameter.parametersOf

@Destination("/pages/albums")
data class AlbumsScreen(
    val albumsId: List<String> = emptyList()
) : Screen, ScreenInfoFactory, ScreenActionFactory, ScreenBarFactory {
    @Composable
    override fun provideScreenInfo(): ScreenInfo {
        val workLabel = rememberWorkLabel()
        return remember(workLabel) {
        ScreenInfo(
            title = { workLabel },
            icon = RemixIcon.Media.albumFill
        )
        }
    }

    @Composable
    override fun provideScreenActions(): List<ScreenAction> {
        val albumsViewModel = screenViewModel<AlbumsViewModel>(
            parameters = { parametersOf(albumsId) }
        )
        val state by albumsViewModel.state
        val workLabel = rememberWorkLabel()

        return remember(state.showText, state.searchKeyword, workLabel) {
            listOf(
                ScreenAction.Static(
                    title = { if (state.showText) "隐藏${workLabel}名" else "显示${workLabel}名" },
                    color = { Color(0xFF6E4AC3) },
                    icon = { if (state.showText) RemixIcon.Editor.text else RemixIcon.Editor.formatClear },
                    onAction = { albumsViewModel.intent(AlbumsAction.ToggleShowText) }
                ),
                ScreenAction.Static(
                    title = { "排序" },
                    icon = { RemixIcon.Editor.sortDesc },
                    color = { Color(0xFF1793FF) },
                    onAction = { albumsViewModel.intent(AlbumsAction.ToggleSortPanel) }
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
                        albumsViewModel.intent(AlbumsAction.ToggleSearcherPanel)
                        DialogWrapper.dismiss()
                    }
                ),
            )
        }
    }

    @Composable
    override fun Content() {
        val albumsViewModel = screenViewModel<AlbumsViewModel>(
            parameters = { parametersOf(albumsId) }
        )
        val state by albumsViewModel.state
        val albums by albumsViewModel.albums
        val workLabel = rememberWorkLabel()

        SongsSortPanelDialog(
            isVisible = { state.showSortPanel },
            onDismiss = { albumsViewModel.intent(AlbumsAction.HideSortPanel) },
            supportSortActions = albumsViewModel.supportSortActions,
            isSortActionSelected = { state.selectedSortAction == it },
            onSelectSortAction = { albumsViewModel.intent(AlbumsAction.SelectSortAction(it)) }
        )

        SongsSearcherPanel(
            isVisible = { state.showSearcherPanel },
            onDismiss = { albumsViewModel.intent(AlbumsAction.HideSearcherPanel) },
            keyword = { state.searchKeyword },
            onUpdateKeyword = { albumsViewModel.intent(AlbumsAction.SearchFor(it)) }
        )

        AlbumsScreenContent(
            eventFlow = albumsViewModel.eventFlow(),
            title = { "全部$workLabel" },
            workLabel = { workLabel },
            albums = { albums },
            showText = { state.showText }
        )
    }
}
