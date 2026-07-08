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
    private val migrationManager: LMusicMigrationManager,
) {
    suspend fun execute(
        command: String,
        path: String?,
        resultPath: String?,
    ): AgentCommandResult = withContext(Dispatchers.IO) {
        val normalizedCommand = command.trim().lowercase(Locale.ROOT).ifBlank { COMMAND_HELP }
        val finalResultPath = resultPath.orDefaultResultPath()

        val result = runCatching {
            when (normalizedCommand) {
                COMMAND_HELP -> helpResult()
                COMMAND_EXPORT_LIBRARY -> libraryExporter.exportLibrary(path.orDefaultLibraryPath())
                COMMAND_IMPORT_BUNDLE -> bundleImporter.importBundle(path.requirePath())
                COMMAND_IMPORT_HISTORY -> bundleImporter.importHistory(path.requirePath())
                COMMAND_IMPORT_WORKS -> bundleImporter.importWorks(path.requirePath())
                COMMAND_IMPORT_GROUPS -> bundleImporter.importGroups(path.requirePath())
                COMMAND_EXPORT_BACKUP -> exportBackup(path.orDefaultBackupPath())
                COMMAND_IMPORT_BACKUP -> importBackup(path.requirePath())
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
        }.copy(resultPath = finalResultPath)

        files.writeResult(finalResultPath, result)
        Log.i(LOG_TAG, result.message)
        result
    }

    private suspend fun exportBackup(outputPath: String): AgentCommandResult {
        val result = migrationManager.exportToFilePath(outputPath)

        return AgentCommandResult(
            ok = true,
            command = COMMAND_EXPORT_BACKUP,
            message = result.message,
            outputPath = outputPath,
        )
    }

    private suspend fun importBackup(inputPath: String): AgentCommandResult {
        val result = migrationManager.importFromFilePath(inputPath)

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
            message = "ARMusic agent commands: export_library, import_bundle, import_history, import_works, import_groups, export_backup, import_backup. Default agent dir: ${files.agentDir().absolutePath}",
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
        const val COMMAND_EXPORT_BACKUP = "export_backup"
        const val COMMAND_IMPORT_BACKUP = "import_backup"

        private const val LOG_TAG = "ARMusicAgent"
    }
}
