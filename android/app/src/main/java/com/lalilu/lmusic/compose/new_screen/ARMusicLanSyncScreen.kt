package com.lalilu.lmusic.compose.new_screen

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.lalilu.R
import com.lalilu.component.base.NavigatorHeader
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.extension.dayNightTextColor
import com.lalilu.lmusic.sync.ARMusicAndroidManifestBuilder
import com.lalilu.lmusic.sync.ARMusicLanSyncClient
import com.lalilu.lmusic.sync.ARMusicSyncHealth
import com.lalilu.lmusic.sync.ARMusicSyncManifest
import com.lalilu.lmusic.sync.ARMusicSyncPlan
import com.lalilu.lmusic.sync.ARMusicSyncPlanner
import com.lalilu.lmusic.sync.ARMusicSyncTrack
import com.lalilu.lmusic.sync.ARMusicTrackDownloader
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
) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("先在桌面端启动同步服务，再填这里的地址。") }
    var health by remember { mutableStateOf<ARMusicSyncHealth?>(null) }
    var localManifest by remember { mutableStateOf<ARMusicSyncManifest?>(null) }
    var remoteManifest by remember { mutableStateOf<ARMusicSyncManifest?>(null) }
    var syncPlan by remember { mutableStateOf<ARMusicSyncPlan?>(null) }

    fun refreshPlan() {
        if (address.isBlank() || isBusy) return
        scope.launch {
            isBusy = true
            message = "正在连接桌面端"
            runCatching {
                val nextHealth = syncClient.fetchHealth(address).getOrThrow()
                message = "正在读取歌曲清单"
                val remote = syncClient.fetchManifest(address).getOrThrow()
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

        scope.launch {
            isBusy = true
            runCatching {
                plan.download.forEachIndexed { index, track ->
                    message = "正在下载 ${index + 1}/${plan.download.size}：${track.title}"
                    downloader.downloadToMusicDirectory(address, track)
                }
                message = "下载完成，系统音乐库会自动刷新"

                val remote = remoteManifest ?: syncClient.fetchManifest(address).getOrThrow()
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
                        onValueChange = { address = it },
                        label = { Text("桌面端地址") },
                        placeholder = { Text("例如 192.168.1.20:38689") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(
                            text = if (isBusy) "处理中" else "连接并对比",
                            enabled = !isBusy && address.isNotBlank(),
                            color = Color(0xFF006E7C),
                            onClick = ::refreshPlan,
                        )
                        ActionButton(
                            text = "下载缺失歌曲",
                            enabled = !isBusy && (syncPlan?.download?.isNotEmpty() == true),
                            color = Color(0xFF3EA22C),
                            onClick = ::downloadMissingTracks,
                        )
                    }
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
            item {
                Text(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    text = "上传接口还没接，先只显示数量，下一步再做。",
                    color = dayNightTextColor(0.5f),
                    fontSize = 12.sp,
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
