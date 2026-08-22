package com.lalilu.lmusic.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lalilu.BuildConfig
import com.lalilu.lplayer.MPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only capture clock used by the offline promo renderer.
 *
 * It corrects the real player position after navigation or theme changes without
 * opening an Activity or showing a foreground-service notification in the film.
 */
class ARMusicCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
        if (positionMs < 0L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                MPlayer.correctCapturePosition(positionMs)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val EXTRA_POSITION_MS = "positionMs"
    }
}
