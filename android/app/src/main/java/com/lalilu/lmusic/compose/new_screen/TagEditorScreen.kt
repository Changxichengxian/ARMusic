package com.lalilu.lmusic.compose.new_screen

import android.content.Context
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.R
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.extension.dayNightTextColor
import com.lalilu.component.navigation.AppRouter
import com.lalilu.component.navigation.NavIntent
import com.lalilu.component.work.rememberWorkLabel
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmedia.wrapper.Taglib
import com.lalilu.lmusic.api.tag.OnlineSongTag
import com.lalilu.lmusic.api.tag.OnlineTagSearchService
import com.lalilu.lmusic.tag.SongGroupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject
import com.lalilu.RemixIcon
import com.lalilu.remixicon.System
import com.lalilu.remixicon.system.checkLine
import com.lalilu.remixicon.system.searchLine

private const val ONLINE_RESULT_MODE_TAG = "tag"
private const val ONLINE_RESULT_MODE_LYRIC = "lyric"
private const val ONLINE_SEARCH_PAGE_SIZE = 10
private const val MSG_REQUEST_WRITE_PERMISSION = "\u9700\u8981\u5148\u6388\u6743 AR Music \u4fee\u6539\u8fd9\u4e2a\u97f3\u9891\u6587\u4ef6\u3002"
private const val MSG_WRITE_PERMISSION_DENIED = "\u6ca1\u6709\u83b7\u5f97\u5199\u5165\u6388\u6743\uff0c\u6807\u7b7e\u6ca1\u6709\u4fdd\u5b58\u3002"
private const val MSG_SAVE_FAILED = "\u4fdd\u5b58\u5931\u8d25"

data class TagEditorScreen(
    private val mediaId: String
) : Screen, ScreenInfoFactory {
    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(title = { "" })
    }

    @Composable
    override fun Content() {
        val song = LMedia.getFlow<LSong>(id = mediaId)
            .collectAsState(initial = LMedia.get<LSong>(id = mediaId))

        TagEditorContent(song = song.value)
    }
}

@Composable
private fun TagEditorContent(
    song: LSong?,
    onlineTagSearchService: OnlineTagSearchService = koinInject(),
    songGroupStore: SongGroupStore = koinInject(),
    songWorkStore: SongWorkStore = koinInject(),
    httpClient: OkHttpClient = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var initializedMediaId by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var album by rememberSaveable { mutableStateOf("") }
    var albumArtist by rememberSaveable { mutableStateOf("") }
    var work by rememberSaveable { mutableStateOf("") }
    var composer by rememberSaveable { mutableStateOf("") }
    var lyricist by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var track by rememberSaveable { mutableStateOf("") }
    var disc by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var sameSongGroup by rememberSaveable { mutableStateOf("") }
    var lyric by rememberSaveable { mutableStateOf("") }
    var selectedOnlineId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCoverUrl by rememberSaveable { mutableStateOf("") }
    var selectedLocalCover by remember { mutableStateOf<DownloadedCover?>(null) }
    var selectedLocalCoverUri by remember { mutableStateOf<Uri?>(null) }
    var removeCoverRequested by rememberSaveable { mutableStateOf(false) }
    var pendingCoverExport by remember { mutableStateOf<DownloadedCover?>(null) }
    var onlineResults by remember { mutableStateOf<List<OnlineSongTag>>(emptyList()) }
    var onlineResultMode by rememberSaveable { mutableStateOf(ONLINE_RESULT_MODE_TAG) }
    var onlineSearchExtended by rememberSaveable { mutableStateOf(false) }
    var hasOnlineSearched by rememberSaveable { mutableStateOf(false) }
    var visibleOnlineResultsLimit by rememberSaveable { mutableStateOf(ONLINE_SEARCH_PAGE_SIZE) }
    var message by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var isApplyingOnlineResult by remember { mutableStateOf(false) }
    var retrySaveAfterWritePermission by remember { mutableStateOf(false) }
    val workStoreVersion by songWorkStore.changes.collectAsState()
    val knownWorks = remember(workStoreVersion) {
        LMedia.get<LSong>(blockFilter = false)
            .map { songWorkStore.getWork(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    val workLabel = rememberWorkLabel()

    fun notify(text: String) {
        message = text
        ToastUtils.showShort(text)
    }

    val localCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult

        scope.launch {
            val cover = withContext(Dispatchers.IO) {
                readCoverFromUri(context, uri)
            }
            if (cover == null) {
                notify("没有读到这张图片。")
                return@launch
            }

            selectedLocalCover = cover
            selectedLocalCoverUri = uri
            selectedCoverUrl = ""
            removeCoverRequested = false
            notify("已选择本地封面，保存后生效。")
        }
    }

    val exportCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val cover = pendingCoverExport ?: return@rememberLauncherForActivityResult

        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                writeCoverToUri(context, uri, cover)
            }
            notify(if (saved) "封面图片已保存。" else "封面图片保存失败。")
            pendingCoverExport = null
        }
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            retrySaveAfterWritePermission = true
        } else {
            notify(MSG_WRITE_PERMISSION_DENIED)
        }
    }

    LaunchedEffect(song?.id) {
        val current = song ?: return@LaunchedEffect
        if (initializedMediaId == current.id) return@LaunchedEffect

        initializedMediaId = current.id
        title = current.metadata.title
        artist = current.metadata.artist
        album = current.metadata.album
        albumArtist = current.metadata.albumArtist
        work = songWorkStore.getWork(current)
        composer = current.metadata.composer
        lyricist = current.metadata.lyricist
        comment = current.metadata.comment
        genre = current.metadata.genre
        track = current.metadata.track
        disc = current.metadata.disc
        date = current.metadata.date
        sameSongGroup = songGroupStore.getGroup(current)
        selectedOnlineId = null
        selectedCoverUrl = ""
        selectedLocalCover = null
        selectedLocalCoverUri = null
        removeCoverRequested = false
        onlineResults = emptyList()
        onlineResultMode = ONLINE_RESULT_MODE_TAG
        onlineSearchExtended = false
        hasOnlineSearched = false
        visibleOnlineResultsLimit = ONLINE_SEARCH_PAGE_SIZE
        lyric = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(current.uri, "r")?.use {
                    Taglib.getLyricWithFD(it.detachFd())
                }.orEmpty()
            }.getOrDefault("")
        }
    }

    fun applyOnlineResult(result: OnlineSongTag) {
        if (isApplyingOnlineResult) return

        selectedOnlineId = result.id
        onlineResults = emptyList()
        title = result.title
        artist = result.artist
        composer = result.composer
        lyricist = result.lyricist
        comment = result.comment
        selectedCoverUrl = result.cover
        selectedLocalCover = null
        selectedLocalCoverUri = null
        removeCoverRequested = false

        scope.launch {
            isApplyingOnlineResult = true
            notify("正在获取歌词")
            val onlineLyric = withContext(Dispatchers.IO) {
                runCatching {
                    onlineTagSearchService.lyricFor(result)
                }.getOrDefault("")
            }

            if (onlineLyric.isNotBlank()) {
                lyric = onlineLyric
            }
            notify("已预览")
            isApplyingOnlineResult = false
        }
    }

    fun applyLyricResult(result: OnlineSongTag) {
        if (isApplyingOnlineResult) return

        selectedOnlineId = result.id
        onlineResults = emptyList()

        scope.launch {
            isApplyingOnlineResult = true
            notify("正在获取歌词")
            val onlineLyric = withContext(Dispatchers.IO) {
                runCatching {
                    onlineTagSearchService.lyricFor(result)
                }.getOrDefault("")
            }

            if (onlineLyric.isBlank()) {
                notify("这个候选没有可用歌词。")
            } else {
                lyric = onlineLyric
                notify("已预览歌词")
            }
            isApplyingOnlineResult = false
        }
    }

    fun searchOnline(extended: Boolean = false) {
        val keyword = currentSearchKeyword(title = title, artist = artist, fallback = song?.defaultTagSearchKeyword())
        if (keyword.isBlank() || isSearching) return

        scope.launch {
            isSearching = true
            onlineResultMode = ONLINE_RESULT_MODE_TAG
            if (!extended) hasOnlineSearched = false
            notify(if (extended) "继续搜索歌曲信息" else "正在搜索歌曲信息")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    onlineTagSearchService.search(
                        keyword = keyword,
                        title = title,
                        artist = artist,
                        album = album,
                        limit = ONLINE_SEARCH_PAGE_SIZE,
                        extended = extended,
                    )
                }
            }

            result
                .onSuccess {
                    onlineResults = it
                    onlineSearchExtended = extended
                    hasOnlineSearched = true
                    visibleOnlineResultsLimit = if (extended) {
                        (visibleOnlineResultsLimit + ONLINE_SEARCH_PAGE_SIZE).coerceAtMost(it.size)
                    } else {
                        ONLINE_SEARCH_PAGE_SIZE
                    }
                    if (it.isEmpty()) {
                        notify("没有搜到匹配的歌曲信息")
                    }
                }
                .onFailure { error ->
                    notify(error.message ?: "联网搜索失败")
                }

            isSearching = false
        }
    }

    fun searchLyric(extended: Boolean = false) {
        val keyword = currentSearchKeyword(title = title, artist = artist, fallback = song?.defaultTagSearchKeyword())
        if (keyword.isBlank() || isSearching) return

        scope.launch {
            isSearching = true
            onlineResultMode = ONLINE_RESULT_MODE_LYRIC
            if (!extended) hasOnlineSearched = false
            notify(if (extended) "继续搜索歌词" else "正在搜索歌词")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    onlineTagSearchService.search(
                        keyword = keyword,
                        title = title,
                        artist = artist,
                        album = album,
                        limit = ONLINE_SEARCH_PAGE_SIZE,
                        extended = extended,
                    )
                }
            }

            result
                .onSuccess {
                    onlineResults = it
                    onlineSearchExtended = extended
                    hasOnlineSearched = true
                    visibleOnlineResultsLimit = if (extended) {
                        (visibleOnlineResultsLimit + ONLINE_SEARCH_PAGE_SIZE).coerceAtMost(it.size)
                    } else {
                        ONLINE_SEARCH_PAGE_SIZE
                    }
                    if (it.isEmpty()) {
                        notify("没有搜到歌词")
                    }
                }
                .onFailure { error ->
                    notify(error.message ?: "歌词搜索失败")
                }

            isSearching = false
        }
    }

    fun exportCurrentCover() {
        val current = song ?: return
        if (isSearching) return

        scope.launch {
            isSearching = true
            notify("正在读取封面")
            val localCover = selectedLocalCover
            val networkCoverUrl = selectedCoverUrl.trim()
            val cover = withContext(Dispatchers.IO) {
                localCover
                    ?: networkCoverUrl
                        .takeIf(String::isNotBlank)
                        ?.let { downloadCover(httpClient, it) }
                    ?: readSongCover(context, current)
            }

            if (cover == null) {
                notify("这首歌现在没有可保存的封面。")
            } else {
                pendingCoverExport = cover
                exportCoverLauncher.launch(
                    "${safeFileName(title.ifBlank { current.name })}-cover.${cover.fileExtension()}"
                )
            }
            isSearching = false
        }
    }

    fun save() {
        val current = song ?: return
        if (isSaving) return

        scope.launch {
            isSaving = true
            notify("正在保存标签")
            val coverUrl = selectedCoverUrl.trim()
            val localCover = selectedLocalCover
            val shouldRemoveCover = removeCoverRequested
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = context.contentResolver
                        .openFileDescriptor(current.uri, "rw")
                        ?.use {
                            Taglib.writeMetadataWithFD(
                                fileDescriptor = it.detachFd(),
                                title = title.trim(),
                                album = album.trim(),
                                artist = artist.trim(),
                                albumArtist = albumArtist.trim(),
                                composer = composer.trim(),
                                lyricist = lyricist.trim(),
                                comment = comment.trim(),
                                genre = genre.trim(),
                                track = track.trim(),
                                disc = disc.trim(),
                                date = date.trim(),
                                sameSongGroup = sameSongGroup.trim(),
                                lyric = lyric
                            )
                        } ?: false

                    if (!saved) error("保存失败，当前格式可能暂不支持写入。")

                    songGroupStore.setGroup(current, sameSongGroup)
                    songWorkStore.setWork(current, work.trim())

                    val coverToWrite = localCover
                        ?: coverUrl.takeIf(String::isNotBlank)
                            ?.let { downloadCover(httpClient, it) }

                    if (shouldRemoveCover && coverToWrite == null) {
                        val removed = context.contentResolver
                            .openFileDescriptor(current.uri, "rw")
                            ?.use { Taglib.removeCoverWithFD(it.detachFd()) } == true

                        if (!removed) error("标签已保存，但封面移除失败。")
                    }

                    if (coverToWrite != null) {
                        val coverSaved = context.contentResolver
                            .openFileDescriptor(current.uri, "rw")
                            ?.use {
                                Taglib.writeCoverWithFD(
                                    fileDescriptor = it.detachFd(),
                                    cover = coverToWrite.bytes,
                                    mimeType = coverToWrite.mimeType
                                )
                            } == true

                        if (!coverSaved) error("标签已保存，但封面写入失败。")
                    }

                    current.fileInfo.pathStr?.takeIf(String::isNotBlank)?.let { path ->
                        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                    }
                }
            }

            result
                .onSuccess {
                    notify("保存成功，系统媒体库会重新扫描。")
                    AppRouter.intent(NavIntent.Pop)
                }
                .onFailure { error ->
                    val intentSender = error.mediaWritePermissionIntentSender(context, current.uri)
                    if (intentSender != null) {
                        notify(MSG_REQUEST_WRITE_PERMISSION)
                        runCatching {
                            writePermissionLauncher.launch(
                                IntentSenderRequest.Builder(intentSender).build()
                            )
                        }.onFailure {
                            notify(it.message ?: MSG_SAVE_FAILED)
                        }
                    } else {
                        notify(error.message ?: MSG_SAVE_FAILED)
                    }
                }

            isSaving = false
        }
    }

    LaunchedEffect(retrySaveAfterWritePermission) {
        if (!retrySaveAfterWritePermission) return@LaunchedEffect
        retrySaveAfterWritePermission = false
        save()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        if (song == null) {
            item {
                Text(
                    modifier = Modifier.padding(20.dp),
                    text = "没有找到这首歌。",
                    color = dayNightTextColor(0.65f),
                )
            }
        } else {
            item {
                CoverEditorCard(
                    song = song,
                    selectedLocalCoverUri = selectedLocalCoverUri,
                    selectedCoverUrl = selectedCoverUrl,
                    removeCoverRequested = removeCoverRequested,
                    isBusy = isSaving || isSearching || isApplyingOnlineResult,
                    onPickLocal = { localCoverLauncher.launch(arrayOf("image/*")) },
                    onSearchNetwork = { searchOnline(false) },
                    onRemove = {
                        selectedCoverUrl = ""
                        selectedLocalCover = null
                        selectedLocalCoverUri = null
                        removeCoverRequested = true
                        notify("保存后会移除封面。")
                    },
                    onSaveImage = ::exportCurrentCover,
                    onClearSelected = {
                        selectedCoverUrl = ""
                        selectedLocalCover = null
                        selectedLocalCoverUri = null
                        removeCoverRequested = false
                    },
                )
            }

            item {
                TagEditorActionRow(
                    isBusy = isSearching || isApplyingOnlineResult || isSaving,
                    onSearch = { searchOnline(false) },
                    onSearchLyric = { searchLyric(false) },
                    onSave = ::save,
                )
            }

            if (onlineResults.isNotEmpty()) {
                items(
                    items = onlineResults.take(visibleOnlineResultsLimit),
                    key = { it.id },
                    contentType = { OnlineSongTag::class.java }
                ) {
                    LyricCard(
                        title = it.title,
                        subTitle = it.artist,
                        caption = listOf(it.source, it.album)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        imageData = it.cover,
                        selected = { selectedOnlineId == it.id },
                        onClick = {
                            if (onlineResultMode == ONLINE_RESULT_MODE_LYRIC) {
                                applyLyricResult(it)
                            } else {
                                applyOnlineResult(it)
                            }
                        }
                    )
                }
            }

            if ((onlineResults.isNotEmpty()
                    && (onlineResults.size > visibleOnlineResultsLimit || !onlineSearchExtended))
                || (onlineResults.isEmpty() && hasOnlineSearched && !onlineSearchExtended)
            ) {
                item {
                    OnlineSearchMoreButton(
                        canShowMore = onlineResults.size > visibleOnlineResultsLimit,
                        canSearchMore = !onlineSearchExtended,
                        onClick = {
                            when {
                                onlineResults.size > visibleOnlineResultsLimit -> {
                                    visibleOnlineResultsLimit =
                                        (visibleOnlineResultsLimit + ONLINE_SEARCH_PAGE_SIZE)
                                            .coerceAtMost(onlineResults.size)
                                }

                                onlineResultMode == ONLINE_RESULT_MODE_LYRIC -> searchLyric(true)
                                else -> searchOnline(true)
                            }
                        }
                    )
                }
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TagField("标题", title, { title = it })
                        TagField("歌手", artist, { artist = it })
                        TagField("同曲分组", sameSongGroup, { sameSongGroup = it })
                        WorkTagField(workLabel, work, { work = it }, knownWorks)
                        TagField("作曲", composer, { composer = it })
                        TagField("作词", lyricist, { lyricist = it })
                        TagField("备注", comment, { comment = it })
                        TagField(
                            label = "歌词",
                            value = lyric,
                            onValueChange = { lyric = it },
                            minLines = 8,
                        )
                        LyricToolsRow(
                            enabled = !isSaving &&
                                    !isSearching &&
                                    !isApplyingOnlineResult &&
                                    lyric.isNotBlank(),
                            onSwapSameTimeLines = {
                                val swapped = swapSameTimeLyricLines(lyric)
                                if (swapped == lyric) {
                                    notify("\u6ca1\u6709\u627e\u5230\u53ef\u4ea4\u6362\u7684\u540c\u65f6\u95f4\u53cc\u884c\u6b4c\u8bcd")
                                } else {
                                    lyric = swapped
                                    notify("\u5df2\u4ea4\u6362\u540c\u65f6\u95f4\u7684\u539f\u6587/\u7ffb\u8bd1\u884c")
                                }
                            }
                        )
                    }
                }
            }
        }

        smartBarPadding()
    }
}

@Composable
private fun LyricToolsRow(
    enabled: Boolean,
    onSwapSameTimeLines: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.clickable(
                enabled = enabled,
                onClick = onSwapSameTimeLines,
            ),
            shape = RoundedCornerShape(8.dp),
            color = dayNightTextColor(if (enabled) 0.08f else 0.04f),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                text = "\u4ea4\u6362\u540c\u65f6\u95f4\u539f\u6587/\u7ffb\u8bd1",
                color = dayNightTextColor(if (enabled) 0.85f else 0.35f),
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
private fun OnlineSearchMoreButton(
    canShowMore: Boolean,
    canSearchMore: Boolean,
    onClick: () -> Unit,
) {
    if (!canShowMore && !canSearchMore) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = dayNightTextColor(0.06f),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            text = if (canShowMore) "显示更多" else "继续搜",
            color = Color(0xFF3EA22C),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun TagEditorActionRow(
    isBusy: Boolean,
    onSearch: () -> Unit,
    onSearchLyric: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = dayNightTextColor(0.06f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    enabled = !isBusy,
                    onClick = onSearchLyric,
                ) {
                    Text("词", color = dayNightTextColor(0.85f))
                }
                IconButton(
                    enabled = !isBusy,
                    onClick = onSearch,
                ) {
                    Icon(
                        imageVector = RemixIcon.System.searchLine,
                        contentDescription = "搜索信息",
                        tint = Color(0xFF3EA22C),
                    )
                }
                IconButton(
                    enabled = !isBusy,
                    onClick = onSave,
                ) {
                    Icon(
                        imageVector = RemixIcon.System.checkLine,
                        contentDescription = "保存",
                        tint = Color(0xFF3EA22C),
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverEditorCard(
    song: LSong,
    selectedLocalCoverUri: Uri?,
    selectedCoverUrl: String,
    removeCoverRequested: Boolean,
    isBusy: Boolean,
    onPickLocal: () -> Unit,
    onSearchNetwork: () -> Unit,
    onRemove: () -> Unit,
    onSaveImage: () -> Unit,
    onClearSelected: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val previewData: Any? = when {
        removeCoverRequested -> null
        selectedLocalCoverUri != null -> selectedLocalCoverUri
        selectedCoverUrl.isNotBlank() -> selectedCoverUrl
        else -> song
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        AsyncImage(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(enabled = !isBusy) { menuExpanded = true },
            model = ImageRequest.Builder(LocalContext.current)
                .size(900)
                .data(previewData)
                .placeholder(R.drawable.ic_music_line_bg_64dp)
                .error(R.drawable.ic_music_line_bg_64dp)
                .crossfade(true)
                .build(),
            contentScale = ContentScale.Crop,
            contentDescription = "Cover"
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onPickLocal()
            }) {
                Text("本地选择")
            }
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onSearchNetwork()
            }) {
                Text("搜索网络")
            }
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onRemove()
            }) {
                Text("移除图片")
            }
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onSaveImage()
            }) {
                Text("保存图片")
            }
            if (selectedLocalCoverUri != null || selectedCoverUrl.isNotBlank() || removeCoverRequested) {
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    onClearSelected()
                }) {
                    Text("不用这次改动")
                }
            }
        }
    }
}

@Composable
private fun TagField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    OutlinedTextField(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (minLines > 1) 160.dp else 0.dp),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        textStyle = MaterialTheme.typography.body2,
    )
}

@Composable
private fun WorkTagField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val keyword = value.trim()
    val matchedSuggestions = remember(keyword, suggestions) {
        if (keyword.isBlank()) {
            emptyList()
        } else {
            suggestions
                .filter { it != keyword && it.contains(keyword, ignoreCase = true) }
                .take(8)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.body2,
        )

        if (focused && matchedSuggestions.isNotEmpty()) {
            matchedSuggestions.forEach { suggestion ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable {
                        onValueChange(suggestion)
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = dayNightTextColor(0.06f),
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        text = suggestion,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
        }
    }
}

private data class LyricTimestampKey(
    val value: String,
    val isZero: Boolean,
)

private val lrcTimestampPrefixRegex = Regex("""^(?:\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?])+""")
private val lrcTimestampTokenRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

private fun swapSameTimeLyricLines(text: String): String {
    val newline = if (text.contains("\r\n")) "\r\n" else "\n"
    val lines = text.split(Regex("\r\n|\n|\r"))
    val result = mutableListOf<String>()
    var index = 0

    while (index < lines.size) {
        val key = sameTimeKey(lines[index])
        if (key == null) {
            result += lines[index]
            index += 1
            continue
        }

        val group = mutableListOf<String>()
        while (index < lines.size && sameTimeKey(lines[index])?.value == key.value) {
            group += lines[index]
            index += 1
        }

        if (!key.isZero && group.size == 2) {
            result += group[1]
            result += group[0]
        } else {
            result += group
        }
    }

    return result.joinToString(newline)
}

private fun sameTimeKey(line: String): LyricTimestampKey? {
    val prefix = lrcTimestampPrefixRegex.find(line)?.value ?: return null
    val tokens = lrcTimestampTokenRegex.findAll(prefix)
        .mapNotNull { match ->
            val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val millis = match.groupValues.getOrNull(3)
                ?.takeIf(String::isNotBlank)
                ?.padEnd(3, '0')
                ?.take(3)
                ?.toLongOrNull()
                ?: 0L
            minute * 60_000L + second * 1_000L + millis
        }
        .toList()

    if (tokens.isEmpty()) return null

    return LyricTimestampKey(
        value = tokens.joinToString("|"),
        isZero = tokens.all { it == 0L },
    )
}

private data class DownloadedCover(
    val bytes: ByteArray,
    val mimeType: String,
)

private fun Throwable.mediaWritePermissionIntentSender(
    context: Context,
    uri: Uri,
): IntentSender? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is RecoverableSecurityException) {
        return userAction.actionIntent.intentSender
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && this is SecurityException && uri.scheme == "content") {
        return runCatching {
            MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
        }.getOrNull()
    }

    return null
}

private fun DownloadedCover.fileExtension(): String {
    return when (mimeType.substringBefore(";").lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}

private fun readCoverFromUri(
    context: Context,
    uri: Uri,
): DownloadedCover? {
    return runCatching {
        val bytes = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: return null

        if (bytes.isEmpty()) return null

        DownloadedCover(
            bytes = bytes,
            mimeType = context.contentResolver.getType(uri)
                ?: guessImageMimeType(bytes)
        )
    }.getOrNull()
}

private suspend fun readSongCover(
    context: Context,
    song: LSong,
): DownloadedCover? {
    val embedded = runCatching {
        context.contentResolver.openFileDescriptor(song.uri, "r")
            ?.use { Taglib.getPictureWithFD(it.detachFd()) }
    }.getOrNull()

    if (embedded != null && embedded.isNotEmpty()) {
        return DownloadedCover(
            bytes = embedded,
            mimeType = guessImageMimeType(embedded)
        )
    }

    return listOf(song.albumCoverUri, song.artworkUri)
        .filterNotNull()
        .firstNotNullOfOrNull { readCoverFromUri(context, it) }
}

private fun writeCoverToUri(
    context: Context,
    uri: Uri,
    cover: DownloadedCover,
): Boolean {
    return runCatching {
        context.contentResolver.openOutputStream(uri, "wt")
            ?.use { it.write(cover.bytes) } != null
    }.getOrDefault(false)
}

private fun safeFileName(raw: String): String {
    return raw
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim()
        .take(80)
        .ifBlank { "cover" }
}

private fun LSong.defaultTagSearchKeyword(): String {
    return listOf(metadata.title, metadata.artist)
        .map { it.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
}

private fun currentSearchKeyword(
    title: String,
    artist: String,
    fallback: String?,
): String {
    return listOf(title, artist)
        .map { it.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
        .ifBlank { fallback.orEmpty() }
}

private fun downloadCover(
    client: OkHttpClient,
    url: String,
): DownloadedCover? {
    return runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val body = response.body ?: return null
            val bytes = body.bytes()
            if (bytes.isEmpty()) return null

            DownloadedCover(
                bytes = bytes,
                mimeType = body.contentType()?.toString()?.substringBefore(";")
                    ?: guessImageMimeType(bytes)
            )
        }
    }.getOrNull()
}

private fun guessImageMimeType(bytes: ByteArray): String {
    return when {
        bytes.size >= 8
                && bytes[0] == 0x89.toByte()
                && bytes[1] == 'P'.code.toByte()
                && bytes[2] == 'N'.code.toByte()
                && bytes[3] == 'G'.code.toByte() -> "image/png"

        bytes.size >= 3
                && bytes[0] == 0xFF.toByte()
                && bytes[1] == 0xD8.toByte()
                && bytes[2] == 0xFF.toByte() -> "image/jpeg"

        bytes.size >= 12
                && bytes[0] == 'R'.code.toByte()
                && bytes[1] == 'I'.code.toByte()
                && bytes[2] == 'F'.code.toByte()
                && bytes[3] == 'F'.code.toByte()
                && bytes[8] == 'W'.code.toByte()
                && bytes[9] == 'E'.code.toByte()
                && bytes[10] == 'B'.code.toByte()
                && bytes[11] == 'P'.code.toByte() -> "image/webp"

        else -> "image/jpeg"
    }
}
