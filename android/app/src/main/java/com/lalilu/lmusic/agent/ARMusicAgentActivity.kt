package com.lalilu.lmusic.agent

import android.app.Activity
import android.os.Bundle
import android.util.Log
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

        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        val path = intent?.getStringExtra(EXTRA_PATH)
        val resultPath = intent?.getStringExtra(EXTRA_RESULT_PATH)

        CoroutineScope(Dispatchers.Main).launch {
            val result = agentManager.execute(
                command = command,
                path = path,
                resultPath = resultPath,
            )
            Log.i(LOG_TAG, result.message)
            finish()
        }
    }

    companion object {
        const val ACTION_AGENT_COMMAND = "com.lalilu.lmusic.armusic.AGENT_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_PATH = "resultPath"
        private const val LOG_TAG = "ARMusicAgentActivity"
    }
}
