package com.lalilu.lmusic.agent

import android.app.Application
import android.util.Log
import java.io.File

class ARMusicAgentFiles(
    private val application: Application,
) {
    fun defaultLibraryPath(): String {
        return File(agentDir(), DEFAULT_LIBRARY_FILE).absolutePath
    }

    fun defaultBackupPath(): String {
        return File(agentDir(), DEFAULT_BACKUP_FILE).absolutePath
    }

    fun defaultResultPath(): String {
        return File(agentDir(), DEFAULT_RESULT_FILE).absolutePath
    }

    fun readTextFile(path: String): String {
        return File(path).readText(Charsets.UTF_8)
    }

    fun writeTextFile(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    fun writeResult(path: String, result: AgentCommandResult) {
        runCatching {
            writeTextFile(path, result.toJson().toString(2))
        }.onFailure {
            Log.e(LOG_TAG, "Failed to write result file: $path", it)
        }
    }

    fun agentDir(): File {
        return (application.getExternalFilesDir("agent") ?: File(application.filesDir, "agent"))
            .apply { mkdirs() }
    }

    private companion object {
        const val LOG_TAG = "ARMusicAgent"
        const val DEFAULT_LIBRARY_FILE = "armusic-library.json"
        const val DEFAULT_BACKUP_FILE = "armusic-backup.json"
        const val DEFAULT_RESULT_FILE = "armusic-agent-result.json"
    }
}
