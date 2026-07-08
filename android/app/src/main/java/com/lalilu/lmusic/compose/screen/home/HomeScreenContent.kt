package com.lalilu.lmusic.compose.screen.home

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lalilu.common.ext.requestFor
import com.lalilu.component.LazyGridContent
import com.lalilu.component.base.smartBarPadding
import com.lalilu.lmusic.extension.DailyRecommend
import com.lalilu.lmusic.extension.EntryPanel
import com.lalilu.lmusic.extension.LatestPanel
import org.koin.core.qualifier.named

@Composable
fun HomeScreenContent(
    modifier: Modifier = Modifier,
) {
    val dailyRecommend = DailyRecommend.register()
    val entryPanel = EntryPanel.register()
    val latestPanel = LatestPanel.register()
    val historyPanel = remember {
        requestFor<LazyGridContent>(named("history_panel"))
    }?.register()

    LazyVerticalGrid(
        modifier = modifier,
        columns = GridCells.Fixed(12),
        contentPadding = WindowInsets.systemBars.asPaddingValues()
    ) {
        dailyRecommend(this)

        latestPanel(this)

        historyPanel?.invoke(this)

        entryPanel(this)

        smartBarPadding()
    }
}
