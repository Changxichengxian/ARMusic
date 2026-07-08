package com.lalilu.lmusic.compose.screen.tag

import android.app.Activity
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.lalilu.lmusic.compose.screen.lyric.LyricCard
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.entity.toMediaItem
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmedia.scanner.FileSystemScanner
import com.lalilu.lmedia.wrapper.Taglib
import com.lalilu.lmusic.api.tag.OnlineSongTag
import com.lalilu.lmusic.api.tag.OnlineTagSearchService
import com.lalilu.lmusic.tag.SongGroupStore
import com.lalilu.lplayer.MPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import com.lalilu.RemixIcon
import com.lalilu.remixicon.System
import com.lalilu.remixicon.system.checkLine
import com.lalilu.remixicon.system.searchLine

private const val ONLINE_RESULT_MODE_TAG = "tag"
private const val ONLINE_RESULT_MODE_LYRIC = "lyric"
private const val ONLINE_RESULT_MODE_IMAGE = "image"
private const val ONLINE_SEARCH_PAGE_SIZE = 10
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
    fileSystemScanner: FileSystemScanner = koinInject(),
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
    val editableCovers = remember { mutableStateListOf<DownloadedCover>() }
    val coverPagerState = rememberPagerState(pageCount = { editableCovers.size + 1 })
    var coversChanged by rememberSaveable { mutableStateOf(false) }
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

    fun putCoverOnCurrentPage(cover: DownloadedCover) {
        val page = coverPagerState.currentPage.coerceAtMost(editableCovers.size)
        if (page in editableCovers.indices) {
            editableCovers[page] = cover
        } else {
            editableCovers.add(cover)
        }
        coversChanged = true
        removeCoverRequested = false
    }

    suspend fun previewCoverFromUrl(url: String): Boolean {
        if (url.isBlank()) {
            notify(context.getString(R.string.tag_editor_no_cover_url))
            return false
        }

        val result = withContext(Dispatchers.IO) {
            downloadCover(httpClient, url)?.prepareForEmbeddedCover(context)
        }
        if (result == null) {
            notify(context.getString(R.string.tag_editor_cover_read_failed))
            return false
        }

        val cover = result.cover.getOrElse {
            notify(it.message ?: context.getString(R.string.common_save_failed))
            return false
        }
        result.messages.forEach(::notify)
        putCoverOnCurrentPage(cover)
        return true
    }

    val localCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                readCoverFromUri(context, uri)?.prepareForEmbeddedCover(context)
            }
            if (result == null) {
                notify(context.getString(R.string.tag_editor_no_image_read))
                return@launch
            }

            val cover = result.cover.getOrElse {
                notify(it.message ?: context.getString(R.string.common_save_failed))
                return@launch
            }
            result.messages.forEach(::notify)

            putCoverOnCurrentPage(cover)
            notify(context.getString(R.string.tag_editor_local_cover_previewed))
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
            notify(
                context.getString(
                    if (saved) R.string.tag_editor_cover_saved
                    else R.string.tag_editor_cover_save_failed
                )
            )
            pendingCoverExport = null
        }
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            retrySaveAfterWritePermission = true
        } else {
            notify(context.getString(R.string.tag_editor_write_permission_denied))
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
        editableCovers.clear()
        coversChanged = false
        removeCoverRequested = false
        onlineResults = emptyList()
        onlineResultMode = ONLINE_RESULT_MODE_TAG
        onlineSearchExtended = false
        hasOnlineSearched = false
        visibleOnlineResultsLimit = ONLINE_SEARCH_PAGE_SIZE
        val initial = withContext(Dispatchers.IO) {
            val covers = readSongCovers(context, current)
            val loadedLyric = runCatching {
                context.contentResolver.openFileDescriptor(current.uri, "r")?.use {
                    Taglib.getLyricWithFD(it.detachFd())
                }.orEmpty()
            }.getOrDefault("")
            covers to loadedLyric
        }
        editableCovers.addAll(initial.first)
        lyric = initial.second
        coverPagerState.scrollToPage(0)
    }

    fun applyOnlineResult(result: OnlineSongTag) {
        if (isApplyingOnlineResult) return

        selectedOnlineId = result.id
        onlineResults = emptyList()
        title = result.title
        artist = result.artist
        comment = result.comment

        scope.launch {
            isApplyingOnlineResult = true
            try {
                if (result.cover.isNotBlank()) {
                    notify(context.getString(R.string.tag_editor_reading_cover))
                    previewCoverFromUrl(result.cover)
                }

                notify(context.getString(R.string.tag_editor_fetching_lyrics))
                val onlineLyric = withContext(Dispatchers.IO) {
                    runCatching {
                        onlineTagSearchService.lyricFor(result)
                    }.getOrDefault("")
                }

                if (onlineLyric.isNotBlank()) {
                    lyric = onlineLyric
                }
                notify(context.getString(R.string.tag_editor_preview_ready))
            } finally {
                isApplyingOnlineResult = false
            }
        }
    }

    fun applyImageResult(result: OnlineSongTag) {
        if (isApplyingOnlineResult) return

        selectedOnlineId = result.id

        scope.launch {
            isApplyingOnlineResult = true
            try {
                notify(context.getString(R.string.tag_editor_reading_cover))
                if (previewCoverFromUrl(result.cover)) {
                    onlineResults = emptyList()
                    notify(context.getString(R.string.tag_editor_cover_previewed))
                }
            } finally {
                isApplyingOnlineResult = false
            }
        }
    }

    fun applyLyricResult(result: OnlineSongTag) {
        if (isApplyingOnlineResult) return

        selectedOnlineId = result.id
        onlineResults = emptyList()

        scope.launch {
            isApplyingOnlineResult = true
            notify(context.getString(R.string.tag_editor_fetching_lyrics))
            val onlineLyric = withContext(Dispatchers.IO) {
                runCatching {
                    onlineTagSearchService.lyricFor(result)
                }.getOrDefault("")
            }

            if (onlineLyric.isBlank()) {
                notify(context.getString(R.string.tag_editor_no_candidate_lyrics))
            } else {
                lyric = onlineLyric
                notify(context.getString(R.string.tag_editor_lyrics_previewed))
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
            notify(
                context.getString(
                    if (extended) R.string.tag_editor_searching_more_info
                    else R.string.tag_editor_searching_info
                )
            )
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
                        notify(context.getString(R.string.tag_editor_no_tag_results))
                    }
                }
                .onFailure { error ->
                    notify(error.message ?: context.getString(R.string.tag_editor_online_search_failed))
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
            notify(
                context.getString(
                    if (extended) R.string.tag_editor_searching_more_lyrics
                    else R.string.tag_editor_searching_lyrics
                )
            )
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
                        notify(context.getString(R.string.tag_editor_no_lyrics_results))
                    }
                }
                .onFailure { error ->
                    notify(error.message ?: context.getString(R.string.tag_editor_lyrics_search_failed))
                }

            isSearching = false
        }
    }

    fun searchImage(extended: Boolean = false) {
        val keyword = currentSearchKeyword(title = title, artist = artist, fallback = song?.defaultTagSearchKeyword())
        if (keyword.isBlank() || isSearching) return

        scope.launch {
            isSearching = true
            onlineResultMode = ONLINE_RESULT_MODE_IMAGE
            if (!extended) hasOnlineSearched = false
            notify(
                context.getString(
                    if (extended) R.string.tag_editor_searching_more_images
                    else R.string.tag_editor_searching_images
                )
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    onlineTagSearchService.search(
                        keyword = keyword,
                        title = title,
                        artist = artist,
                        album = album,
                        limit = ONLINE_SEARCH_PAGE_SIZE,
                        extended = extended,
                    ).filter { it.cover.isNotBlank() }
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
                        notify(context.getString(R.string.tag_editor_no_image_results))
                    }
                }
                .onFailure { error ->
                    notify(error.message ?: context.getString(R.string.tag_editor_image_search_failed))
                }

            isSearching = false
        }
    }

    fun exportCurrentCover() {
        val current = song ?: return
        if (isSearching) return

        scope.launch {
            isSearching = true
            notify(context.getString(R.string.tag_editor_reading_cover))
            val currentCover = editableCovers.getOrNull(coverPagerState.currentPage)
            val cover = withContext(Dispatchers.IO) {
                currentCover ?: readSongCover(context, current)
            }

            if (cover == null) {
                notify(context.getString(R.string.tag_editor_no_cover_to_save))
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
            notify(context.getString(R.string.tag_editor_saving_tags))
            val coversToWrite = editableCovers.toList()
            val shouldWriteCovers = coversChanged
            val shouldRemoveCover = removeCoverRequested
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val updatedSong = current.copy(
                        metadata = current.metadata.copy(
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
                            work = work.trim(),
                            sameSongGroup = sameSongGroup.trim(),
                            dateModified = System.currentTimeMillis() / 1000L,
                        )
                    ).also {
                        it.artworkUri = current.artworkUri
                        it.blocked = current.blocked
                    }

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
                                work = work.trim(),
                                sameSongGroup = sameSongGroup.trim(),
                                lyric = lyric
                            )
                        } ?: false

                    if (!saved) error(context.getString(R.string.tag_editor_save_unsupported))

                    songGroupStore.setGroup(current, sameSongGroup)
                    songWorkStore.setWork(current, work.trim())

                    if (shouldWriteCovers && shouldRemoveCover && coversToWrite.isEmpty()) {
                        val removed = context.contentResolver
                            .openFileDescriptor(current.uri, "rw")
                            ?.use { Taglib.removeCoverWithFD(it.detachFd()) } == true

                        if (!removed) error(context.getString(R.string.tag_editor_cover_remove_failed_after_save))
                    }

                    if (shouldWriteCovers && coversToWrite.isNotEmpty()) {
                        val coverSaved = context.contentResolver
                            .openFileDescriptor(current.uri, "rw")
                            ?.use {
                                if (coversToWrite.size == 1) {
                                    Taglib.writeCoverWithFD(
                                        fileDescriptor = it.detachFd(),
                                        cover = coversToWrite.first().bytes,
                                        mimeType = coversToWrite.first().mimeType
                                    )
                                } else {
                                    Taglib.writeCoversWithFD(
                                        fileDescriptor = it.detachFd(),
                                        covers = coversToWrite.map { cover -> cover.bytes }.toTypedArray(),
                                        mimeTypes = coversToWrite.map { cover -> cover.mimeType }.toTypedArray()
                                    )
                                }
                            } == true

                        if (!coverSaved) error(context.getString(R.string.tag_editor_cover_write_failed_after_save))
                    }

                    refreshSavedSong(context, updatedSong, fileSystemScanner)
                    updatedSong
                }
            }

            result
                .onSuccess { updatedSong ->
                    MPlayer.replaceMediaItem(updatedSong.toMediaItem())
                    notify(context.getString(R.string.common_save_success))
                    AppRouter.intent(NavIntent.Pop)
                }
                .onFailure { error ->
                    val intentSender = error.mediaWritePermissionIntentSender(context, current.uri)
                    if (intentSender != null) {
                        notify(context.getString(R.string.tag_editor_request_write_permission))
                        runCatching {
                            writePermissionLauncher.launch(
                                IntentSenderRequest.Builder(intentSender).build()
                            )
                        }.onFailure {
                            notify(it.message ?: context.getString(R.string.common_save_failed))
                        }
                    } else {
                        notify(error.message ?: context.getString(R.string.common_save_failed))
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
                    text = stringResource(id = R.string.tag_editor_song_not_found),
                    color = dayNightTextColor(0.65f),
                )
            }
        } else {
            item {
                CoverEditorCard(
                    covers = editableCovers,
                    pagerState = coverPagerState,
                    coversChanged = coversChanged,
                    isBusy = isSaving || isSearching || isApplyingOnlineResult,
                    onPickLocal = { localCoverLauncher.launch(arrayOf("image/*")) },
                    onSearchNetwork = { searchImage(false) },
                    onRemove = {
                        val page = coverPagerState.currentPage
                        if (page in editableCovers.indices) {
                            editableCovers.removeAt(page)
                            coversChanged = true
                            removeCoverRequested = editableCovers.isEmpty()
                            notify(
                                context.getString(
                                    if (editableCovers.isEmpty()) {
                                        R.string.tag_editor_cover_will_be_removed
                                    } else {
                                        R.string.tag_editor_current_cover_will_be_removed
                                    }
                                )
                            )
                        }
                    },
                    onSaveImage = ::exportCurrentCover,
                    onMakePrimary = {
                        val page = coverPagerState.currentPage
                        if (page > 0 && page in editableCovers.indices) {
                            val cover = editableCovers.removeAt(page)
                            editableCovers.add(0, cover)
                            coversChanged = true
                            removeCoverRequested = false
                            scope.launch {
                                coverPagerState.animateScrollToPage(0)
                            }
                            notify(context.getString(R.string.tag_editor_primary_cover_previewed))
                        }
                    },
                    onResetCovers = {
                        val current = song
                        scope.launch {
                            isSearching = true
                            val covers = withContext(Dispatchers.IO) {
                                readSongCovers(context, current)
                            }
                            editableCovers.clear()
                            editableCovers.addAll(covers)
                            coversChanged = false
                            removeCoverRequested = false
                            coverPagerState.scrollToPage(0)
                            notify(context.getString(R.string.tag_editor_cover_preview_reset))
                            isSearching = false
                        }
                    },
                )
            }

            item {
                TagEditorActionRow(
                    isBusy = isSearching || isApplyingOnlineResult || isSaving,
                    onSearch = { searchOnline(false) },
                    onSearchLyric = { searchLyric(false) },
                    onSearchImage = { searchImage(false) },
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
                            when (onlineResultMode) {
                                ONLINE_RESULT_MODE_LYRIC -> applyLyricResult(it)
                                ONLINE_RESULT_MODE_IMAGE -> applyImageResult(it)
                                else -> applyOnlineResult(it)
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
                                onlineResultMode == ONLINE_RESULT_MODE_IMAGE -> searchImage(true)
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
                        TagField(stringResource(id = R.string.tag_editor_title), title, { title = it })
                        TagField(stringResource(id = R.string.tag_editor_artist), artist, { artist = it })
                        TagField(
                            stringResource(id = R.string.tag_editor_same_song_group),
                            sameSongGroup,
                            { sameSongGroup = it }
                        )
                        WorkTagField(workLabel, work, { work = it }, knownWorks)
                        TagField(stringResource(id = R.string.tag_editor_comment), comment, { comment = it })
                        TagField(
                            label = stringResource(id = R.string.tag_editor_lyrics),
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
                                    notify(context.getString(R.string.tag_editor_no_swappable_lines))
                                } else {
                                    lyric = swapped
                                    notify(context.getString(R.string.tag_editor_swapped_lines))
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
                text = stringResource(id = R.string.tag_editor_swap_same_time_lines),
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
            text = if (canShowMore) {
                stringResource(id = R.string.common_show_more)
            } else {
                stringResource(id = R.string.common_search_more)
            },
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
    onSearchImage: () -> Unit,
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
                    Icon(
                        painter = painterResource(id = R.drawable.ic_text),
                        contentDescription = stringResource(id = R.string.tag_editor_search_lyrics),
                        tint = Color(0xFF3EA22C),
                    )
                }
                IconButton(
                    enabled = !isBusy,
                    onClick = onSearch,
                ) {
                    Icon(
                        imageVector = RemixIcon.System.searchLine,
                        contentDescription = stringResource(id = R.string.tag_editor_search_info),
                        tint = Color(0xFF3EA22C),
                    )
                }
                IconButton(
                    enabled = !isBusy,
                    onClick = onSearchImage,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_music_2_line),
                        contentDescription = stringResource(id = R.string.tag_editor_search_cover),
                        tint = Color(0xFF3EA22C),
                    )
                }
                IconButton(
                    enabled = !isBusy,
                    onClick = onSave,
                ) {
                    Icon(
                        imageVector = RemixIcon.System.checkLine,
                        contentDescription = stringResource(id = R.string.common_save),
                        tint = Color(0xFF3EA22C),
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverEditorCard(
    covers: List<DownloadedCover>,
    pagerState: PagerState,
    coversChanged: Boolean,
    isBusy: Boolean,
    onPickLocal: () -> Unit,
    onSearchNetwork: () -> Unit,
    onRemove: () -> Unit,
    onSaveImage: () -> Unit,
    onMakePrimary: () -> Unit,
    onResetCovers: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val currentPage = pagerState.currentPage
    val currentCover = covers.getOrNull(currentPage)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        HorizontalPager(
            modifier = Modifier.fillMaxSize(),
            state = pagerState,
        ) { page ->
            val cover = covers.getOrNull(page)
            if (cover != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(enabled = !isBusy) { menuExpanded = true },
                        model = ImageRequest.Builder(LocalContext.current)
                            .size(900)
                            .data(cover.bytes)
                            .placeholder(R.drawable.ic_music_line_bg_64dp)
                            .error(R.drawable.ic_music_line_bg_64dp)
                            .crossfade(true)
                            .build(),
                        contentScale = ContentScale.Crop,
                        contentDescription = stringResource(id = R.string.tag_editor_cover)
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.42f),
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            text = "${page + 1}/${covers.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = !isBusy, onClick = onPickLocal),
                    color = dayNightTextColor(0.04f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "+",
                            color = dayNightTextColor(0.58f),
                            style = MaterialTheme.typography.h3,
                        )
                        Text(
                            text = stringResource(id = R.string.tag_editor_add_cover),
                            color = dayNightTextColor(0.58f),
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded && currentCover != null,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onPickLocal()
            }) {
                Text(stringResource(id = R.string.tag_editor_pick_local))
            }
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onSearchNetwork()
            }) {
                Text(stringResource(id = R.string.tag_editor_search_network))
            }
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onRemove()
            }) {
                Text(stringResource(id = R.string.tag_editor_remove_image))
            }
            DropdownMenuItem(onClick = {
                menuExpanded = false
                onSaveImage()
            }) {
                Text(stringResource(id = R.string.tag_editor_save_image))
            }
            if (currentPage > 0) {
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    onMakePrimary()
                }) {
                    Text(stringResource(id = R.string.tag_editor_make_primary_cover))
                }
            }
            if (coversChanged) {
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    onResetCovers()
                }) {
                    Text(stringResource(id = R.string.tag_editor_reset_cover_preview))
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
