package com.lalilu.lmusic.agent

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.lalilu.BuildConfig
import com.lalilu.lmusic.ARMusicDeferredStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent

class ARMusicAgentActivity : Activity() {
    private val agentManager: ARMusicAgentManager by lazy {
        KoinJavaComponent.get(ARMusicAgentManager::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(LOG_TAG, "Agent activity created; starting deferred app initialization")
        ARMusicDeferredStartup.start(application)
        Log.i(LOG_TAG, "Deferred app initialization requested")

        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        val path = intent?.getStringExtra(EXTRA_PATH)
        val resultPath = intent?.getStringExtra(EXTRA_RESULT_PATH)
        val commandId = intent?.getStringExtra(EXTRA_COMMAND_ID)

        CoroutineScope(Dispatchers.Main).launch {
            Log.i(LOG_TAG, "Resolving agent command manager for $command")
            val manager = agentManager
            Log.i(LOG_TAG, "Agent command manager resolved for $command")
            val result = manager.execute(
                command = command,
                path = path,
                resultPath = resultPath,
                commandId = commandId,
            )
            Log.i(LOG_TAG, "Agent command finished: $command; ok=${result.ok}")
            Log.i(LOG_TAG, result.message)
            finish()
        }
    }

    companion object {
        val ACTION_AGENT_COMMAND = "${BuildConfig.APPLICATION_ID}.AGENT_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_PATH = "resultPath"
        const val EXTRA_COMMAND_ID = "commandId"
        private const val LOG_TAG = "ARMusicAgentActivity"
    }
}
