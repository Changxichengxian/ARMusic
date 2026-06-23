package com.lalilu.lmusic.compose.screen.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import com.lalilu.R
import com.lalilu.RemixIcon
import com.lalilu.component.base.LocalEnhanceSheetState
import com.lalilu.component.base.LocalSmartBarPadding
import com.lalilu.component.base.TabScreen
import com.lalilu.component.base.screen.ScreenBarFactory
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.navigation.AppRouter
import com.lalilu.component.work.rememberWorkLabel
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.compose.screen.search.extensions.SearchArtistsResult
import com.lalilu.lmusic.compose.screen.search.extensions.SearchSongsResult
import com.lalilu.lmusic.compose.screen.search.extensions.SearchWorksResult
import com.lalilu.lmusic.utils.extension.edgeTransparent
import com.lalilu.lmusic.viewmodel.SearchScreenState
import com.lalilu.lmusic.viewmodel.SearchVM
import com.lalilu.lplayer.action.MediaControl
import com.lalilu.remixicon.System
import com.lalilu.remixicon.system.search2Line
import com.lalilu.remixicon.system.searchLine
import com.zhangke.krouter.annotation.Destination
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

@Destination("/pages/search")
data object SearchScreen : Screen, TabScreen, ScreenInfoFactory, ScreenBarFactory {
    private fun readResolve(): Any = SearchScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { stringResource(id = R.string.screen_title_search) },
            icon = RemixIcon.System.search2Line,
        )
    }

    @Composable
    override fun Content() {
        val searchVM: SearchVM = koinInject()

        SearchBar(searchVM = searchVM)

        SearchScreenContent(
            searchVM = searchVM
        )
    }
}

@Composable
private fun SearchScreenContent(
    searchVM: SearchVM = koinInject(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val state = searchVM.searchState.value
    val songs = LMedia.getFlow<LSong>()
        .collectAsState(initial = emptyList())
        .value
    val workLabel = rememberWorkLabel()

    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = when {
            state is SearchScreenState.Idle -> "Idle"
            state is SearchScreenState.Empty -> "Empty"
            else -> "Searching"
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = ""
    ) { searchState ->
        if (searchState == "Idle") {
            SearchIdleContent(
                modifier = Modifier
                    .fillMaxSize(),
                songs = songs,
            )
            return@AnimatedContent
        }

        if (searchState == "Empty") {
            SearchTips(
                modifier = Modifier
                    .fillMaxSize(),
                title = "暂无搜索结果"
            )
            return@AnimatedContent
        }

        val songsResult = remember {
            SearchSongsResult(
                songsResult = {
                    (searchVM.searchState.value as? SearchScreenState.Searching)
                        ?.songs ?: emptyList()
                }
            )
        }.register()

        val worksResult = remember(workLabel) {
            SearchWorksResult(
                workLabel = { workLabel },
            ) {
                (searchVM.searchState.value as? SearchScreenState.Searching)
                    ?.works ?: emptyList()
            }
        }.register()

        val artistsResult = remember {
            SearchArtistsResult {
                (searchVM.searchState.value as? SearchScreenState.Searching)
                    ?.artists ?: emptyList()
            }
        }.register()

        val lyricSongsResult = remember {
            SearchSongsResult(
                songsResult = {
                    (searchVM.searchState.value as? SearchScreenState.Searching)
                        ?.lyricSongs ?: emptyList()
                },
                title = "歌词搜索结果"
            )
        }.register()

        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            contentPadding = statusBar,
            columns = GridCells.Fixed(6)
        ) {
            item(
                key = "SearchScope",
                contentType = "SearchScope",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                SearchScopeRow(
                    modifier = Modifier.animateItem(),
                    searchVM = searchVM,
                    workLabel = workLabel,
                )
            }
            songsResult(this)
            worksResult(this)
            artistsResult(this)
            lyricSongsResult(this)
            smartBarPadding()
        }
    }
}

@Composable
private fun SearchIdleContent(
    modifier: Modifier = Modifier,
    songs: List<LSong>,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SearchPosterWall(
            modifier = Modifier.fillMaxSize(),
            songs = songs,
        )
    }
}

@Composable
private fun SearchPosterWall(
    modifier: Modifier = Modifier,
    songs: List<LSong>,
) {
    val posters = remember(songs) {
        songs.distinctBy { it.id }
    }
    if (posters.isEmpty()) return

    val density = LocalDensity.current
    val enhanceSheetState = LocalEnhanceSheetState.current
    val autoSpeedPx = with(density) { 56.dp.toPx() }
    val centerPlayerGesturePx = with(density) { 48.dp.toPx() }
    val centerPlayerTriggerPx = with(density) { 26.dp.toPx() }
    val random = remember(posters) { Random(System.currentTimeMillis()) }
    val leftColumnSongs = remember(posters) { mutableStateListOf<LSong>() }
    val rightColumnSongs = remember(posters) { mutableStateListOf<LSong>() }
    var leftShiftPx by remember(posters) { mutableStateOf(0f) }
    var rightShiftPx by remember(posters) { mutableStateOf(0f) }
    var leftVelocityPx by remember(posters) { mutableStateOf(0f) }
    var rightVelocityPx by remember(posters) { mutableStateOf(0f) }
    var draggingColumn by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .edgeTransparent(top = 120.dp, bottom = 170.dp, left = 10.dp, right = 10.dp)
            .pointerInput(enhanceSheetState, centerPlayerGesturePx, centerPlayerTriggerPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val centerX = size.width / 2f
                    if (abs(down.position.x - centerX) > centerPlayerGesturePx) {
                        return@awaitEachGesture
                    }

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) break

                        val dragX = change.position.x - down.position.x
                        val dragY = change.position.y - down.position.y
                        if (
                            dragY >= centerPlayerTriggerPx &&
                            dragY >= abs(dragX) * 0.75f
                        ) {
                            enhanceSheetState?.hide()
                            break
                        }

                        if (
                            dragY <= -centerPlayerTriggerPx ||
                            abs(dragX) > centerPlayerTriggerPx &&
                            abs(dragX) > dragY.coerceAtLeast(0f) * 1.35f
                        ) {
                            break
                        }
                    }
                }
            }
    ) {
        val tileGap = 4.dp
        val sideGap = 5.dp
        val cardWidth = (maxWidth - sideGap * 2 - tileGap) / 2f
        val cardStep = cardWidth
        val cardStepPx = with(density) { cardStep.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val posterIds = remember(posters) { posters.map(LSong::id) }
        val visibleCount = remember(containerHeightPx, cardStepPx) {
            (containerHeightPx / cardStepPx).roundToInt() + 3
        }
        val historyCount = 28
        val futureCount = 14
        val columnSize = visibleCount + historyCount + futureCount

        LaunchedEffect(posters, columnSize) {
            leftColumnSongs.clear()
            rightColumnSongs.clear()
            fillRandomPosterQueue(leftColumnSongs, posters, random, columnSize)
            fillRandomPosterQueue(rightColumnSongs, posters, random, columnSize)
            leftShiftPx = 0f
            rightShiftPx = 0f
            leftVelocityPx = 0f
            rightVelocityPx = 0f
        }

        LaunchedEffect(posters, autoSpeedPx, cardStepPx, columnSize) {
            var lastFrameNanos = 0L
            while (true) {
                val frameNanos = withFrameNanos { it }
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    if (draggingColumn != 0) {
                        leftShiftPx -= autoSpeedPx * deltaSeconds
                        if (abs(leftVelocityPx) > 1f) {
                            leftShiftPx += leftVelocityPx * deltaSeconds
                            leftVelocityPx *= 0.84f
                        } else {
                            leftVelocityPx = 0f
                        }
                    }
                    if (draggingColumn != 1) {
                        rightShiftPx += autoSpeedPx * deltaSeconds
                        if (abs(rightVelocityPx) > 1f) {
                            rightShiftPx += rightVelocityPx * deltaSeconds
                            rightVelocityPx *= 0.84f
                        } else {
                            rightVelocityPx = 0f
                        }
                    }

                    leftShiftPx = normalizePosterQueue(
                        queue = leftColumnSongs,
                        source = posters,
                        random = random,
                        shiftPx = leftShiftPx,
                        stepPx = cardStepPx,
                        visibleCount = visibleCount,
                        historyCount = historyCount,
                        futureCount = futureCount,
                    )
                    rightShiftPx = normalizePosterQueue(
                        queue = rightColumnSongs,
                        source = posters,
                        random = random,
                        shiftPx = rightShiftPx,
                        stepPx = cardStepPx,
                        visibleCount = visibleCount,
                        historyCount = historyCount,
                        futureCount = futureCount,
                    )
                }
                lastFrameNanos = frameNanos
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = sideGap),
            horizontalArrangement = Arrangement.spacedBy(tileGap)
        ) {
            repeat(2) { column ->
                val columnSongs = if (column == 0) leftColumnSongs else rightColumnSongs
                val shiftPx = if (column == 0) leftShiftPx else rightShiftPx

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(posters, column) {
                            var lastDragTimeMs = 0L
                            var dragToPlayer = false
                            var playerDragXPx = 0f
                            var playerDragYPx = 0f
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragToPlayer = if (column == 0) {
                                        start.x >= size.width - centerPlayerGesturePx
                                    } else {
                                        start.x <= centerPlayerGesturePx
                                    }
                                    playerDragXPx = 0f
                                    playerDragYPx = 0f
                                    if (!dragToPlayer) {
                                        draggingColumn = column
                                    }
                                    lastDragTimeMs = 0L
                                    if (column == 0) {
                                        leftVelocityPx = 0f
                                    } else {
                                        rightVelocityPx = 0f
                                    }
                                },
                                onDragCancel = {
                                    draggingColumn = null
                                    dragToPlayer = false
                                    playerDragXPx = 0f
                                    playerDragYPx = 0f
                                },
                                onDragEnd = {
                                    draggingColumn = null
                                    dragToPlayer = false
                                    playerDragXPx = 0f
                                    playerDragYPx = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (dragToPlayer) {
                                        playerDragXPx += dragAmount.x
                                        playerDragYPx += dragAmount.y
                                        if (
                                            playerDragYPx >= centerPlayerTriggerPx &&
                                            playerDragYPx >= abs(playerDragXPx) * 0.75f
                                        ) {
                                            enhanceSheetState?.hide()
                                            playerDragXPx = 0f
                                            playerDragYPx = 0f
                                            return@detectDragGestures
                                        }
                                        if (
                                            playerDragYPx <= -centerPlayerTriggerPx ||
                                            abs(playerDragXPx) > centerPlayerTriggerPx &&
                                            abs(playerDragXPx) > playerDragYPx.coerceAtLeast(0f) * 1.35f
                                        ) {
                                            dragToPlayer = false
                                            draggingColumn = column
                                        } else {
                                            return@detectDragGestures
                                        }
                                    }
                                    val dragY = dragAmount.y
                                    val nowMs = change.uptimeMillis
                                    if (column == 0) {
                                        leftShiftPx += dragY
                                    } else {
                                        rightShiftPx += dragY
                                    }
                                    if (lastDragTimeMs != 0L) {
                                        val elapsedMs = (nowMs - lastDragTimeMs).coerceAtLeast(1L)
                                        val velocity = (dragY / elapsedMs) * 1000f
                                        if (column == 0) {
                                            leftVelocityPx = velocity.coerceIn(-1800f, 1800f)
                                        } else {
                                            rightVelocityPx = velocity.coerceIn(-1800f, 1800f)
                                        }
                                    }
                                    lastDragTimeMs = nowMs
                                }
                            )
                        }
                ) {
                    columnSongs.forEachIndexed { index, song ->
                        val y = index * cardStepPx + shiftPx
                        if (y > -cardStepPx && y < containerHeightPx) {
                            SearchPosterTile(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset { IntOffset(x = 0, y = y.roundToInt()) },
                                song = song,
                                mediaIds = posterIds,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fillRandomPosterQueue(
    queue: MutableList<LSong>,
    source: List<LSong>,
    random: Random,
    targetSize: Int,
) {
    while (queue.size < targetSize) {
        appendRandomPosterBatch(
            queue = queue,
            source = source,
            random = random,
            count = targetSize - queue.size,
        )
    }
}

private fun normalizePosterQueue(
    queue: MutableList<LSong>,
    source: List<LSong>,
    random: Random,
    shiftPx: Float,
    stepPx: Float,
    visibleCount: Int,
    historyCount: Int,
    futureCount: Int,
): Float {
    var normalized = shiftPx
    val targetSize = visibleCount + historyCount + futureCount
    fillRandomPosterQueue(queue, source, random, targetSize)

    while (normalized > 0f) {
        queue.add(0, randomPosterSong(source, random, queue.firstOrNull()))
        normalized -= stepPx
    }

    while (normalized < -stepPx * historyCount) {
        if (queue.isNotEmpty()) queue.removeAt(0)
        appendRandomPosterBatch(
            queue = queue,
            source = source,
            random = random,
            count = 1,
        )
        normalized += stepPx
    }

    while (queue.size < targetSize) {
        appendRandomPosterBatch(
            queue = queue,
            source = source,
            random = random,
            count = targetSize - queue.size,
        )
    }

    while (queue.size > targetSize) {
        queue.removeAt(queue.lastIndex)
    }

    return normalized
}

private fun appendRandomPosterBatch(
    queue: MutableList<LSong>,
    source: List<LSong>,
    random: Random,
    count: Int,
) {
    if (count <= 0) return

    val batch = source.shuffled(random).toMutableList()
    if (batch.size > 1 && batch.firstOrNull()?.id == queue.lastOrNull()?.id) {
        val first = batch.removeAt(0)
        batch.add(first)
    }
    queue.addAll(batch.take(count))
}

private fun randomPosterSong(
    source: List<LSong>,
    random: Random,
    avoid: LSong? = null,
): LSong {
    if (source.size <= 1) return source.first()

    var song = source[random.nextInt(source.size)]
    repeat(8) {
        if (song.id != avoid?.id) return song
        song = source[random.nextInt(source.size)]
    }

    return song
}

@Composable
private fun SearchPosterTile(
    modifier: Modifier = Modifier,
    song: LSong,
    mediaIds: List<String>,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
    ) {
        AsyncImage(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(7.dp))
                .combinedClickable(
                    onClick = {
                        MediaControl.playWithList(
                            mediaIds = mediaIds,
                            mediaId = song.id,
                        )
                    },
                    onLongClick = {
                        AppRouter.route("/pages/songs/detail")
                            .with("mediaId", song.id)
                            .push()
                    }
                ),
            model = song,
            contentScale = ContentScale.Crop,
            contentDescription = null,
        )
    }
}

@Composable
private fun SearchScopeRow(
    modifier: Modifier = Modifier,
    searchVM: SearchVM,
    workLabel: String,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchScopeButton(
            text = "歌曲",
            selected = searchVM.includeSongs,
            onClick = { searchVM.includeSongs = !searchVM.includeSongs },
        )
        SearchScopeButton(
            text = workLabel,
            selected = searchVM.includeWorks,
            onClick = { searchVM.includeWorks = !searchVM.includeWorks },
        )
        SearchScopeButton(
            text = "艺术家",
            selected = searchVM.includeArtists,
            onClick = { searchVM.includeArtists = !searchVM.includeArtists },
        )
        SearchScopeButton(
            text = "歌词",
            selected = searchVM.includeLyrics,
            onClick = { searchVM.includeLyrics = !searchVM.includeLyrics },
        )
    }
}

@Composable
private fun RowScope.SearchScopeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier.weight(1f),
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground.copy(0.72f),
            backgroundColor = if (selected) MaterialTheme.colors.primary.copy(0.14f) else MaterialTheme.colors.onBackground.copy(0.06f),
        )
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 13.sp,
        )
    }
}

@Preview
@Composable
fun SearchTips(
    modifier: Modifier = Modifier,
    title: String = "暂无搜索结果"
) {
    val paddingBottom = LocalSmartBarPadding.current.value.calculateBottomPadding()
    val imePadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = paddingBottom + imePadding),
        contentAlignment = Alignment.Center
    ) {
        Column {
            Row(
                modifier = Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = RemixIcon.System.searchLine,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackground.copy(0.4f)
                )
                Text(
                    text = title,
                    fontSize = 14.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colors.onBackground.copy(0.6f)
                )
            }
        }
    }
}
