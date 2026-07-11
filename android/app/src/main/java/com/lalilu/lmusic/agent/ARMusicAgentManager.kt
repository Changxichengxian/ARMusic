package com.lalilu.lmusic.agent

import android.util.Log
import com.lalilu.lmusic.migration.LMusicMigrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class ARMusicAgentManager(
    private val files: ARMusicAgentFiles,
    private val libraryExporter: ARMusicAgentLibraryExporter,
    private val bundleImporter: ARMusicAgentBundleImporter,
    private val historyManager: ARMusicAgentHistoryManager,
    private val migrationManager: LMusicMigrationManager,
    private val trackCommitter: ARMusicAgentTrackCommitter,
    private val wishlistManager: ARMusicAgentWishlistManager,
    private val playlistManager: ARMusicAgentPlaylistManager,
) {
    suspend fun execute(
        command: String,
        path: String?,
        resultPath: String?,
        commandId: String?,
    ): AgentCommandResult = withContext(Dispatchers.IO) {
        val normalizedCommand = command.trim().lowercase(Locale.ROOT).ifBlank { COMMAND_HELP }
        val finalResultPath = resultPath.orDefaultResultPath()
        val finalCommandId = commandId?.trim().orEmpty()

        val result = runCatching {
            when (normalizedCommand) {
                COMMAND_HELP -> helpResult()
                COMMAND_EXPORT_LIBRARY -> libraryExporter.exportLibrary(path.orDefaultLibraryPath())
                COMMAND_IMPORT_BUNDLE -> bundleImporter.importBundle(path.requirePath())
                COMMAND_IMPORT_HISTORY -> bundleImporter.importHistory(path.requirePath())
                COMMAND_IMPORT_WORKS -> bundleImporter.importWorks(path.requirePath())
                COMMAND_IMPORT_GROUPS -> bundleImporter.importGroups(path.requirePath())
                COMMAND_EXPORT_HISTORY -> historyManager.exportHistory(path.orDefaultHistoryPath())
                COMMAND_CLEAR_HISTORY -> historyManager.clearHistory(path.requirePath(), finalCommandId)
                COMMAND_RESTORE_RAW_HISTORY -> historyManager.restoreRawHistory(path.requirePath())
                COMMAND_COMMIT_TRACK -> trackCommitter.commit(path.requirePath())
                COMMAND_PREPARE_USB_FILE_REPLACE -> historyManager.prepareUsbFileReplace()
                COMMAND_VERIFY_TRACK -> trackCommitter.verify(path.requirePath())
                COMMAND_VERIFY_USB_FILE_REPLACE_LEASE ->
                    historyManager.verifyUsbFileReplaceLease(path.requirePath())
                COMMAND_CANCEL_USB_FILE_REPLACE_LEASE ->
                    historyManager.cancelUsbFileReplaceLease(path.requirePath())
                COMMAND_EXPORT_BACKUP -> exportBackup(path.orDefaultBackupPath())
                COMMAND_IMPORT_BACKUP -> importBackup(path.requirePath())
                COMMAND_EXPORT_WISHLIST -> wishlistManager.exportWishlist(path.orDefaultWishlistPath())
                COMMAND_IMPORT_WISHLIST -> wishlistManager.importWishlist(path.requirePath())
                COMMAND_EXPORT_PLAYLISTS, COMMAND_EXPORT_PLAYLIST ->
                    playlistManager.exportPlaylists(path.orDefaultPlaylistsPath())
                COMMAND_IMPORT_PLAYLISTS, COMMAND_IMPORT_PLAYLIST ->
                    playlistManager.importPlaylists(path.requirePath())
                else -> AgentCommandResult(
                    ok = false,
                    command = normalizedCommand,
                    message = "Unknown command: $normalizedCommand",
                )
            }
        }.getOrElse { throwable ->
            AgentCommandResult(
                ok = false,
                command = normalizedCommand,
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
        }.copy(resultPath = finalResultPath, commandId = finalCommandId)

        files.writeResult(finalResultPath, result)
        Log.i(LOG_TAG, result.message)
        result
    }

    private suspend fun exportBackup(outputPath: String): AgentCommandResult {
        val safePath = files.requireAgentPath(outputPath).absolutePath
        val result = migrationManager.exportToFilePath(safePath)

        return AgentCommandResult(
            ok = true,
            command = COMMAND_EXPORT_BACKUP,
            message = result.message,
            outputPath = safePath,
        )
    }

    private suspend fun importBackup(inputPath: String): AgentCommandResult {
        val safePath = files.requireAgentPath(inputPath).absolutePath
        val result = migrationManager.importFromFilePath(safePath)

        return AgentCommandResult(
            ok = result.isSuccess,
            command = COMMAND_IMPORT_BACKUP,
            message = result.message,
            skipped = result.skipped.size,
        )
    }

    private fun helpResult(): AgentCommandResult {
        val path = files.defaultLibraryPath()
        return AgentCommandResult(
            ok = true,
            command = COMMAND_HELP,
            message = "ARMusic agent commands: export_library, export_history, export_wishlist, import_wishlist, export_playlists, import_playlists, import_bundle, import_history, import_works, import_groups, clear_history, restore_raw_history, commit_track, verify_track, prepare_usb_file_replace, verify_usb_file_replace_lease, cancel_usb_file_replace_lease, export_backup, import_backup. Default agent dir: ${files.agentDir().absolutePath}",
            outputPath = path,
        )
    }

    private fun String?.requirePath(): String {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) error("Missing path")
        return value
    }

    private fun String?.orDefaultLibraryPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: files.defaultLibraryPath()
    }

    private fun String?.orDefaultBackupPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: files.defaultBackupPath()
    }

    private fun String?.orDefaultHistoryPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: files.defaultHistoryPath()
    }

    private fun String?.orDefaultWishlistPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: files.defaultWishlistPath()
    }

    private fun String?.orDefaultPlaylistsPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: files.defaultPlaylistsPath()
    }

    private fun String?.orDefaultResultPath(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: files.defaultResultPath()
    }

    companion object {
        const val COMMAND_HELP = "help"
        const val COMMAND_EXPORT_LIBRARY = "export_library"
        const val COMMAND_IMPORT_BUNDLE = "import_bundle"
        const val COMMAND_IMPORT_HISTORY = "import_history"
        const val COMMAND_IMPORT_WORKS = "import_works"
        const val COMMAND_IMPORT_GROUPS = "import_groups"
        const val COMMAND_EXPORT_HISTORY = "export_history"
        const val COMMAND_CLEAR_HISTORY = "clear_history"
        const val COMMAND_RESTORE_RAW_HISTORY = "restore_raw_history"
        const val COMMAND_COMMIT_TRACK = "commit_track"
        const val COMMAND_PREPARE_USB_FILE_REPLACE = "prepare_usb_file_replace"
        const val COMMAND_VERIFY_TRACK = "verify_track"
        const val COMMAND_VERIFY_USB_FILE_REPLACE_LEASE = "verify_usb_file_replace_lease"
        const val COMMAND_CANCEL_USB_FILE_REPLACE_LEASE = "cancel_usb_file_replace_lease"
        const val COMMAND_EXPORT_BACKUP = "export_backup"
        const val COMMAND_IMPORT_BACKUP = "import_backup"
        const val COMMAND_EXPORT_WISHLIST = "export_wishlist"
        const val COMMAND_IMPORT_WISHLIST = "import_wishlist"
        const val COMMAND_EXPORT_PLAYLISTS = "export_playlists"
        const val COMMAND_IMPORT_PLAYLISTS = "import_playlists"
        const val COMMAND_EXPORT_PLAYLIST = "export_playlist"
        const val COMMAND_IMPORT_PLAYLIST = "import_playlist"

        private const val LOG_TAG = "ARMusicAgent"
    }
}
