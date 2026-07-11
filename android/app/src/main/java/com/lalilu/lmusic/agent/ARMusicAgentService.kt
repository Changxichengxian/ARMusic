package com.lalilu.lmusic.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.lalilu.R
import com.lalilu.lmusic.ARMusicDeferredStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.java.KoinJavaComponent

/** Runs USB sync commands without depending on an unlocked, visible activity. */
class ARMusicAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val agentManager: ARMusicAgentManager by lazy {
        KoinJavaComponent.get(ARMusicAgentManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        ARMusicDeferredStartup.start(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSyncForeground()
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        val path = intent?.getStringExtra(EXTRA_PATH)
        val resultPath = intent?.getStringExtra(EXTRA_RESULT_PATH)
        val commandId = intent?.getStringExtra(EXTRA_COMMAND_ID)

        scope.launch {
            try {
                commandMutex.withLock {
                    val wakeLock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                        .apply { acquire(COMMAND_WAKE_LOCK_TIMEOUT_MS) }
                    try {
                        Log.i(LOG_TAG, "Background agent command started: $command")
                        val result = agentManager.execute(
                            command = command,
                            path = path,
                            resultPath = resultPath,
                            commandId = commandId,
                        )
                        Log.i(LOG_TAG, "Background agent command finished: $command; ok=${result.ok}")
                    } finally {
                        if (wakeLock.isHeld) wakeLock.release()
                    }
                }
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Background agent command crashed: $command", error)
            } finally {
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(LOG_TAG, "Android stopped the data-sync foreground service after its time limit")
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSyncForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.armusic_usb_sync_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.armusic_usb_sync_in_progress)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_music_line)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.armusic_usb_sync_in_progress))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_PATH = "resultPath"
        const val EXTRA_COMMAND_ID = "commandId"
        const val LOG_TAG = "ARMusicAgentService"
        const val WAKE_LOCK_TAG = "ARMusic:UsbSync"
        const val COMMAND_WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 1000L
        const val NOTIFICATION_CHANNEL_ID = "armusic_usb_sync"
        const val NOTIFICATION_ID = 1_503_001
    }
}
