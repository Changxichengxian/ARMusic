package com.lalilu.lmusic.compose.new_screen

import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.lalilu.component.navigation.AppRouter
import com.lalilu.component.navigation.NavIntent
import com.lalilu.crash.CrashHelper
import com.lalilu.lmedia.repository.LMediaSp
import com.lalilu.lmedia.scanner.FileSystemScanner
import com.lalilu.lmedia.scanner.PathExclusionMatcher
import com.lalilu.lmusic.compose.component.playing.SettingFontLibrary
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
import kotlinx.coroutines.launch
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
    lyricSettings: DataSaverMutableState<LyricSettings> = koinInject(named("LyricSettings")),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isMigrationBusy = remember { mutableStateOf(false) }
    val darkModeOption = settingsSp.darkModeOption
    val ignoreAudioFocus = settingsSp.ignoreAudioFocus
    val enableUnknownFilter = settingsSp.enableUnknownFilter
    val statusBarLyric = settingsSp.enableStatusLyric
    val lyricTextSize = settingsSp.lyricTextSize
    val volumeControl = settingsSp.volumeControl
    val lyricTypefacePath = settingsSp.lyricTypefacePath
    val enableSystemEq = settingsSp.enableSystemEq
    val enableDynamicTips = settingsSp.enableDynamicTips
    val forceHideStatusBar = settingsSp.forceHideStatusBar
    val keepScreenOnWhenLyricExpanded = settingsSp.keepScreenOnWhenLyricExpanded
    val historyDurationFilter = settingsSp.historyDurationFilter
    val workLabelMode = settingsSp.workLabelMode
    val excludedFolders = lMediaSp.excludePath
    val excludedFoldersExpanded = remember { mutableStateOf(false) }

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
        ToastUtils.showLong("已屏蔽：${PathExclusionMatcher.displayPath(uri.toString())}")
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
                    titleRes = R.string.preference_player_settings_ignore_audio_focus,
                    state = ignoreAudioFocus
                )
                SettingProgressSeekBar(
                    value = { volumeControl.value.toFloat() },
                    onValueUpdate = { volumeControl.value = it.roundToInt() },
                    title = "独立音量控制",
                    valueRange = 0..100
                )
                SettingProgressSeekBar(
                    value = { historyDurationFilter.value.toFloat() },
                    onValueUpdate = { historyDurationFilter.value = it.roundToInt() },
                    title = "播放记录过滤",
                    subTitle = historyDurationFilter.value
                        .takeIf { it > 0 }
                        ?.let { "播放不足 ${it} 秒时，不计入历史、次数和时长" }
                        ?: "记录所有播放",
                    valueRange = 0..60
                )
                SettingSwitcher(
                    state = enableSystemEq,
                    title = "启用系统均衡器",
                    subTitle = "实验性功能，存在较大机型差异"
                )
                val enableSystemEqValue by enableSystemEq
                AnimatedVisibility(visible = enableSystemEqValue) {
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconTextButton(
                            text = "系统均衡器",
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
                    title = "歌词页展开时屏幕常亮",
                    subTitle = "小心烧屏",
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
                title = "ARMusic 同步"
            ) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    mainAxisSpacing = 10.dp,
                    crossAxisSpacing = 8.dp
                ) {
                    IconTextButton(
                        text = "添加新歌",
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
                        text = "局域网同步",
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF006E7C),
                        onClick = {
                            AppRouter.intent(NavIntent.Push(ARMusicLanSyncScreen))
                        }
                    )
                    IconTextButton(
                        text = if (isMigrationBusy.value) "迁移中" else "从 LMusic 迁移",
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF6D5B00),
                        onClick = {
                            if (isMigrationBusy.value) return@IconTextButton
                            scope.launch {
                                isMigrationBusy.value = true
                                val result = migrationManager.migrateFromInstalledLmusic()
                                ToastUtils.showLong(result.message)
                                fileSystemScanner.updateAsync()
                                isMigrationBusy.value = false
                            }
                        }
                    )
                    IconTextButton(
                        text = "导入备份",
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF006E7C),
                        onClick = {
                            launcherForRestore.launch(arrayOf("application/json", "text/*", "*/*"))
                        }
                    )
                    IconTextButton(
                        text = "导出作品映射",
                        iconPainter = painterResource(id = R.drawable.ic_download_cloud_2_line),
                        showIcon = { true },
                        color = Color(0xFF3EA22C),
                        onClick = {
                            launcherForWorkMappingExport.launch("armusic_work_mapping.tsv")
                        }
                    )
                    IconTextButton(
                        text = "导入作品映射",
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
                        text = "添加屏蔽文件夹",
                        color = Color(0xFF006E7C),
                        onClick = {
                            launcherForExcludedFolder.launch(null)
                        }
                    )
                    if (excludedFolders.value.isNotEmpty()) {
                        IconTextButton(
                            text = if (excludedFoldersExpanded.value) {
                                "隐藏屏蔽文件夹"
                            } else {
                                "展开屏蔽文件夹 (${excludedFolders.value.size})"
                            },
                            color = Color(0xFF6E4AC3),
                            onClick = {
                                excludedFoldersExpanded.value = !excludedFoldersExpanded.value
                            }
                        )
                        IconTextButton(
                            text = "清空屏蔽",
                            color = Color(0xFFC13D1A),
                            onClick = {
                                excludedFolders.value = emptyList()
                                fileSystemScanner.updateAsync()
                                ToastUtils.showShort("已清空屏蔽文件夹")
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
                                        text = "已屏蔽，重新扫描后不会出现在曲库里",
                                        color = Color.Gray
                                    )
                                },
                                contentEnd = {
                                    IconTextButton(
                                        text = "移除",
                                        color = Color(0xFFC13D1A),
                                        onClick = {
                                            excludedFolders.remove(path)
                                            fileSystemScanner.updateAsync()
                                            ToastUtils.showShort("已移除屏蔽文件夹")
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
            SettingCategory(
                icon = painterResource(id = R.drawable.ic_loader_line),
                title = "其他"
            ) {
                SettingSwitcher(
                    title = "全局隐藏状态栏",
                    subTitle = "简化界面显示效果",
                    state = forceHideStatusBar,
                )
                SettingStateSeekBar(
                    state = darkModeOption,
                    selection = stringArrayResource(id = R.array.dark_mode_options).toList(),
                    titleRes = R.string.preference_dark_mode
                )
                SettingStateSeekBar(
                    state = workLabelMode,
                    selection = WorkLabel.options,
                    title = "作品栏名称",
                    subTitle = "只改界面叫法，不改歌曲文件里的字段"
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
                        text = "日志分享",
                        color = Color(0xFF0040FF),
                        onClick = {
                            scope.launch {
                                context.getActivity()?.apply {
                                    CrashHelper.shareLog(this)
                                } ?: run {
                                    ToastUtils.showShort("日志分享失败")
                                }
                            }
                        }
                    )

                    IconTextButton(
                        text = "MediaStore重新扫描",
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            Toast.makeText(context, "扫描开始", Toast.LENGTH_SHORT).show()
                            // TODO 存在扫描不到的情况，改进方向为先遍历出fileList然后交由其进行scanFile
                            MediaScannerConnection.scanFile(
                                context, arrayOf("/storage/emulated/0/"), null
                            ) { path, uri ->
                                Toast.makeText(context, "扫描结束", Toast.LENGTH_SHORT).show()
                                LogUtils.i("MediaScannerConnection", "path: $path, uri: $uri")
                            }
                        }
                    )

                    IconTextButton(
                        text = "FileSystem重新扫描",
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            Toast.makeText(context, "扫描开始", Toast.LENGTH_SHORT).show()
                            fileSystemScanner.updateAsync()
                        }
                    )
                    IconTextButton(
                        text = "备份数据",
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            launcherForBackup.launch("armusic_backup.json")
                        }
                    )
                    IconTextButton(
                        text = "恢复数据",
                        color = Color(0xFFFF8B3F),
                        onClick = {
                            launcherForRestore.launch(arrayOf("application/json", "text/*", "*/*"))
                        }
                    )

                    if (BuildConfig.DEBUG) {
                        IconTextButton(
                            text = "测试异常捕获",
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
