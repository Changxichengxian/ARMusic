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

    fun defaultHistoryPath(): String {
        return File(agentDir(), DEFAULT_HISTORY_FILE).absolutePath
    }

    fun defaultWishlistPath(): String {
        return File(agentDir(), DEFAULT_WISHLIST_FILE).absolutePath
    }

    fun defaultPlaylistsPath(): String {
        return File(agentDir(), DEFAULT_PLAYLISTS_FILE).absolutePath
    }

    fun historyClearBackupPath(timestamp: Long): String {
        return File(agentDir(), "history-before-clear-$timestamp.json").absolutePath
    }

    fun historyMergeImportPath(): String = File(agentDir(), "history-merged-from-desktop.json").absolutePath

    fun historyClearReceiptPath(): String = File(agentDir(), "history-clear-receipt.json").absolutePath

    fun markCommandConsumed(commandId: String): Boolean {
        require(commandId.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Invalid command id" }
        return File(agentDir(), "consumed-$commandId").createNewFile()
    }

    fun readTextFile(path: String): String {
        return requireAgentPath(path).readText(Charsets.UTF_8)
    }

    fun writeTextFile(path: String, text: String) {
        val file = requireAgentPath(path)
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

    fun requireAgentPath(path: String): File {
        val base = agentDir().canonicalFile
        val requested = File(path).let { file ->
            if (file.isAbsolute) file else File(base, path)
        }.canonicalFile
        require(requested.toPath().startsWith(base.toPath()) && requested != base) {
            "Agent path must stay inside ${base.absolutePath}"
        }
        return requested
    }

    private companion object {
        const val LOG_TAG = "ARMusicAgent"
        const val DEFAULT_LIBRARY_FILE = "armusic-library.json"
        const val DEFAULT_BACKUP_FILE = "armusic-backup.json"
        const val DEFAULT_RESULT_FILE = "armusic-agent-result.json"
        const val DEFAULT_HISTORY_FILE = "armusic-history.json"
        const val DEFAULT_WISHLIST_FILE = "armusic-wishlist.json"
        const val DEFAULT_PLAYLISTS_FILE = "armusic-playlists.json"
    }
}
