package com.lalilu.lmusic.compose.screen.playing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lplayer.MPlayer
import org.koin.compose.koinInject

@Composable
fun PlayingSmartCard(
    modifier: Modifier = Modifier,
) {
    val songWorkStore: SongWorkStore = koinInject()
    val currentPlaying = MPlayer.currentMediaItem
    val mediaId = currentPlaying?.mediaId
    val workVersion by songWorkStore.changes.collectAsState()
    val originalSong = remember(mediaId, workVersion) {
        mediaId?.let { LMedia.get<LSong>(it) }
    }
    val displayTitle = originalSong?.name
        ?: currentPlaying?.mediaMetadata?.title?.toString()
        ?: "Unknown"
    val displaySubTitle = originalSong?.let { song ->
        songWorkStore.getWork(song)
            .ifBlank { song.metadata.artist }
            .ifBlank { song.metadata.album }
    } ?: currentPlaying?.mediaMetadata?.subtitle?.toString()
        ?: "Unknown"

    Surface(modifier) {
        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                slideInVertically { -it } togetherWith slideOutVertically { it }
            },
            targetState = currentPlaying,
            label = ""
        ) { playing ->
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                    model = playing,
                    contentScale = ContentScale.Crop,
                    contentDescription = null
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            spacing = MarqueeSpacing(30.dp)
                        ),
                        text = displayTitle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground,
                        fontSize = 14.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            spacing = MarqueeSpacing(30.dp)
                        ),
                        text = displaySubTitle,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.onBackground.copy(0.6f),
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
