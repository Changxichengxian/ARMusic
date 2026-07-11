package com.lalilu.lmusic.migration

import android.app.Application
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ARMusicBackupCodec(
    private val application: Application,
    private val preferenceMigrator: ARMusicPreferenceMigrator,
    private val historyMigrator: ARMusicHistoryMigrator,
) {
    suspend fun importFromText(text: String): LMusicMigrationResult {
        if (text.isBlank()) {
            return LMusicMigrationResult(
                imported = emptyMap(),
                skipped = listOf("文件为空"),
                message = "这个备份文件是空的。"
            )
        }

        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return LMusicMigrationResult(
                imported = emptyMap(),
                skipped = listOf("格式不对"),
                message = "这个文件不是 ARMusic 能识别的 JSON 备份。"
            )

        val imported = linkedMapOf<String, Int>()
        val skipped = mutableListOf<String>()

        if (root.has("sharedPreferences")) {
            preferenceMigrator.importFromBackup(root.getAsJsonObject("sharedPreferences"), imported, skipped)
            historyMigrator.importFromBackup(root.get("histories"), imported, skipped)
        } else {
            val count = preferenceMigrator.copyLegacyJsonSettings(root)
            if (count > 0) imported["旧版设置备份"] = count else skipped += "旧版设置备份"
        }

        return LMusicMigrationResult(
            imported = imported,
            skipped = skipped.distinct(),
            message = buildMessage(imported, skipped),
        )
    }

    fun buildBackupRoot(): JsonObject {
        return JsonObject().apply {
            addProperty("format", BACKUP_FORMAT)
            addProperty("version", 1)
            addProperty("sourcePackage", application.packageName)
            addProperty("createdAt", System.currentTimeMillis())
            add("sharedPreferences", preferenceMigrator.exportForBackup())
            add("histories", historyMigrator.exportForBackup())
        }
    }

    fun buildMessage(imported: Map<String, Int>, skipped: List<String>): String {
        if (imported.isEmpty()) {
            return "没有迁移到私有数据。安卓大概率拦住了旧版应用的目录；歌曲文件里的标题、封面、歌词会通过重新扫描读取。"
        }

        val summary = imported.entries.joinToString("，") { "${it.key}${it.value}项" }
        val skippedText = skipped.distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("，", prefix = "；未迁移：")
            .orEmpty()
        return "迁移完成：$summary$skippedText"
    }

    private companion object {
        const val BACKUP_FORMAT = "armusic-backup-v1"
    }
}
