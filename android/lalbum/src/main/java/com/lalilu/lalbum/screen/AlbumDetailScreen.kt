package com.lalilu.lalbum.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.lalilu.component.work.rememberWorkLabel
import com.lalilu.lalbum.R
import com.lalilu.lalbum.viewmodel.AlbumDetailAction
import com.lalilu.lalbum.viewmodel.AlbumDetailViewModel
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

@Destination("/pages/albums/detail")
data class AlbumDetailScreen(
    private val albumId: String
) : Screen, ScreenInfoFactory, ScreenActionFactory, ScreenBarFactory {
    override val key: ScreenKey = "${super.key}:$albumId"

    @Composable
    override fun provideScreenInfo(): ScreenInfo {
        val workLabel = rememberWorkLabel()
        return remember(workLabel) {
            ScreenInfo(
                title = { workLabel }
            )
        }
    }

    @Composable
    override fun provideScreenActions(): List<ScreenAction> {
        val albumDetailViewModel = screenViewModel<AlbumDetailViewModel>(
            parameters = { parametersOf(albumId) }
        )
        val state by albumDetailViewModel.state

        return remember {
            listOf(
                ScreenAction.Static(
                    title = { "排序" },
                    icon = { RemixIcon.Editor.sortDesc },
                    color = { Color(0xFF1793FF) },
                    onAction = { albumDetailViewModel.intent(AlbumDetailAction.ToggleSortPanel) }
                ),
                ScreenAction.Static(
                    title = { "选择" },
                    icon = { RemixIcon.Design.editBoxLine },
                    color = { Color(0xFF009673) },
                    onAction = { albumDetailViewModel.selector.isSelecting.value = true }
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
                        albumDetailViewModel.intent(AlbumDetailAction.ToggleSearcherPanel)
                        DialogWrapper.dismiss()
                    }
                ),
                ScreenAction.Static(
                    title = { "定位至当前播放歌曲" },
                    icon = { RemixIcon.Design.focus3Line },
                    color = { Color(0xFF8700FF) },
                    onAction = { albumDetailViewModel.intent(AlbumDetailAction.LocateToPlayingItem) }
                ),
            )
        }
    }

    @Composable
    override fun Content() {
        val albumDetailViewModel = screenViewModel<AlbumDetailViewModel>(
            parameters = { parametersOf(albumId) }
        )
        val songs by albumDetailViewModel.songs
        val state by albumDetailViewModel.state
        val album by albumDetailViewModel.album
        val context = LocalContext.current
        val workLabel = rememberWorkLabel()
        val coverPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            albumDetailViewModel.intent(AlbumDetailAction.SetCoverUri(uri.toString()))
        }

        SongsSortPanelDialog(
            isVisible = { state.showSortPanel },
            onDismiss = { albumDetailViewModel.intent(AlbumDetailAction.HideSortPanel) },
            supportSortActions = albumDetailViewModel.supportSortActions,
            isSortActionSelected = { state.selectedSortAction == it },
            onSelectSortAction = { albumDetailViewModel.intent(AlbumDetailAction.SelectSortAction(it)) }
        )

        SongsHeaderJumperDialog(
            isVisible = { state.showJumperDialog },
            onDismiss = { albumDetailViewModel.intent(AlbumDetailAction.HideJumperDialog) },
            items = { songs.keys },
            onSelectItem = { albumDetailViewModel.intent(AlbumDetailAction.LocateToGroupItem(it)) }
        )

        SongsSearcherPanel(
            isVisible = { state.showSearcherPanel },
            onDismiss = { albumDetailViewModel.intent(AlbumDetailAction.HideSearcherPanel) },
            keyword = { state.searchKeyword },
            onUpdateKeyword = { albumDetailViewModel.intent(AlbumDetailAction.SearchFor(it)) }
        )

        SongsSelectorPanel(
            isVisible = { albumDetailViewModel.selector.isSelecting.value },
            onDismiss = { albumDetailViewModel.selector.isSelecting.value = false },
            screenActions = listOfNotNull(
                ScreenAction.Static(
                    title = { "全选" },
                    color = { Color(0xFF00ACF0) },
                    icon = { RemixIcon.System.checkboxMultipleLine },
                    onAction = { albumDetailViewModel.selector.selectAll(songs.values.flatten()) }
                ),
                ScreenAction.Static(
                    title = { "取消全选" },
                    icon = { RemixIcon.System.checkboxMultipleBlankLine },
                    color = { Color(0xFFFF5100) },
                    onAction = { albumDetailViewModel.selector.clear() }
                ),
                requestFor<ScreenAction>(
                    qualifier = named("add_to_playlist_action"),
                    parameters = { parametersOf(albumDetailViewModel.selector::selected) }
                )
            )
        )

        AlbumDetailScreenContent(
            songs = songs,
            album = album,
            recorder = albumDetailViewModel.recorder,
            eventFlow = albumDetailViewModel.eventFlow(),
            keys = { albumDetailViewModel.recorder.list().filterNotNull() },
            isSelecting = { albumDetailViewModel.selector.isSelecting.value },
            isSelected = { albumDetailViewModel.selector.isSelected(it) },
            selectedSortAction = state.selectedSortAction,
            onSelect = { albumDetailViewModel.selector.onSelect(it) },
            onClickGroup = { albumDetailViewModel.intent(AlbumDetailAction.ToggleJumperDialog) },
            onPickCoverFromStorage = { coverPickerLauncher.launch(arrayOf("image/*")) },
            onUseSongCover = { albumDetailViewModel.intent(AlbumDetailAction.SetCoverSong(it)) },
            onClearCover = { albumDetailViewModel.intent(AlbumDetailAction.ClearCover) },
            workLabel = { workLabel },
        )
    }
}
