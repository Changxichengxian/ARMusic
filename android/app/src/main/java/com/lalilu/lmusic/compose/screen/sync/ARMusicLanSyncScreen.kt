package com.lalilu.lmusic.compose.screen.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.lalilu.R
import com.google.accompanist.flowlayout.FlowRow
import com.lalilu.component.base.NavigatorHeader
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.extension.dayNightTextColor
import com.lalilu.lmusic.sync.ARMusicAndroidManifestBuilder
import com.lalilu.lmusic.sync.ARMusicLanSyncClient
import com.lalilu.lmusic.sync.ARMusicHistorySyncCoordinator
import com.lalilu.lmusic.sync.ARMusicHistorySyncMode
import com.lalilu.lmusic.sync.ARMusicSyncHealth
import com.lalilu.lmusic.sync.ARMusicConflictResolution
import com.lalilu.lmusic.sync.ARMusicSyncConflict
import com.lalilu.lmusic.sync.ARMusicSyncManifest
import com.lalilu.lmusic.sync.ARMusicSyncPlan
import com.lalilu.lmusic.sync.ARMusicSyncPlanner
import com.lalilu.lmusic.sync.ARMusicSyncTrack
import com.lalilu.lmusic.sync.ARMusicTrackDownloader
import com.lalilu.lmusic.sync.ARMusicTrackUploader
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

object ARMusicLanSyncScreen : Screen, ScreenInfoFactory {
    private fun readResolve(): Any = ARMusicLanSyncScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { "局域网同步" },
        )
    }

    @Composable
    override fun Content() {
        ARMusicLanSyncContent()
    }
}

@Composable
private fun ARMusicLanSyncContent(
    syncClient: ARMusicLanSyncClient = koinInject(),
    manifestBuilder: ARMusicAndroidManifestBuilder = koinInject(),
    downloader: ARMusicTrackDownloader = koinInject(),
    uploader: ARMusicTrackUploader = koinInject(),
    historyCoordinator: ARMusicHistorySyncCoordinator = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("请粘贴电脑端显示的完整同步地址（含临时 token），再手动连接。") }
    var health by remember { mutableStateOf<ARMusicSyncHealth?>(null) }
    var localManifest by remember { mutableStateOf<ARMusicSyncManifest?>(null) }
    var remoteManifest by remember { mutableStateOf<ARMusicSyncManifest?>(null) }
    var syncPlan by remember { mutableStateOf<ARMusicSyncPlan?>(null) }
    var conflictResolutions by remember {
        mutableStateOf<Map<String, ARMusicConflictResolution>>(emptyMap())
    }

    LaunchedEffect(syncPlan) {
        conflictResolutions = syncPlan?.conflicts.orEmpty().associate { conflict ->
                conflict.local.syncId to (
                conflictResolutions[conflict.local.syncId]
                    ?: ARMusicConflictResolution.SKIP
                )
        }
    }

    fun refreshPlan(targetAddress: String = address) {
        if (targetAddress.isBlank() || isBusy) return
        scope.launch {
            isBusy = true
            address = targetAddress
            message = "正在连接桌面端"
            runCatching {
                val nextHealth = syncClient.fetchHealth(targetAddress).getOrThrow()
                message = "正在读取歌曲清单"
                val remote = syncClient.fetchManifest(targetAddress).getOrThrow()
                message = "正在读取 Android 本地音乐库"
                val local = manifestBuilder.buildManifest()
                val plan = ARMusicSyncPlanner.buildPlan(
                    localTracks = local.tracks,
                    remoteTracks = remote.tracks,
                )

                health = nextHealth
                localManifest = local
                remoteManifest = remote
                syncPlan = plan
                message = "已对比完成"
            }.getOrElse { error ->
                message = error.message ?: "连接失败"
            }
            isBusy = false
        }
    }

    fun downloadMissingTracks() {
        val plan = syncPlan ?: return
        if (address.isBlank() || plan.download.isEmpty() || isBusy) return
        val targetAddress = address

        scope.launch {
            isBusy = true
            runCatching {
                plan.download.forEachIndexed { index, track ->
                    message = "正在下载 ${index + 1}/${plan.download.size}：${track.title}"
                    downloader.downloadToMusicDirectory(targetAddress, track)
                }
                message = "下载完成，系统音乐库会自动刷新"

                val remote = remoteManifest ?: syncClient.fetchManifest(targetAddress).getOrThrow()
                val local = manifestBuilder.buildManifest()
                localManifest = local
                syncPlan = ARMusicSyncPlanner.buildPlan(
                    localTracks = local.tracks,
                    remoteTracks = remote.tracks,
                )
            }.getOrElse { error ->
                message = error.message ?: "下载失败"
            }
            isBusy = false
        }
    }

    fun uploadMissingTracks() {
        val plan = syncPlan ?: return
        if (address.isBlank() || plan.upload.isEmpty() || isBusy) return
        val targetAddress = address

        scope.launch {
            isBusy = true
            runCatching {
                plan.upload.forEachIndexed { index, track ->
                    message = "正在上传 ${index + 1}/${plan.upload.size}：${track.title}"
                    uploader.uploadToDesktop(targetAddress, track)
                }
                message = "上传完成，桌面端会重新扫描音乐库"

                val remote = syncClient.fetchManifest(targetAddress).getOrThrow()
                val local = manifestBuilder.buildManifest()
                remoteManifest = remote
                localManifest = local
                syncPlan = ARMusicSyncPlanner.buildPlan(
                    localTracks = local.tracks,
                    remoteTracks = remote.tracks,
                )
            }.getOrElse { error ->
                message = error.message ?: "上传失败"
            }
            isBusy = false
        }
    }

    fun syncHistory() {
        if (address.isBlank() || isBusy) return
        val targetAddress = address
        scope.launch {
            isBusy = true
            message = "正在安全合并听歌时间"
            runCatching {
                historyCoordinator.sync(
                    baseUrl = targetAddress,
                    mode = ARMusicHistorySyncMode.KEEP_ON_BOTH,
                )
            }.onSuccess { outcome ->
                message = "听歌时间已合并：手机新增 ${outcome.importedToPhone} 条，重复记录没有再次累计"
            }.onFailure { error ->
                message = error.message ?: "听歌时间同步失败，手机记录保持不变"
            }
            isBusy = false
        }
    }

    fun syncBothDirections() {
        val plan = syncPlan ?: return
        if (address.isBlank() || isBusy) return
        val targetAddress = address
        val targetResolutions = conflictResolutions.toMap()
        scope.launch {
            isBusy = true
            runCatching {
                plan.upload.forEachIndexed { index, track ->
                    message = "手机 → 电脑 ${index + 1}/${plan.upload.size}：${track.title}"
                    uploader.uploadToDesktop(targetAddress, track)
                }
                plan.download.forEachIndexed { index, track ->
                    message = "电脑 → 手机 ${index + 1}/${plan.download.size}：${track.title}"
                    downloader.downloadToMusicDirectory(targetAddress, track)
                }
                var resolvedConflicts = 0
                var usbOnlyConflicts = 0
                plan.conflicts.forEachIndexed { index, conflict ->
                    when (targetResolutions[conflict.local.syncId]
                        ?: ARMusicConflictResolution.SKIP
                    ) {
                        ARMusicConflictResolution.ANDROID_TO_DESKTOP -> {
                            message = "更新冲突 ${index + 1}/${plan.conflicts.size}：手机版本 → 电脑"
                            uploader.replaceOnDesktop(
                                targetAddress,
                                conflict.local,
                                conflict.remote.revisionHash
                                    ?: error("电脑端没有提供可校验的文件版本，已跳过覆盖"),
                            )
                            resolvedConflicts += 1
                        }
                        ARMusicConflictResolution.DESKTOP_TO_ANDROID -> {
                            // Scoped storage cannot guarantee rollback after a process crash.
                            // The desktop USB workflow performs a verified same-directory rename.
                            usbOnlyConflicts += 1
                        }
                        ARMusicConflictResolution.SKIP -> Unit
                    }
                }
                message = "正在合并听歌时间"
                val history = historyCoordinator.sync(
                    baseUrl = targetAddress,
                    mode = ARMusicHistorySyncMode.KEEP_ON_BOTH,
                )
                val remote = syncClient.fetchManifest(targetAddress).getOrThrow()
                val local = manifestBuilder.buildManifest()
                remoteManifest = remote
                localManifest = local
                syncPlan = ARMusicSyncPlanner.buildPlan(local.tracks, remote.tracks)
                Triple(history, resolvedConflicts, usbOnlyConflicts)
            }.onSuccess { (history, resolvedConflicts, usbOnlyConflicts) ->
                val skippedConflicts = plan.conflicts.count { conflict ->
                    (targetResolutions[conflict.local.syncId]
                        ?: ARMusicConflictResolution.SKIP) == ARMusicConflictResolution.SKIP
                }
                val conflictNote = buildString {
                    if (resolvedConflicts > 0) append("；已处理 $resolvedConflicts 个冲突")
                    if (skippedConflicts > 0) append("；$skippedConflicts 个冲突已跳过")
                    if (usbOnlyConflicts > 0) append("；$usbOnlyConflicts 个需在电脑用 USB 安全替换")
                }
                message = "双向同步完成：手机新增 ${plan.download.size} 首，电脑新增 ${plan.upload.size} 首，听歌记录新增 ${history.importedToPhone} 条$conflictNote"
            }.onFailure { error ->
                message = error.message ?: "双向同步失败；冲突和手机听歌记录均未静默删除"
            }
            isBusy = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item {
            NavigatorHeader(
                title = "局域网同步",
                subTitle = message,
            )
        }

        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = dayNightTextColor(0.06f),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = address,
                        enabled = !isBusy,
                        onValueChange = { address = it },
                        label = { Text("桌面端地址") },
                        placeholder = { Text("粘贴电脑端完整地址，必须包含 token") },
                        singleLine = true,
                    )
                    FlowRow(
                        mainAxisSpacing = 10.dp,
                        crossAxisSpacing = 8.dp,
                    ) {
                        ActionButton(
                            text = if (isBusy) "处理中" else "连接并对比",
                            enabled = !isBusy && address.isNotBlank(),
                            color = Color(0xFF006E7C),
                            onClick = { refreshPlan() },
                        )
                        ActionButton(
                            text = "下载缺失歌曲",
                            enabled = !isBusy && (syncPlan?.download?.isNotEmpty() == true),
                            color = Color(0xFF3EA22C),
                            onClick = ::downloadMissingTracks,
                        )
                        ActionButton(
                            text = "上传到桌面端",
                            enabled = !isBusy && (syncPlan?.upload?.isNotEmpty() == true),
                            color = Color(0xFFFF8B3F),
                            onClick = ::uploadMissingTracks,
                        )
                        ActionButton(
                            text = "一键双向同步",
                            enabled = !isBusy && syncPlan != null,
                            color = Color(0xFF006E7C),
                            onClick = { syncBothDirections() },
                        )
                    }
                    Text(
                        text = "听歌时间保存位置",
                        color = dayNightTextColor(0.65f),
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            text = "手机和电脑都保留（局域网）",
                            enabled = false,
                            color = Color(0xFF3EA22C),
                            onClick = {},
                        )
                        ActionButton(
                            text = "只在电脑保留（仅电脑端 USB）",
                            enabled = false,
                            color = Color(0xFFFF8B3F),
                            onClick = {},
                        )
                    }
                    Text(
                        text = "局域网只做两端保留并去重合并。要让听歌时间只留在电脑，请连接 USB 后在电脑端执行。",
                        color = dayNightTextColor(0.5f),
                        fontSize = 12.sp,
                    )
                    ActionButton(
                        text = "合并听歌时间",
                        enabled = !isBusy && address.isNotBlank(),
                        color = Color(0xFF6B5BD2),
                        onClick = ::syncHistory,
                    )
                }
            }
        }

        item {
            SyncSummaryCard(
                health = health,
                localManifest = localManifest,
                remoteManifest = remoteManifest,
                plan = syncPlan,
            )
        }

        syncPlan?.download?.takeIf { it.isNotEmpty() }?.let { tracks ->
            item { SectionTitle("Android 缺少的歌曲", tracks.size) }
            items(tracks.take(20), key = { it.syncId }) { track ->
                TrackRow(track)
            }
            if (tracks.size > 20) {
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        text = "还有 ${tracks.size - 20} 首，下载时会一起处理。",
                        color = dayNightTextColor(0.5f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        syncPlan?.upload?.takeIf { it.isNotEmpty() }?.let { tracks ->
            item { SectionTitle("桌面端缺少的歌曲", tracks.size) }
            items(tracks.take(20), key = { it.syncId }) { track ->
                TrackRow(track)
            }
            if (tracks.size > 20) {
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        text = "还有 ${tracks.size - 20} 首，上传时会一起处理。",
                        color = dayNightTextColor(0.5f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        syncPlan?.conflicts?.takeIf { it.isNotEmpty() }?.let { conflicts ->
            item { SectionTitle("同一首歌的版本冲突", conflicts.size) }
            items(conflicts, key = { it.local.syncId }) { conflict ->
                ConflictRow(
                    conflict = conflict,
                    resolution = conflictResolutions[conflict.local.syncId]
                        ?: ARMusicConflictResolution.SKIP,
                    enabled = !isBusy,
                    onResolution = { resolution ->
                        conflictResolutions = conflictResolutions +
                            (conflict.local.syncId to resolution)
                    },
                )
            }
        }

        smartBarPadding()
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    TextButton(
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            backgroundColor = color.copy(alpha = 0.15f),
            disabledContentColor = MaterialTheme.colors.onBackground.copy(alpha = 0.35f),
        )
    ) {
        Text(text = text)
    }
}

@Composable
private fun SyncSummaryCard(
    health: ARMusicSyncHealth?,
    localManifest: ARMusicSyncManifest?,
    remoteManifest: ARMusicSyncManifest?,
    plan: ARMusicSyncPlan?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = dayNightTextColor(0.06f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = health?.name ?: "还没有连接桌面端",
                color = dayNightTextColor(),
                style = MaterialTheme.typography.subtitle1,
            )
            SummaryLine("Android 本地", localManifest?.tracks?.size?.toString() ?: "--")
            SummaryLine("桌面端", remoteManifest?.tracks?.size?.toString() ?: "--")
            SummaryLine(
                "Android 已排除（非 MP3/少于 15 秒）",
                localManifest?.let {
                    (it.ignoredTracks.size + (plan?.ignoredLocal?.size ?: 0)).toString()
                } ?: "--",
            )
            SummaryLine(
                "桌面已排除（非 MP3/少于 15 秒）",
                remoteManifest?.let {
                    (it.ignoredTracks.size + (plan?.ignoredRemote?.size ?: 0)).toString()
                } ?: "--",
            )
            SummaryLine("可下载到 Android", plan?.download?.size?.toString() ?: "--")
            SummaryLine("待上传到桌面端", plan?.upload?.size?.toString() ?: "--")
            SummaryLine("冲突", plan?.conflicts?.size?.toString() ?: "--")
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = dayNightTextColor(0.65f), fontSize = 13.sp)
        Text(text = value, color = dayNightTextColor(), fontSize = 13.sp)
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        text = "$title（$count）",
        color = dayNightTextColor(0.7f),
        style = MaterialTheme.typography.subtitle2,
    )
}

@Composable
private fun TrackRow(track: ARMusicSyncTrack) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = track.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = dayNightTextColor(),
            style = MaterialTheme.typography.body1,
        )
        Text(
            text = "${track.artist} · ${track.relativePath}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = dayNightTextColor(0.5f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ConflictRow(
    conflict: ARMusicSyncConflict,
    resolution: ARMusicConflictResolution,
    enabled: Boolean,
    onResolution: (ARMusicConflictResolution) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = dayNightTextColor(0.05f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(conflict.local.title, color = dayNightTextColor())
            Text(
                text = conflict.recommendedResolution?.let { "修改时间可供参考，但默认仍会跳过" }
                    ?: "修改时间相同或无法判断，默认跳过",
                color = dayNightTextColor(0.5f),
                fontSize = 12.sp,
            )
            FlowRow(mainAxisSpacing = 7.dp, crossAxisSpacing = 7.dp) {
                ConflictChoice(
                    text = "以电脑为准（请在电脑用 USB）",
                    selected = resolution == ARMusicConflictResolution.DESKTOP_TO_ANDROID,
                    enabled = false,
                    onClick = { onResolution(ARMusicConflictResolution.DESKTOP_TO_ANDROID) },
                )
                ConflictChoice(
                    text = "以手机为准",
                    selected = resolution == ARMusicConflictResolution.ANDROID_TO_DESKTOP,
                    enabled = enabled,
                    onClick = { onResolution(ARMusicConflictResolution.ANDROID_TO_DESKTOP) },
                )
                ConflictChoice(
                    text = "跳过",
                    selected = resolution == ARMusicConflictResolution.SKIP,
                    enabled = enabled,
                    onClick = { onResolution(ARMusicConflictResolution.SKIP) },
                )
            }
        }
    }
}

@Composable
private fun ConflictChoice(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) Color.White else Color(0xFF006E7C),
            backgroundColor = if (selected) Color(0xFF006E7C) else Color(0xFF006E7C).copy(alpha = 0.12f),
            disabledContentColor = dayNightTextColor(0.35f),
        ),
    ) { Text(text) }
}
