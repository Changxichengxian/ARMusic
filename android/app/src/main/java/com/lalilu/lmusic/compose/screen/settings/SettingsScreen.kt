package com.lalilu.lmusic.compose.screen.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.google.accompanist.flowlayout.FlowRow
import com.funny.data_saver.core.DataSaverMutableState
import com.lalilu.lmusic.ARMusicLanguage
import com.lalilu.BuildConfig
import com.lalilu.R
import com.lalilu.RemixIcon
import com.lalilu.component.IconTextButton
import com.lalilu.component.base.NavigatorHeader
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.extension.rememberFixedStatusBarHeightDp
import com.lalilu.component.settings.SettingCategory
import com.lalilu.component.settings.SettingProgressSeekBar
import com.lalilu.component.settings.SettingStateSeekBar
import com.lalilu.component.settings.SettingSwitcher
import com.lalilu.component.work.WorkLabel
import com.lalilu.component.work.rememberWorkLabelOptions
import com.lalilu.component.navigation.AppRouter
import com.lalilu.component.navigation.NavIntent
import com.lalilu.crash.CrashHelper
import com.lalilu.lmedia.repository.LMediaSp
import com.lalilu.lmedia.scanner.FileSystemScanner
import com.lalilu.lmedia.scanner.PathExclusionMatcher
import com.lalilu.lmusic.compose.component.playing.SettingFontLibrary
import com.lalilu.lmusic.compose.screen.sync.ARMusicLanSyncScreen
import com.lalilu.lmusic.compose.screen.playing.lyric.LyricSettings
import com.lalilu.lmusic.compose.screen.playing.lyric.SerializableFont
import com.lalilu.lmusic.datastore.SettingsSp
import com.lalilu.lmusic.migration.ARMusicWorkMappingManager
import com.lalilu.lmusic.migration.LMusicMigrationManager
import com.lalilu.lmusic.utils.EQHelper
import com.lalilu.lmusic.utils.extension.getActivity
import com.lalilu.remixicon.System
import com.lalilu.remixicon.system.settings4Line
import com.zhangke.krouter.KRouter
import com.zhangke.krouter.annotation.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import kotlin.math.roundToInt

@Destination("/pages/settings")
object SettingsScreen : Screen, ScreenInfoFactory {
    private fun readResolve(): Any = SettingsScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { stringResource(id = R.string.screen_title_settings) },
            icon = RemixIcon.System.settings4Line,
        )
    }

    @Composable
    override fun Content() {
        SettingsScreen()
    }
}


@SuppressLint("PrivateApi")
@Composable
private fun SettingsScreen(
    eqHelper: EQHelper = koinInject(),
    settingsSp: SettingsSp = koinInject(),
    migrationManager: LMusicMigrationManager = koinInject(),
    workMappingManager: ARMusicWorkMappingManager = koinInject(),
    fileSystemScanner: FileSystemScanner = koinInject(),
    lMediaSp: LMediaSp = koinInject(),
    httpClient: OkHttpClient = koinInject(),
    lyricSettings: DataSaverMutableState<LyricSettings> = koinInject(named("LyricSettings")),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isMigrationBusy = remember { mutableStateOf(false) }
    val appLanguageOption = settingsSp.appLanguageOption
    val enableUnknownFilter = settingsSp.enableUnknownFilter
    val statusBarLyric = settingsSp.enableStatusLyric
    val lyricTextSize = settingsSp.lyricTextSize
    val volumeControl = settingsSp.volumeControl
    val lyricTypefacePath = settingsSp.lyricTypefacePath
    val enableSystemEq = settingsSp.enableSystemEq
    val enableDynamicTips = settingsSp.enableDynamicTips
    val forceHideStatusBar = settingsSp.forceHideStatusBar
    val rotateMultipleCovers = settingsSp.rotateMultipleCovers
    val keepScreenOnWhenLyricExpanded = settingsSp.keepScreenOnWhenLyricExpanded
    val historyDurationFilter = settingsSp.historyDurationFilter
    val workLabelMode = settingsSp.workLabelMode
    val excludedFolders = lMediaSp.excludePath
    val excludedFoldersExpanded = remember { mutableStateOf(false) }
    val isCheckingUpdate = remember { mutableStateOf(false) }

    fun checkForUpdate() {
        if (isCheckingUpdate.value) return

        scope.launch {
            isCheckingUpdate.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchLatestGithubVersion(httpClient) }
            }

            result
                .onSuccess { info ->
                    if (compareVersionName(info.version, BuildConfig.VERSION_NAME) > 0) {
                        ToastUtils.showLong(
                            context.getString(R.string.settings_update_found, info.version)
                        )
                    } else {
                        ToastUtils.showShort(
                            context.getString(
                                R.string.settings_update_current,
                                BuildConfig.VERSION_NAME
                            )
                        )
                    }
                }
                .onFailure { error ->
                    ToastUtils.showLong(
                        error.message ?: context.getString(R.string.settings_update_failed)
                    )
                }

            isCheckingUpdate.value = false
        }
    }

    val launcherForAudioFx = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
    }
    val launcherForBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (isMigrationBusy.value) return@rememberLauncherForActivityResult
        scope.launch {
            isMigrationBusy.value = true
            val result = migrationManager.exportToUri(uri)
            ToastUtils.showLong(result.message)
            isMigrationBusy.value = false
        }
    }
    val launcherForRestore = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (isMigrationBusy.value) return@rememberLauncherForActivityResult
        scope.launch {
            isMigrationBusy.value = true
            val result = migrationManager.importFromUri(uri)
            ToastUtils.showLong(result.message)
            if (result.isSuccess) {
                fileSystemScanner.updateAsync()
            }
            isMigrationBusy.value = false
        }
    }
    val launcherForWorkMappingExport = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/tab-separated-values")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (isMigrationBusy.value) return@rememberLauncherForActivityResult
        scope.launch {
            isMigrationBusy.value = true
            val result = workMappingManager.exportToUri(uri)
            ToastUtils.showLong(result.message)
            isMigrationBusy.value = false
        }
    }
    val launcherForWorkMappingImport = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (isMigrationBusy.value) return@rememberLauncherForActivityResult
        scope.launch {
            isMigrationBusy.value = true
            val result = workMappingManager.importFromUri(uri)
            ToastUtils.showLong(result.message)
            fileSystemScanner.updateAsync()
            isMigrationBusy.value = false
        }
    }
    val launcherForExcludedFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        excludedFolders.add(uri.toString())
        fileSystemScanner.updateAsync()
        ToastUtils.showLong(
            context.getString(
                R.string.settings_excluded_folder_added,
                PathExclusionMatcher.displayPath(uri.toString())
            )
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(top = rememberFixedStatusBarHeightDp())
    ) {
        item {
            NavigatorHeader(
                title = stringResource(id = R.string.screen_title_settings),
                subTitle = stringResource(id = R.string.destination_subtitle_settings)
            )
        }

        item {
            SettingCategory(
                iconRes = R.drawable.ic_settings_4_line,
                titleRes = R.string.preference_player_settings
            ) {
                SettingSwitcher(
                    title = stringResource(id = R.string.settings_player_rotate_multiple_covers),
                    subTitle = stringResource(
                        id = R.string.settings_player_rotate_multiple_covers_subtitle
                    ),
                    state = rotateMultipleCovers,
                )
                SettingProgressSeekBar(
                    value = { volumeControl.value.toFloat() },
                    onValueUpdate = { volumeControl.value = it.roundToInt() },
                    title = stringResource(id = R.string.settings_volume_control),
                    valueRange = 0..100
                )
                SettingProgressSeekBar(
                    value = { historyDurationFilter.value.toFloat() },
                    onValueUpdate = { historyDurationFilter.value = it.roundToInt() },
                    title = stringResource(id = R.string.settings_history_duration_filter),
                    subTitle = historyDurationFilter.value
                        .takeIf { it > 0 }
                        ?.let {
                            stringResource(
                                id = R.string.settings_history_duration_filter_value,
                                it
                            )
                        }
                        ?: stringResource(id = R.string.settings_history_duration_filter_all),
                    valueRange = 0..60
                )
                SettingSwitcher(
                    state = enableSystemEq,
                    title = stringResource(id = R.string.settings_enable_system_eq),
                    subTitle = stringResource(id = R.string.settings_enable_system_eq_subtitle)
                )
                val enableSystemEqValue by enableSystemEq
                AnimatedVisibility(visible = enableSystemEqValue) {
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconTextButton(
                            text = stringResource(id = R.string.settings_system_eq),
                            iconPainter = painterResource(id = R.drawable.equalizer_line),
                            showIcon = { true },
                            color = Color(0xFF006E7C),
                            onClick = {
                                eqHelper.startSystemEqActivity {
                                    launcherForAudioFx.launch(it)
                                }
                            }
                        )
                    }
                }
            }
        }

        item {
            SettingCategory(
                iconRes = com.lalilu.component.R.drawable.ic_lrc_fill,
                titleRes = R.string.preference_lyric_settings
            ) {
                SettingSwitcher(
                    titleRes = R.string.preference_lyric_settings_status_bar_lyric,
                    state = statusBarLyric
                )
                SettingSwitcher(
                    title = stringResource(id = R.string.settings_keep_screen_on_when_lyric_expanded),
                    subTitle = stringResource(
                        id = R.string.settings_keep_screen_on_when_lyric_expanded_subtitle
                    ),
                    state = keepScreenOnWhenLyricExpanded,
                )
                SettingFontLibrary(
                    currentPath = { lyricTypefacePath.value },
                    onPathSelected = { path ->
                        lyricTypefacePath.value = path
                        lyricSettings.value = lyricSettings.value.copy(
                            mainFont = path.takeIf { it.isNotBlank() }
                                ?.let { SerializableFont.LoadedFont(it) }
                        )
                        lyricSettings.saveData()
                    }
                )
//                SettingProgressSeekBar(
//                    state = lyricTextSize,
//                    title = "歌词文字大小",
//                    valueRange = 14..36
//                )
            }
        }

        item {
            SettingCategory(
                icon = painterResource(id = R.drawable.ic_download_cloud_2_line),
                title = stringResource(id = R.string.settings_armusic_sync)
            ) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    mainAxisSpacing = 10.dp,
                    crossAxisSpacing = 8.dp
                ) {
                    IconTextButton(
                        text = stringResource(id = R.string.settings_add_new_song),
                        iconPainter = painterResource(id = R.drawable.ic_scan_line),
                        showIcon = { true },
                        color = Color(0xFF006E7C),
                        onClick = {
                            KRouter.route<Screen>("/pages/folders")?.let {
                                AppRouter.intent(NavIntent.Push(it))
                            }
                        }
                    )
                    IconTextButton(
                        text = stringResource(id = R.string.settings_lan_sync),
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF006E7C),
                        onClick = {
                            AppRouter.intent(NavIntent.Push(ARMusicLanSyncScreen))
                        }
                    )
                    IconTextButton(
                        text = if (isMigrationBusy.value) {
                            stringResource(id = R.string.settings_migrating)
                        } else {
                            stringResource(id = R.string.settings_migrate_from_legacy_app)
                        },
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF6D5B00),
                        onClick = {
                            if (isMigrationBusy.value) return@IconTextButton
                            scope.launch {
                                isMigrationBusy.value = true
                                val result = migrationManager.migrateFromInstalledLegacyApp()
                                ToastUtils.showLong(result.message)
                                fileSystemScanner.updateAsync()
                                isMigrationBusy.value = false
                            }
                        }
                    )
                    IconTextButton(
                        text = stringResource(id = R.string.settings_import_backup),
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF006E7C),
                        onClick = {
                            launcherForRestore.launch(arrayOf("application/json", "text/*", "*/*"))
                        }
                    )
                    IconTextButton(
                        text = stringResource(id = R.string.settings_export_work_mapping),
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF3EA22C),
                        onClick = {
                            launcherForWorkMappingExport.launch("armusic_work_mapping.tsv")
                        }
                    )
                    IconTextButton(
                        text = stringResource(id = R.string.settings_import_work_mapping),
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF6D5B00),
                        onClick = {
                            launcherForWorkMappingImport.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                        }
                    )
                }
            }
        }

        item {
            SettingCategory(
                iconRes = R.drawable.ic_scan_line,
                titleRes = R.string.preference_media_source_settings
            ) {
//                SettingProgressSeekBar(
//                    state = durationFilter,
//                    title = "筛除小于时长的文件",
//                    valueRange = 0..60
//                )
                SettingSwitcher(
                    state = enableUnknownFilter,
                    titleRes = R.string.preference_media_source_settings_unknown_filter,
                    subTitleRes = R.string.preference_media_source_tips
                )
                FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    mainAxisSpacing = 10.dp
                ) {
                    IconTextButton(
                        text = stringResource(id = R.string.settings_add_excluded_folder),
                        color = Color(0xFF006E7C),
                        onClick = {
                            launcherForExcludedFolder.launch(null)
                        }
                    )
                    if (excludedFolders.value.isNotEmpty()) {
                        IconTextButton(
                            text = if (excludedFoldersExpanded.value) {
                                stringResource(id = R.string.settings_hide_excluded_folders)
                            } else {
                                stringResource(
                                    id = R.string.settings_show_excluded_folders,
                                    excludedFolders.value.size
                                )
                            },
                            color = Color(0xFF6E4AC3),
                            onClick = {
                                excludedFoldersExpanded.value = !excludedFoldersExpanded.value
                            }
                        )
                        IconTextButton(
                            text = stringResource(id = R.string.settings_clear_excluded_folders),
                            color = Color(0xFFC13D1A),
                            onClick = {
                                excludedFolders.value = emptyList()
                                fileSystemScanner.updateAsync()
                                ToastUtils.showShort(
                                    context.getString(R.string.settings_excluded_folder_cleared)
                                )
                            }
                        )
                    }
                }
                AnimatedVisibility(visible = excludedFoldersExpanded.value) {
                    androidx.compose.foundation.layout.Column {
                        excludedFolders.value.forEach { path ->
                            SettingSwitcher(
                                enableContentClickable = false,
                                contentStart = {
                                    androidx.compose.material.Text(
                                        text = PathExclusionMatcher.displayPath(path)
                                    )
                                    androidx.compose.material.Text(
                                        text = stringResource(
                                            id = R.string.settings_excluded_folder_description
                                        ),
                                        color = Color.Gray
                                    )
                                },
                                contentEnd = {
                                    IconTextButton(
                                        text = stringResource(id = R.string.settings_remove),
                                        color = Color(0xFFC13D1A),
                                        onClick = {
                                            excludedFolders.remove(path)
                                            fileSystemScanner.updateAsync()
                                            ToastUtils.showShort(
                                                context.getString(
                                                    R.string.settings_excluded_folder_removed
                                                )
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            AboutUpdateCategory(
                icon = painterResource(id = R.drawable.ic_download_cloud_2_line),
            ) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    mainAxisSpacing = 10.dp,
                    crossAxisSpacing = 8.dp
                ) {
                    IconTextButton(
                        text = if (isCheckingUpdate.value) {
                            stringResource(id = R.string.settings_checking_update)
                        } else {
                            stringResource(id = R.string.settings_check_update)
                        },
                        iconPainter = painterResource(id = R.drawable.ic_loader_line),
                        showIcon = { true },
                        color = Color(0xFF006E7C),
                        onClick = { checkForUpdate() }
                    )
                    IconTextButton(
                        text = stringResource(id = R.string.settings_github_project),
                        iconPainter = painterResource(id = R.drawable.ic_arrow_right_s_line),
                        showIcon = { true },
                        color = Color(0xFF6E4AC3),
                        onClick = { openUrl(context, ARMUSIC_GITHUB_URL) }
                    )
                }
            }
        }

        item {
            SettingCategory(
                icon = painterResource(id = R.drawable.ic_loader_line),
                title = stringResource(id = R.string.settings_other)
            ) {
                SettingSwitcher(
                    title = stringResource(id = R.string.settings_force_hide_status_bar),
                    subTitle = stringResource(id = R.string.settings_force_hide_status_bar_subtitle),
                    state = forceHideStatusBar,
                )
                SettingStateSeekBar(
                    state = { appLanguageOption.value },
                    onStateUpdate = { option ->
                        val value = option.coerceIn(
                            ARMusicLanguage.OPTION_CHINESE,
                            ARMusicLanguage.OPTION_ENGLISH
                        )
                        if (appLanguageOption.value != value) {
                            appLanguageOption.value = value
                            context.getActivity()?.recreate()
                        }
                    },
                    selection = stringArrayResource(id = R.array.app_language_options).toList(),
                    title = stringResource(id = R.string.preference_app_language),
                    subTitle = stringResource(id = R.string.preference_app_language_tips)
                )
                SettingStateSeekBar(
                    state = workLabelMode,
                    selection = rememberWorkLabelOptions(),
                    title = stringResource(id = R.string.settings_work_label_mode),
                    subTitle = stringResource(id = R.string.settings_work_label_mode_subtitle)
                )
                SettingSwitcher(
                    state = enableDynamicTips,
                    titleRes = R.string.preference_media_source_settings_enable_dynamic_tips,
                    subTitleRes = R.string.preference_dynamic_tips
                )
                FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    mainAxisSpacing = 10.dp
                ) {
                    IconTextButton(
                        text = stringResource(id = R.string.settings_share_log),
                        color = Color(0xFF0040FF),
                        onClick = {
                            scope.launch {
                                context.getActivity()?.apply {
                                    CrashHelper.shareLog(this)
                                } ?: run {
                                    ToastUtils.showShort(
                                        context.getString(R.string.settings_share_log_failed)
                                    )
                                }
                            }
                        }
                    )

                    IconTextButton(
                        text = stringResource(id = R.string.settings_media_store_rescan),
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_scan_started),
                                Toast.LENGTH_SHORT
                            ).show()
                            // TODO 存在扫描不到的情况，改进方向为先遍历出fileList然后交由其进行scanFile
                            MediaScannerConnection.scanFile(
                                context, arrayOf("/storage/emulated/0/"), null
                            ) { path, uri ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_scan_finished),
                                    Toast.LENGTH_SHORT
                                ).show()
                                LogUtils.i("MediaScannerConnection", "path: $path, uri: $uri")
                            }
                        }
                    )

                    IconTextButton(
                        text = stringResource(id = R.string.settings_file_system_rescan),
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_scan_started),
                                Toast.LENGTH_SHORT
                            ).show()
                            fileSystemScanner.updateAsync()
                        }
                    )
                    IconTextButton(
                        text = stringResource(id = R.string.settings_backup_data),
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            launcherForBackup.launch("armusic_backup.json")
                        }
                    )
                    IconTextButton(
                        text = stringResource(id = R.string.settings_restore_data),
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            launcherForRestore.launch(arrayOf("application/json", "text/*", "*/*"))
                        }
                    )

                    if (BuildConfig.DEBUG) {
                        IconTextButton(
                            text = stringResource(id = R.string.settings_test_crash),
                            color = Color(0xFFF12121),
                            onClick = {
                                throw RuntimeException("Exception test")
                            }
                        )
                    }
                }
            }
        }

        smartBarPadding()
    }
}
