package com.lalilu.lmusic.compose.component.playing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.funny.data_saver.core.DataSaverMutableState
import com.lalilu.R
import com.lalilu.component.extension.enableFor
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmusic.compose.screen.playing.lyric.LyricSettings
import com.lalilu.lplayer.MPlayer
import org.koin.compose.koinInject
import org.koin.core.qualifier.named


@Composable
fun PlayingToolbar(
    modifier: Modifier = Modifier,
    isUserTouchEnable: () -> Boolean = { false },
    isItemPlaying: (mediaId: String) -> Boolean = { false },
    isExtraVisible: () -> Boolean = { true },
    onClick: () -> Unit = {},
    fixContent: @Composable RowScope.() -> Unit = {},
    extraContent: @Composable AnimatedVisibilityScope.() -> Unit = {}
) {
    val songWorkStore: SongWorkStore = koinInject()
    val lyricSettings: DataSaverMutableState<LyricSettings> = koinInject(named("LyricSettings"))
    val metadata = MPlayer.currentMediaMetadata
    val mediaId = MPlayer.currentMediaItem?.mediaId
    val workVersion by songWorkStore.changes.collectAsState()
    val originalSong = remember(mediaId, workVersion) {
        mediaId?.let { LMedia.get<LSong>(it) }
    }
    val originalTitle = originalSong?.name
    val metadataTitle = metadata?.title?.toString()
    val lyricTitle = metadataTitle
        ?.takeIf { it.isNotBlank() && it != originalTitle }
    val displayTitle = lyricTitle ?: originalTitle ?: metadataTitle
    val displaySubTitle = originalSong?.let { song ->
        songWorkStore.getWork(song)
            .ifBlank { song.metadata.artist }
            .ifBlank { song.metadata.album }
    } ?: metadata?.subtitle?.toString()
    val defaultSloganStr = stringResource(id = R.string.default_slogan)

    val enter = remember {
        fadeIn(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + expandHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + slideInHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) { it / 2 }
    }
    val exit = remember {
        fadeOut(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + shrinkHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + slideOutHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) { it / 2 }
    }

    Row(
        modifier = modifier
            .enableFor(isUserTouchEnable) {
                clickable(
                    onClick = { if (isUserTouchEnable()) onClick() },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                )
            }
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(start = 25.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayingHeader(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            title = { displayTitle?.takeIf(String::isNotBlank) ?: defaultSloganStr },
            subTitle = { displaySubTitle?.takeIf(String::isNotBlank) ?: defaultSloganStr },
            isPlaying = { MPlayer.isPlaying },
            titleStyle = {
                if (lyricTitle == null) {
                    MaterialTheme.typography.subtitle1
                } else {
                    MaterialTheme.typography.subtitle1.copy(
                        fontFamily = lyricSettings.value.mainTextStyle.fontFamily,
                        fontWeight = lyricSettings.value.mainTextStyle.fontWeight,
                    )
                }
            }
        )

        fixContent()

        AnimatedVisibility(
            visible = isExtraVisible(),
            enter = enter,
            exit = exit,
            content = extraContent
        )
    }
}
