package com.lalilu.lmusic.compose

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.annotation.ExperimentalVoyagerApi
import cafe.adriel.voyager.jetpack.ProvideNavigatorLifecycleKMPSupport
import com.funny.data_saver.core.LocalDataSaver
import com.lalilu.component.base.LocalWindowSize
import com.lalilu.component.lumo.LumoTheme
import com.lalilu.lmusic.LMusicTheme
import com.lalilu.lmusic.compose.shell.ARMusicScaffold
import com.lalilu.lmusic.compose.screen.playing.PhoneLandscapePlayingLayout
import com.lalilu.lmusic.compose.screen.playing.LocalStartupPlayback
import com.lalilu.lmusic.compose.screen.playing.StartupPlayback
import com.lalilu.lmusic.compose.screen.playing.loadStartupPlayback
import com.lalilu.lplayer.MPlayer
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
object App {

    @OptIn(ExperimentalVoyagerApi::class)
    @Composable
    fun Content(activity: Activity, preparePlayback: Boolean, onFirstFrameReady: () -> Unit) {
        var ready by remember { mutableStateOf(!preparePlayback) }
        var playback by remember { mutableStateOf<StartupPlayback?>(null) }
        LaunchedEffect(Unit) {
            if (!preparePlayback) return@LaunchedEffect
            playback = withTimeoutOrNull(3500L) {
                MPlayer.awaitInitialQueue()
                MPlayer.currentMediaItem?.let { loadStartupPlayback(activity, it) }
            }
            ready = true
        }
        if (!ready) return

        val currentItem = MPlayer.currentMediaItem
        if (playback?.item != currentItem) SideEffect { playback = null }

        Environment(activity = activity) {
            CompositionLocalProvider(
                LocalStartupPlayback provides playback?.takeIf { it.item == currentItem }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ProvideNavigatorLifecycleKMPSupport {
                        ARMusicScaffold()
                    }
                    PhoneLandscapePlayingLayout()
                }
            }
        }
        if (preparePlayback) SideEffect(onFirstFrameReady)
    }

    @Composable
    fun Environment(activity: Activity, content: @Composable () -> Unit) {
        LMusicTheme {
            MaterialTheme {
                LumoTheme {
                    CompositionLocalProvider(
                        LocalWindowSize provides calculateWindowSizeClass(activity = activity),
                        LocalDataSaver provides koinInject(),
                        content = content
                    )
                }
            }
        }
    }
}
