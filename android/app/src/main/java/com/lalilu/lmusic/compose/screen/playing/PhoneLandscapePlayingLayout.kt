package com.lalilu.lmusic.compose.screen.playing

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.IconButton
import androidx.compose.material.IconToggleButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.lalilu.R
import com.lalilu.component.base.LocalWindowSize
import com.lalilu.component.extension.rememberIsPad
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmusic.utils.coil.BlurTransformation
import com.lalilu.lplayer.MPlayer
import com.lalilu.lplayer.MPlayerKV
import com.lalilu.lplayer.action.PlayerAction
import com.lalilu.lplayer.extensions.PlayMode
import org.koin.compose.koinInject

@Composable
fun PhoneLandscapePlayingLayout(
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        val windowSize = LocalWindowSize.current
        val isPad by windowSize.rememberIsPad()
        val isLandscape = maxWidth > maxHeight ||
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isPad || !isLandscape) return@BoxWithConstraints

        val currentPlaying = MPlayer.currentMediaItem

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color.DarkGray)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {}
        ) {
            LandscapeBlurBackground(item = currentPlaying)

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LandscapeCover(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    item = currentPlaying
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    LandscapeSongDetail(item = currentPlaying)
                    Spacer(modifier = Modifier.weight(1f))
                    LandscapeControlPanel()
                }
            }
        }
    }
}

@Composable
private fun RowScope.LandscapeCover(
    modifier: Modifier = Modifier,
    item: MediaItem?,
) {
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .aspectRatio(1f),
            shape = RoundedCornerShape(10.dp),
            color = Color(0x55000000),
            elevation = 0.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.ic_music_line),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(color = Color.LightGray),
                    modifier = Modifier
                        .size(96.dp)
                        .align(Alignment.Center)
                )

                AnimatedContent(
                    targetState = item,
                    transitionSpec = {
                        fadeIn(tween(500)) togetherWith fadeOut(tween(300, 500))
                    },
                    label = ""
                ) { model ->
                    if (model != null) {
                        AsyncImage(
                            modifier = Modifier.fillMaxSize(),
                            model = model,
                            contentScale = ContentScale.Crop,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.LandscapeBlurBackground(
    item: MediaItem?,
) {
    val context = LocalContext.current
    val model = remember(item) {
        ImageRequest.Builder(context)
            .data(item)
            .size(500)
            .crossfade(true)
            .transformations(BlurTransformation(context, 25f, 4f))
            .build()
    }

    AnimatedContent(
        modifier = Modifier
            .fillMaxSize()
            .align(Alignment.Center),
        targetState = model,
        transitionSpec = {
            fadeIn(tween(500)) togetherWith fadeOut(tween(300, 500))
        },
        label = ""
    ) { imageModel ->
        AsyncImage(
            modifier = Modifier.fillMaxSize(),
            model = imageModel,
            contentScale = ContentScale.Crop,
            contentDescription = null
        )
    }

    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color(0x55000000))
    )
}

@Composable
private fun LandscapeSongDetail(
    item: MediaItem?,
    songWorkStore: SongWorkStore = koinInject(),
) {
    if (item == null) {
        Text(
            text = "歌曲读取失败",
            color = Color.White,
            fontSize = 24.sp
        )
        return
    }

    val workVersion by songWorkStore.changes.collectAsState()
    val song = remember(item.mediaId, workVersion) {
        LMedia.get<LSong>(item.mediaId)
    }
    val title = song?.name ?: item.mediaMetadata.title?.toString().orEmpty()
    val artist = song?.metadata?.artist ?: item.mediaMetadata.artist?.toString().orEmpty()
    val album = song?.metadata?.album ?: item.mediaMetadata.albumTitle?.toString().orEmpty()
    val work = song?.let { songWorkStore.getWork(it) }.orEmpty()

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title.ifBlank { "未知歌曲" },
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (artist.isNotBlank()) {
            Text(
                text = artist,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (album.isNotBlank()) {
            Text(
                text = album,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (work.isNotBlank()) {
            Text(
                text = work,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LandscapeControlPanel() {
    val isPlaying = remember { derivedStateOf { MPlayer.isPlaying } }
    val playModeName by MPlayerKV.playMode.flow().collectAsState(initial = MPlayerKV.playMode.value)
    val playMode = remember(playModeName) {
        runCatching { PlayMode.from(playModeName) }.getOrDefault(PlayMode.ListRecycle)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = { PlayerAction.SkipToPrevious.action() }) {
            Image(
                painter = painterResource(id = R.drawable.ic_skip_previous_line),
                contentDescription = "skip_back",
                modifier = Modifier.size(28.dp)
            )
        }
        IconToggleButton(
            checked = isPlaying.value,
            onCheckedChange = { PlayerAction.PlayOrPause.action() }
        ) {
            Image(
                painter = painterResource(
                    if (isPlaying.value) R.drawable.ic_pause_line else R.drawable.ic_play_line
                ),
                contentDescription = "play_pause",
                modifier = Modifier.size(28.dp)
            )
        }
        IconButton(onClick = { PlayerAction.SkipToNext.action() }) {
            Image(
                painter = painterResource(id = R.drawable.ic_skip_next_line),
                contentDescription = "skip_forward",
                modifier = Modifier.size(28.dp)
            )
        }
        IconButton(
            onClick = {
                val nextMode = when (playMode) {
                    PlayMode.ListRecycle -> PlayMode.RepeatOne
                    PlayMode.RepeatOne -> PlayMode.Shuffle
                    PlayMode.Shuffle -> PlayMode.ListRecycle
                }
                PlayerAction.SetPlayMode(nextMode).action()
            }
        ) {
            Image(
                painter = painterResource(
                    when (playMode) {
                        PlayMode.ListRecycle -> R.drawable.ic_order_play_line
                        PlayMode.RepeatOne -> R.drawable.ic_repeat_one_line
                        PlayMode.Shuffle -> R.drawable.ic_shuffle_line
                    }
                ),
                contentDescription = "play_mode",
                colorFilter = ColorFilter.tint(color = Color.White),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
