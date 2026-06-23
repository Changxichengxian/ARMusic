package com.lalilu.lmusic.compose.screen.search.extensions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lalilu.RemixIcon
import com.lalilu.component.LazyGridContent
import com.lalilu.component.navigation.AppRouter
import com.lalilu.component.navigation.NavIntent
import com.lalilu.lalbum.component.AlbumCard
import com.lalilu.lalbum.screen.AlbumDetailScreen
import com.lalilu.lmedia.entity.LAlbum
import com.lalilu.lplayer.MPlayer
import com.lalilu.remixicon.Arrows
import com.lalilu.remixicon.Media
import com.lalilu.remixicon.arrows.arrowDownSLine
import com.lalilu.remixicon.arrows.arrowUpSLine
import com.lalilu.remixicon.media.music2Line

class SearchWorksResult(
    private val workLabel: () -> String = { "作品" },
    private val worksResult: () -> List<LAlbum>,
) : LazyGridContent {

    @Composable
    override fun register(): LazyGridScope.() -> Unit {
        val collapsed = remember { mutableStateOf(false) }

        return fun LazyGridScope.() {
            if (worksResult().isNotEmpty()) {
                stickyHeader(
                    key = "${this@SearchWorksResult::class.java.name}_Header",
                    contentType = "sticky"
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { collapsed.value = !collapsed.value }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = RemixIcon.Media.music2Line,
                            contentDescription = null,
                            tint = MaterialTheme.colors.onBackground
                        )

                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            text = "${workLabel()}搜索结果 (${worksResult().size})",
                            fontSize = 16.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colors.onBackground,
                            fontWeight = FontWeight.Bold
                        )

                        AnimatedContent(
                            targetState = collapsed.value,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = ""
                        ) { collapsedValue ->
                            Icon(
                                imageVector = if (collapsedValue) RemixIcon.Arrows.arrowDownSLine
                                else RemixIcon.Arrows.arrowUpSLine,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onBackground
                            )
                        }
                    }
                }
            }

            if (!collapsed.value) {
                items(
                    items = worksResult(),
                    key = { it.id },
                    contentType = { it::class.java },
                    span = { GridItemSpan(3) }
                ) { item ->
                    AlbumCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        album = { item },
                        isPlaying = { item.songs.any { MPlayer.isItemPlaying(it.id) } },
                        onClick = {
                            AppRouter.intent(
                                NavIntent.Push(
                                    AlbumDetailScreen(item.id)
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}
