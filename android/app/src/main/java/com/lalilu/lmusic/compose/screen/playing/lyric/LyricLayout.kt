package com.lalilu.lmusic.compose.screen.playing.lyric

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.funny.data_saver.core.DataSaverMutableState
import com.lalilu.lmedia.lyric.LyricItem
import com.lalilu.lmusic.compose.screen.playing.lyric.impl.LyricContentNormal
import com.lalilu.lmusic.compose.screen.playing.lyric.impl.LyricContentWords
import com.lalilu.lmusic.utils.extension.edgeTransparent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import kotlin.math.abs

@OptIn(FlowPreview::class)
@Composable
fun LyricLayout(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    currentTime: () -> Long = { 0L },
    lyricEntry: State<List<LyricItem>> = remember { mutableStateOf(emptyList()) },
    screenConstraints: Constraints,
    isUserClickEnable: () -> Boolean = { false },
    isUserScrollEnable: () -> Boolean = { false },
    onPositionReset: () -> Unit = {},
    onItemClick: (LyricItem) -> Unit = {},
    onItemLongClick: (LyricItem) -> Unit = {},
) {
    val density = LocalDensity.current
    val settings: DataSaverMutableState<LyricSettings> = koinInject(named("LyricSettings"))
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val isUserScrolling = remember { mutableStateOf(isUserScrollEnable()) }
        .also { it.value = isUserScrollEnable() }
    val displayLyrics = remember {
        derivedStateOf {
            lyricEntry.value.prepareLyricsForDisplay(
                hideZeroTimeCredits = settings.value.hideZeroTimeCredits,
            )
        }
    }

    val currentItemIndex = remember {
        derivedStateOf {
            val time = currentTime() + settings.value.timeOffset
            val lyricEntryList = displayLyrics.value

            lyricEntryList.findDisplayIndex(time)
        }
    }

    val isBeforeFirstLine = remember {
        derivedStateOf {
            val firstLineTime = displayLyrics.value.firstOrNull()?.time
                ?: return@derivedStateOf false
            currentTime() + settings.value.timeOffset < firstLineTime
        }
    }

    val openingProgress = remember {
        derivedStateOf {
            lyricOpeningProgress(
                time = currentTime() + settings.value.timeOffset,
                firstLineTime = displayLyrics.value.firstOrNull()?.time,
                blurOpeningEnabled = settings.value.blurOpeningEnable,
            )
        }
    }

    fun displayFocusPosition(): Float {
        return lyricFocusPosition(settings.value.highlightPosition)
    }

    suspend fun alignCurrentItemToFocus(animate: Boolean) {
        if (isUserScrolling.value) return
        val lyrics = displayLyrics.value
        val targetIndex = if (lyrics.isEmpty()) {
            1
        } else {
            currentItemIndex.value
                .takeIf { it in lyrics.indices }
                ?.plus(1)
                ?: return
        }

        if (listState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
            listState.scrollToItem(targetIndex)
            withFrameNanos { }
        }

        repeat(4) { attempt ->
            withFrameNanos { }
            val layoutInfo = listState.layoutInfo
            val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                ?: return@repeat
            val delta = lyricAnchorDelta(
                itemOffset = item.offset,
                itemSize = item.size,
                viewportHeight = layoutInfo.viewportSize.height,
                focusPosition = displayFocusPosition(),
            )
            if (abs(delta) <= 0.5f) return@repeat

            if (animate && attempt == 0) {
                listState.animateScrollBy(
                    value = delta,
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                )
            } else {
                listState.scroll { scrollBy(delta) }
            }
        }

        val layoutInfo = listState.layoutInfo
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        val finalDelta = item?.let {
            lyricAnchorDelta(
                itemOffset = it.offset,
                itemSize = it.size,
                viewportHeight = layoutInfo.viewportSize.height,
                focusPosition = displayFocusPosition(),
            )
        }
        Log.w(
            "ARMusicLyricAnchor",
            "listIndex=$targetIndex focus=${displayFocusPosition()} " +
                    "viewport=${layoutInfo.viewportSize.height} itemOffset=${item?.offset} " +
                    "itemSize=${item?.size} errorPx=$finalDelta"
        )
    }

    fun requestCurrentItemAlignment(animate: Boolean = true) {
        scope.launch { alignCurrentItemToFocus(animate) }
    }

    BackHandler(enabled = isUserScrolling.value) {
        isUserScrolling.value = false
        requestCurrentItemAlignment()
        onPositionReset()
    }

    LaunchedEffect(
        displayLyrics.value,
        currentItemIndex.value,
        settings.value.highlightPosition,
        settings.value.translationVisible,
        settings.value.hideMainWhenTranslationHidden,
    ) {
        withFrameNanos { }
        alignCurrentItemToFocus(
            animate = displayLyrics.value.isNotEmpty() &&
                    currentItemIndex.value > 0 &&
                    !isBeforeFirstLine.value,
        )
    }

    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress to isUserScrollEnable() }
            .debounce(5000)
            .collectLatest { pair ->
                val (isDragging, isScrolling) = pair
                if (!isActive || isDragging || !isScrolling) return@collectLatest

                isUserScrolling.value = false
                alignCurrentItemToFocus(animate = true)
                onPositionReset()
            }
    }

    val context = remember {
        LyricContext(
            currentTime = { currentTime() + settings.value.timeOffset },
            currentIndex = { currentItemIndex.value },
            isBeforeFirstLine = { isBeforeFirstLine.value },
            openingProgress = { openingProgress.value },
            isUserScrolling = { isUserScrolling.value },
            screenConstraints = screenConstraints,
            textMeasurer = textMeasurer,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val highlightPosition = displayFocusPosition()
        val topPadding = density.run { (screenConstraints.maxHeight * highlightPosition).toDp() }
        val bottomPadding = density.run {
            (screenConstraints.maxHeight * (1f - highlightPosition)).toDp()
        }
        val highlightClearArea = 320.dp
        val topFade = (topPadding - highlightClearArea).coerceAtLeast(0.dp)
        val bottomFade = (bottomPadding - highlightClearArea).coerceAtLeast(0.dp)

        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .edgeTransparent(top = topFade, bottom = bottomFade),
            userScrollEnabled = true,
        ) {
            item(key = "LYRIC_TOP_SPACER", contentType = "LYRIC_SPACER") {
                Spacer(Modifier.height(topPadding))
            }

            if (displayLyrics.value.isEmpty()) {
                item(key = "EMPTY_TIPS") {
                    val item = remember {
                        LyricItem.NormalLyric(
                            key = "0",
                            content = "暂无歌词",
                            time = 0L
                        )
                    }

                    LyricContentNormal(
                        lyric = item,
                        index = context.currentIndex(),
                        modifier = Modifier,
                        settings = settings.value,
                        context = context,
                        onLongClick = { if (isUserClickEnable()) onItemLongClick(item) },
                        onClick = { }
                    )
                }
            } else {
                itemsIndexed(
                    items = displayLyrics.value,
                    key = { index, item -> "LYRIC_ITEM_${index}_${item.key}" },
                    contentType = { _, _ -> LyricItem::class }
                ) { index, item ->
                    when (item) {
                        is LyricItem.NormalLyric -> LyricContentNormal(
                            lyric = item,
                            index = index,
                            modifier = Modifier,
                            settings = settings.value,
                            context = context,
                            onLongClick = { if (isUserClickEnable()) onItemLongClick(item) },
                            onClick = { if (isUserClickEnable()) onItemClick(item) }
                        )

                        is LyricItem.WordsLyric -> LyricContentWords(
                            lyric = item,
                            index = index,
                            modifier = Modifier,
                            settings = settings.value,
                            context = context,
                            onLongClick = { if (isUserClickEnable()) onItemLongClick(item) },
                            onClick = { if (isUserClickEnable()) onItemClick(item) }
                        )
                    }
                }
            }

            item(key = "LYRIC_BOTTOM_SPACER", contentType = "LYRIC_SPACER") {
                Spacer(Modifier.height(bottomPadding))
            }
        }

        val contentColor = remember { Color(0xFFFFFFFF) }
        val colors = ButtonDefaults.textButtonColors(
            backgroundColor = contentColor.copy(alpha = 0.15f),
            contentColor = contentColor
        )

        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .fillMaxWidth(),
            enter = fadeIn() + slideIn { IntOffset(0, 100) },
            exit = fadeOut() + slideOut { IntOffset(0, 100) },
            visible = isUserScrolling.value
        ) {
            TextButton(
                modifier = Modifier.wrapContentWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = colors,
                onClick = {
                    isUserScrolling.value = false
                    requestCurrentItemAlignment()
                    onPositionReset()
                }
            ) {
                Text(
                    text = "退出歌词滚动模式",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
