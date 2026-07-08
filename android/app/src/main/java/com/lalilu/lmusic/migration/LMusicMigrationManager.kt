package com.lalilu.lmusic.migration

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

data class LMusicMigrationResult(
    val imported: Map<String, Int>,
    val skipped: List<String>,
    val message: String,
) {
    val isSuccess: Boolean = imported.values.sum() > 0
}

class LMusicMigrationManager(
    private val application: Application,
    private val preferenceMigrator: ARMusicPreferenceMigrator,
    private val historyMigrator: ARMusicHistoryMigrator,
    private val backupCodec: ARMusicBackupCodec,
) {
    suspend fun migrateFromInstalledLegacyApp(): LMusicMigrationResult = withContext(Dispatchers.IO) {
        if (application.packageName in ARMusicMigrationPackages.sourcePackages) {
            return@withContext LMusicMigrationResult(
                imported = emptyMap(),
                skipped = listOf("当前就是迁移来源，不需要迁移"),
                message = "当前包名属于旧版来源，不会对自己做迁移。"
            )
        }

        val source = findInstalledSource() ?: return@withContext LMusicMigrationResult(
            imported = emptyMap(),
            skipped = listOf("没有找到旧版应用"),
            message = "手机上没有找到旧版 LMusic。"
        )

        val imported = linkedMapOf<String, Int>()
        val skipped = mutableListOf<String>()

        preferenceMigrator.importFromInstalled(
            sourceContext = source.second,
            sourcePackage = source.first,
            imported = imported,
            skipped = skipped,
        )

        val historyCount = historyMigrator.importFromSourceDatabase(source.second)
        if (historyCount > 0) imported["播放历史"] = historyCount else skipped += "播放历史"

        LMusicMigrationResult(
            imported = imported,
            skipped = skipped.distinct(),
            message = backupCodec.buildMessage(imported, skipped),
        )
    }

    suspend fun importFromUri(uri: Uri): LMusicMigrationResult = withContext(Dispatchers.IO) {
        val text = application.contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { it.readText() }
        }.orEmpty()

        backupCodec.importFromText(text)
    }

    suspend fun importFromFilePath(path: String): LMusicMigrationResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return@withContext LMusicMigrationResult(
                imported = emptyMap(),
                skipped = listOf("文件不存在"),
                message = "没有找到这个备份文件。"
            )
        }

        backupCodec.importFromText(file.readText(Charsets.UTF_8))
    }

    suspend fun exportToUri(uri: Uri): LMusicMigrationResult = withContext(Dispatchers.IO) {
        val root = backupCodec.buildBackupRoot()

        application.contentResolver.openOutputStream(uri)?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write(root.toString())
            }
        } ?: return@withContext LMusicMigrationResult(
            imported = emptyMap(),
            skipped = listOf("无法写入文件"),
            message = "无法写入这个备份文件。"
        )

        LMusicMigrationResult(
            imported = mapOf("备份条目" to root.getAsJsonObject("sharedPreferences").size()),
            skipped = emptyList(),
            message = "ARMusic 备份已保存。"
        )
    }

    suspend fun exportToFilePath(path: String): LMusicMigrationResult = withContext(Dispatchers.IO) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val root = backupCodec.buildBackupRoot()
        file.writeText(root.toString(), Charsets.UTF_8)

        LMusicMigrationResult(
            imported = mapOf("备份条目" to root.getAsJsonObject("sharedPreferences").size()),
            skipped = emptyList(),
            message = "ARMusic 备份已保存。"
        )
    }

    private fun findInstalledSource(): Pair<String, Context>? {
        for (sourcePackage in ARMusicMigrationPackages.sourcePackages) {
            if (sourcePackage == application.packageName) continue
            val sourceContext = runCatching {
                application.createPackageContext(sourcePackage, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull() ?: continue
            return sourcePackage to sourceContext
        }
        return null
    }
}
