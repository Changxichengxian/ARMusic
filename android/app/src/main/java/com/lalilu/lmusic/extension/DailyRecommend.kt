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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlin.math.max
import kotlin.random.Random

object DailyRecommend : LazyGridContent {

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    override fun register(): LazyGridScope.() -> Unit {
        val libraryVM: LibraryViewModel = koinInject()
        val windowWidthClass = LocalWindowSize.current.widthSizeClass
        var autoScroll by rememberSaveable { mutableStateOf(true) }

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
                        val ids = libraryVM.dailyRecommends.value.map { it.id }
                        AppRouter.intent(NavIntent.Push(SongsScreen(mediaIds = ids)))
                    }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(onClick = { autoScroll = !autoScroll }) {
                            Text(
                                style = MaterialTheme.typography.caption,
                                text = if (autoScroll) {
                                    stringResource(id = R.string.home_daily_stop_scroll)
                                } else {
                                    stringResource(id = R.string.home_daily_start_scroll)
                                }
                            )
                        }
                        Chip(onClick = { libraryVM.forceUpdate() }) {
                            Text(
                                style = MaterialTheme.typography.caption,
                                text = stringResource(id = R.string.home_daily_shuffle)
                            )
                        }
                    }
                }
            }

            when (windowWidthClass) {
                WindowWidthSizeClass.Compact -> dailyRecommendForSideCompat(autoScroll)
                WindowWidthSizeClass.Medium -> dailyRecommendForSideMedium(autoScroll)
                WindowWidthSizeClass.Expanded -> dailyRecommendForSideCompat(autoScroll)
            }
        }
    }
}

fun LazyGridScope.dailyRecommendForSideCompat(autoScroll: Boolean) {
    item(
        key = "daily_recommend",
        contentType = "daily_recommend",
        span = { GridItemSpan(maxLineSpan) }
    ) {
        val libraryVM: LibraryViewModel = koinInject()

        DailyRecommendAutoRow(
            libraryVM = libraryVM,
            autoScroll = autoScroll,
        )
    }
}

fun LazyGridScope.dailyRecommendForSideMedium(autoScroll: Boolean) {
    dailyRecommendForSideCompat(autoScroll)
}

@Composable
private fun DailyRecommendAutoRow(
    libraryVM: LibraryViewModel,
    autoScroll: Boolean,
) {
    val source = libraryVM.dailyRecommends.value.distinctBy { it.id }
    if (source.isEmpty()) return

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val speedPx = with(density) { 7.dp.toPx() }
    val random = remember(source) { Random(System.currentTimeMillis()) }
    val rowItems = remember(source) { mutableStateListOf<LSong>() }
    val historyCount = 42
    val futureCount = 26
    val minQueueSize = 72

    LaunchedEffect(source) {
        rowItems.clear()
        appendDailyRecommendItems(rowItems, source, random, historyCount)
        rowItems.addAll(source)
        appendDailyRecommendItems(
            queue = rowItems,
            source = source,
            random = random,
            count = max(minQueueSize - rowItems.size, 0)
        )
        listState.scrollToItem(historyCount)
    }

    LaunchedEffect(source, autoScroll) {
        var lastFrameNanos = 0L
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                val deltaSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
                if (autoScroll && !listState.isScrollInProgress) {
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
                    appendDailyRecommendItems(rowItems, source, random, futureCount)
                }

                val firstVisibleIndex = listState.firstVisibleItemIndex
                if (firstVisibleIndex < futureCount) {
                    val offset = listState.firstVisibleItemScrollOffset
                    prependDailyRecommendItems(rowItems, source, random, futureCount)
                    listState.scrollToItem(firstVisibleIndex + futureCount, offset)
                }

                if (firstVisibleIndex > historyCount * 2) {
                    val removeCount = firstVisibleIndex - historyCount
                    val actualRemove = removeCount.coerceAtMost(max(rowItems.size - minQueueSize, 0))
                    repeat(actualRemove) {
                        rowItems.removeAt(0)
                    }
                    if (actualRemove > 0) {
                        listState.scrollToItem(
                            index = max(firstVisibleIndex - actualRemove, 0),
                            scrollOffset = listState.firstVisibleItemScrollOffset,
                        )
                    }
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
                        MediaControl.playWithList(
                            mediaIds = rowItems.map { song -> song.id },
                            mediaId = song.id
                        )
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

private fun appendDailyRecommendItems(
    queue: MutableList<LSong>,
    source: List<LSong>,
    random: Random,
    count: Int,
) {
    if (count <= 0) return

    var appended = 0
    while (appended < count) {
        val batch = source.shuffled(random).toMutableList()
        if (batch.size > 1 && batch.firstOrNull()?.id == queue.lastOrNull()?.id) {
            val first = batch.removeAt(0)
            batch.add(first)
        }
        val takeCount = minOf(count - appended, batch.size)
        queue.addAll(batch.take(takeCount))
        appended += takeCount
    }
}

private fun prependDailyRecommendItems(
    queue: MutableList<LSong>,
    source: List<LSong>,
    random: Random,
    count: Int,
) {
    if (count <= 0) return

    val batchQueue = mutableListOf<LSong>()
    appendDailyRecommendItems(batchQueue, source, random, count)
    if (batchQueue.size > 1 && batchQueue.lastOrNull()?.id == queue.firstOrNull()?.id) {
        val last = batchQueue.removeAt(batchQueue.lastIndex)
        batchQueue.add(0, last)
    }
    queue.addAll(0, batchQueue)
}

fun LazyGridScope.dailyRecommendForSideExpanded(
    libraryVM: LibraryViewModel
) {
    item(
        key = "daily_recommend_left",
        contentType = "daily_recommend_left",
        span = { GridItemSpan(8) }
    ) {
        val item = libraryVM.dailyRecommends.value.getOrNull(0)
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
                    MediaControl.playWithList(
                        mediaIds = libraryVM.dailyRecommends.value.map { song -> song.id },
                        mediaId = item.id
                    )
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
        val item = libraryVM.dailyRecommends.value.getOrNull(1)
            ?: return@item
        val item2 = libraryVM.dailyRecommends.value.getOrNull(2)
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
                    MediaControl.playWithList(
                        mediaIds = libraryVM.dailyRecommends.value.map { song -> song.id },
                        mediaId = item.id
                    )
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
                    MediaControl.playWithList(
                        mediaIds = libraryVM.dailyRecommends.value.map { song -> song.id },
                        mediaId = item2.id
                    )
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
