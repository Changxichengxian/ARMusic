package com.lalilu.lmusic.agent

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** A foreground ADB broadcast reliably wakes the sync service on aggressive OEM power managers. */
class ARMusicAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, ARMusicAgentService::class.java).apply {
            intent.extras?.let { extras -> putExtras(extras) }
        }
        runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onSuccess {
            resultCode = Activity.RESULT_OK
            Log.i(LOG_TAG, "Foreground sync command delivered to the agent service")
        }.onFailure { error ->
            resultCode = Activity.RESULT_CANCELED
            resultData = error.message ?: error.javaClass.simpleName
            Log.e(LOG_TAG, "Unable to start the foreground sync service", error)
        }
    }

    private companion object {
        const val LOG_TAG = "ARMusicAgentReceiver"
    }
}
