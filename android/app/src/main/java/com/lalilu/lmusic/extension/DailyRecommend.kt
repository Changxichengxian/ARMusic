package com.lalilu.lmusic.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Chip
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lalilu.R
import com.lalilu.component.LazyGridContent
import com.lalilu.component.base.LocalWindowSize
import com.lalilu.component.navigation.AppRouter
import com.lalilu.component.navigation.NavIntent
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.compose.component.card.RecommendCard2
import com.lalilu.lmusic.compose.component.card.RecommendTitle
import com.lalilu.lmusic.compose.screen.songs.SongsScreen
import com.lalilu.lmusic.viewmodel.LibraryViewModel
import com.lalilu.lplayer.action.MediaControl
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import kotlin.random.Random

object DailyRecommend : LazyGridContent {

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    override fun register(): LazyGridScope.() -> Unit {
        val libraryViewModel: LibraryViewModel = koinInject()
        val windowWidthClass = LocalWindowSize.current.widthSizeClass

        return fun LazyGridScope.() {
            item(
                key = "daily_recommend_header",
                contentType = "daily_recommend_header",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                RecommendTitle(
                    modifier = Modifier.padding(vertical = 8.dp),
                    title = stringResource(id = R.string.home_daily_recommend),
                    onClick = {
                        val ids = libraryViewModel.dailyRecommends.value.map { it.id }
                        AppRouter.intent(
                            NavIntent.Push(
                                SongsScreen(
                                    mediaIds = ids,
                                    playAcrossLibrary = true,
                                )
                            )
                        )
                    }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(onClick = { libraryViewModel.forceUpdate() }) {
                            Text(
                                style = MaterialTheme.typography.caption,
                                text = stringResource(id = R.string.home_daily_shuffle)
                            )
                        }
                    }
                }
            }

            when (windowWidthClass) {
                WindowWidthSizeClass.Compact -> dailyRecommendForSideCompat()
                WindowWidthSizeClass.Medium -> dailyRecommendForSideMedium()
                WindowWidthSizeClass.Expanded -> dailyRecommendForSideCompat()
            }
        }
    }
}

fun LazyGridScope.dailyRecommendForSideCompat() {
    item(
        key = "daily_recommend",
        contentType = "daily_recommend",
        span = { GridItemSpan(maxLineSpan) }
    ) {
        val libraryViewModel: LibraryViewModel = koinInject()

        DailyRecommendAutoRow(
            libraryViewModel = libraryViewModel,
        )
    }
}

fun LazyGridScope.dailyRecommendForSideMedium() {
    dailyRecommendForSideCompat()
}

@Composable
private fun DailyRecommendAutoRow(
    libraryViewModel: LibraryViewModel,
) {
    val featured = libraryViewModel.dailyRecommends.value.distinctBy { it.id }
    val source = remember(featured, libraryViewModel.allSongs.value) {
        (featured + libraryViewModel.allSongs.value).distinctBy { it.id }
    }
    if (source.isEmpty()) return

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val speedPx = with(density) { 7.dp.toPx() }
    val random = remember(source, featured) { Random(System.currentTimeMillis()) }
    val rowItems = remember(source, featured) { mutableStateListOf<LSong>() }
    val cycleSize = source.size
    val futureCount = 26

    LaunchedEffect(source, featured) {
        rowItems.clear()
        val history = buildDailyRecommendCycle(source, random = random)
        val current = buildDailyRecommendCycle(
            source = source,
            prioritized = featured,
            random = random,
            previousId = history.lastOrNull()?.id,
        )
        val future = buildDailyRecommendCycle(
            source = source,
            random = random,
            previousId = current.lastOrNull()?.id,
        )
        rowItems.addAll(history + current + future)
        listState.scrollToItem(cycleSize)
    }

    LaunchedEffect(source, featured) {
        var lastFrameNanos = 0L
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                val deltaSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
                if (!listState.isScrollInProgress) {
                    try {
                        listState.scroll {
                            scrollBy(speedPx * deltaSeconds)
                        }
                    } catch (_: CancellationException) {
                        lastFrameNanos = frameNanos
                        continue
                    }
                }

                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (rowItems.size - lastVisibleIndex < futureCount) {
                    rowItems.addAll(
                        buildDailyRecommendCycle(
                            source = source,
                            random = random,
                            previousId = rowItems.lastOrNull()?.id,
                        )
                    )
                }

                val firstVisibleIndex = listState.firstVisibleItemIndex
                if (firstVisibleIndex < futureCount) {
                    val offset = listState.firstVisibleItemScrollOffset
                    rowItems.addAll(
                        0,
                        buildDailyRecommendCycle(
                            source = source,
                            random = random,
                            nextId = rowItems.firstOrNull()?.id,
                        )
                    )
                    listState.scrollToItem(firstVisibleIndex + cycleSize, offset)
                }

                if (firstVisibleIndex > cycleSize * 2 && rowItems.size > cycleSize * 3) {
                    val offset = listState.firstVisibleItemScrollOffset
                    repeat(cycleSize) {
                        rowItems.removeAt(0)
                    }
                    listState.scrollToItem(firstVisibleIndex - cycleSize, offset)
                }
            }
            lastFrameNanos = frameNanos
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = 250.dp
        val edgePadding = ((maxWidth - cardWidth) / 2).let { if (it > 0.dp) it else 0.dp }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            contentPadding = PaddingValues(horizontal = edgePadding, vertical = 10.dp),
        ) {
            itemsIndexed(items = rowItems) { _, song ->
                RecommendCard2(
                    item = { song },
                    modifier = Modifier.size(width = cardWidth, height = 250.dp),
                    onClick = {
                        MediaControl.playFromLibrary(song.id)
                    },
                    onLongClick = {
                        AppRouter.route("/pages/songs/detail")
                            .with("mediaId", song.id)
                            .jump()
                    }
                )
            }
        }
    }
}

internal fun buildDailyRecommendCycle(
    source: List<LSong>,
    prioritized: List<LSong> = emptyList(),
    random: Random,
    previousId: String? = null,
    nextId: String? = null,
): List<LSong> {
    val uniqueSource = source.distinctBy { it.id }
    val sourceIds = uniqueSource.mapTo(hashSetOf()) { it.id }
    val priority = prioritized
        .asSequence()
        .filter { it.id in sourceIds }
        .distinctBy { it.id }
        .toList()
    val priorityIds = priority.mapTo(hashSetOf()) { it.id }
    val cycle = (priority + uniqueSource.filterNot { it.id in priorityIds }.shuffled(random))
        .toMutableList()

    if (cycle.size > 1 && cycle.first().id == previousId) {
        cycle.add(cycle.removeAt(0))
    }
    if (cycle.size > 1 && cycle.last().id == nextId) {
        cycle.add(0, cycle.removeAt(cycle.lastIndex))
    }
    return cycle
}

fun LazyGridScope.dailyRecommendForSideExpanded(
    libraryViewModel: LibraryViewModel
) {
    item(
        key = "daily_recommend_left",
        contentType = "daily_recommend_left",
        span = { GridItemSpan(8) }
    ) {
        val item = libraryViewModel.dailyRecommends.value.getOrNull(0)
            ?: return@item

        Row(
            modifier = Modifier
                .height(250.dp)
                .fillMaxWidth()
                .padding(start = 16.dp)
        ) {
            RecommendCard2(
                item = { item },
                modifier = Modifier.fillMaxSize(),
                onClick = {
                    MediaControl.playFromLibrary(item.id)
                },
                onLongClick = {
                    AppRouter.route("/pages/songs/detail")
                        .with("mediaId", item.id)
                        .jump()
                }
            )
        }
    }

    item(
        key = "daily_recommend_right",
        contentType = "daily_recommend_right",
        span = { GridItemSpan(4) }
    ) {
        val item = libraryViewModel.dailyRecommends.value.getOrNull(1)
            ?: return@item
        val item2 = libraryViewModel.dailyRecommends.value.getOrNull(2)
            ?: return@item

        Column(
            modifier = Modifier
                .height(250.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RecommendCard2(
                item = { item },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onClick = {
                    MediaControl.playFromLibrary(item.id)
                },
                onLongClick = {
                    AppRouter.route("/pages/songs/detail")
                        .with("mediaId", item.id)
                        .jump()
                }
            )

            RecommendCard2(
                item = { item2 },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onClick = {
                    MediaControl.playFromLibrary(item2.id)
                },
                onLongClick = {
                    AppRouter.route("/pages/songs/detail")
                        .with("mediaId", item2.id)
                        .jump()
                }
            )
        }
    }
}
