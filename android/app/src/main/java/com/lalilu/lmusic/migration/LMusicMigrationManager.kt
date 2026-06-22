package com.lalilu.lmusic.migration

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.repository.HistoryDao
import com.lalilu.lplaylist.entity.LPlaylist
import com.lalilu.lplaylist.repository.PlaylistKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val historyDao: HistoryDao,
) {
    private val gson = Gson()
    private val playlistListType = object : TypeToken<List<LPlaylist>>() {}.type
    private val historyListType = object : TypeToken<List<LHistory>>() {}.type

    suspend fun migrateFromInstalledLmusic(): LMusicMigrationResult = withContext(Dispatchers.IO) {
        if (application.packageName in SOURCE_PACKAGES) {
            return@withContext LMusicMigrationResult(
                imported = emptyMap(),
                skipped = listOf("当前就是 LMusic，不需要迁移"),
                message = "当前包名还是 LMusic，不会对自己做迁移。"
            )
        }

        val source = findInstalledSource() ?: return@withContext LMusicMigrationResult(
            imported = emptyMap(),
            skipped = listOf("没有找到旧 LMusic"),
            message = "手机上没有找到旧 LMusic。"
        )
        val sourcePackage = source.first
        val sourceContext = source.second

        val imported = linkedMapOf<String, Int>()
        val skipped = mutableListOf<String>()

        importPrefsFromInstalled(
            sourceContext = sourceContext,
            sourceName = sourcePackage,
            targetName = application.packageName,
            title = "基础设置",
            imported = imported,
            skipped = skipped,
        )
        importPrefsFromInstalled(
            sourceContext = sourceContext,
            sourceName = "LMEDIA",
            targetName = "LMEDIA",
            title = "媒体扫描目录",
            imported = imported,
            skipped = skipped,
        )
        importPrefsFromInstalled(
            sourceContext = sourceContext,
            sourceName = "settings",
            targetName = "settings",
            title = "歌词显示设置",
            imported = imported,
            skipped = skipped,
        )
        importPrefsFromInstalled(
            sourceContext = sourceContext,
            sourceName = "armusic_song_groups",
            targetName = "armusic_song_groups",
            title = "同歌不同版本分组",
            imported = imported,
            skipped = skipped,
        )

        val sourceKv = readPrefs(sourceContext, "spUtils")
        if (sourceKv.isNullOrEmpty()) {
            skipped += "歌单和播放器状态"
        } else {
            val playlistCount = mergePlaylists(sourceKv["PLAYLIST"])
            if (playlistCount > 0) imported["歌单/收藏"] = playlistCount

            val kvCount = copyPrefs(
                values = sourceKv,
                target = application.getSharedPreferences("spUtils", Application.MODE_PRIVATE),
                overwrite = true,
                skipKeys = setOf("PLAYLIST"),
            )
            if (kvCount > 0) imported["播放器状态"] = kvCount
        }

        val historyCount = importHistoryFromSourceDatabase(sourceContext)
        if (historyCount > 0) imported["播放历史"] = historyCount else skipped += "播放历史"

        LMusicMigrationResult(
            imported = imported,
            skipped = skipped.distinct(),
            message = buildMessage(imported, skipped),
        )
    }

    suspend fun importFromUri(uri: Uri): LMusicMigrationResult = withContext(Dispatchers.IO) {
        val text = application.contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { it.readText() }
        }.orEmpty()

        if (text.isBlank()) {
            return@withContext LMusicMigrationResult(
                imported = emptyMap(),
                skipped = listOf("文件为空"),
                message = "这个备份文件是空的。"
            )
        }

        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return@withContext LMusicMigrationResult(
                imported = emptyMap(),
                skipped = listOf("格式不对"),
                message = "这个文件不是 ARMusic 能识别的 JSON 备份。"
            )

        val imported = linkedMapOf<String, Int>()
        val skipped = mutableListOf<String>()

        if (root.has("sharedPreferences")) {
            importSharedPreferencesObject(root.getAsJsonObject("sharedPreferences"), imported, skipped)
            importHistories(root.get("histories"), imported, skipped)
        } else {
            val count = copyJsonPrefs(
                values = root,
                target = application.getSharedPreferences(application.packageName, Application.MODE_PRIVATE),
                overwrite = true,
            )
            if (count > 0) imported["旧版设置备份"] = count else skipped += "旧版设置备份"
        }

        LMusicMigrationResult(
            imported = imported,
            skipped = skipped.distinct(),
            message = buildMessage(imported, skipped),
        )
    }

    suspend fun exportToUri(uri: Uri): LMusicMigrationResult = withContext(Dispatchers.IO) {
        val root = JsonObject().apply {
            addProperty("format", BACKUP_FORMAT)
            addProperty("version", 1)
            addProperty("sourcePackage", application.packageName)
            addProperty("createdAt", System.currentTimeMillis())
            add("sharedPreferences", exportSharedPreferences())
            add("histories", gson.toJsonTree(historyDao.getAllForBackup()))
        }

        application.contentResolver.openOutputStream(uri)?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                gson.toJson(root, writer)
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

    private fun importPrefsFromInstalled(
        sourceContext: Context,
        sourceName: String,
        targetName: String,
        title: String,
        imported: MutableMap<String, Int>,
        skipped: MutableList<String>,
    ) {
        val values = readPrefs(sourceContext, sourceName)
        if (values.isNullOrEmpty()) {
            skipped += title
            return
        }

        val count = copyPrefs(
            values = values,
            target = application.getSharedPreferences(targetName, Application.MODE_PRIVATE),
            overwrite = true,
        )
        if (count > 0) imported[title] = count else skipped += title
    }

    private fun readPrefs(context: Context, name: String): Map<String, *>? = runCatching {
        context.getSharedPreferences(name, Application.MODE_PRIVATE).all
    }.getOrNull()

    private fun findInstalledSource(): Pair<String, Context>? {
        for (sourcePackage in SOURCE_PACKAGES) {
            if (sourcePackage == application.packageName) continue
            val sourceContext = runCatching {
                application.createPackageContext(sourcePackage, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull() ?: continue
            return sourcePackage to sourceContext
        }
        return null
    }

    private fun copyPrefs(
        values: Map<String, *>,
        target: SharedPreferences,
        overwrite: Boolean,
        skipKeys: Set<String> = emptySet(),
    ): Int {
        var count = 0
        val editor = target.edit()

        for ((key, value) in values) {
            if (key in skipKeys) continue
            if (!overwrite && target.contains(key)) continue
            if (putPreferenceValue(editor, key, value)) count += 1
        }

        editor.commit()
        return count
    }

    private fun copyJsonPrefs(
        values: JsonObject,
        target: SharedPreferences,
        overwrite: Boolean,
        skipKeys: Set<String> = emptySet(),
    ): Int {
        var count = 0
        val editor = target.edit()

        for ((key, element) in values.entrySet()) {
            if (key in skipKeys) continue
            if (!overwrite && target.contains(key)) continue
            val value = decodePreferenceValue(element)
            if (putPreferenceValue(editor, key, value)) count += 1
        }

        editor.commit()
        return count
    }

    private fun putPreferenceValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?,
    ): Boolean {
        when (value) {
            null -> return false
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Double -> {
                if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                    editor.putInt(key, value.toInt())
                } else if (value % 1.0 == 0.0) {
                    editor.putLong(key, value.toLong())
                } else {
                    editor.putFloat(key, value.toFloat())
                }
            }
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.mapNotNull { it?.toString() }.toSet())
            is List<*> -> editor.putStringSet(key, value.mapNotNull { it?.toString() }.toSet())
            else -> editor.putString(key, gson.toJson(value))
        }
        return true
    }

    private fun importSharedPreferencesObject(
        prefsObject: JsonObject,
        imported: MutableMap<String, Int>,
        skipped: MutableList<String>,
    ) {
        for ((sourceName, element) in prefsObject.entrySet()) {
            val values = element.asJsonObject
            val targetName = when (sourceName) {
                in SOURCE_PACKAGES, application.packageName -> application.packageName
                else -> sourceName
            }

            if (targetName == "spUtils") {
                val playlistCount = values.get("PLAYLIST")?.let { mergePlaylists(decodePreferenceValue(it)) } ?: 0
                if (playlistCount > 0) imported["歌单/收藏"] = playlistCount

                val kvCount = copyJsonPrefs(
                    values = values,
                    target = application.getSharedPreferences("spUtils", Application.MODE_PRIVATE),
                    overwrite = true,
                    skipKeys = setOf("PLAYLIST"),
                )
                if (kvCount > 0) imported["播放器状态"] = kvCount
                if (playlistCount == 0 && kvCount == 0) skipped += "歌单和播放器状态"
                continue
            }

            val count = copyJsonPrefs(
                values = values,
                target = application.getSharedPreferences(targetName, Application.MODE_PRIVATE),
                overwrite = true,
            )
            if (count > 0) imported[prefsTitle(targetName)] = count else skipped += prefsTitle(targetName)
        }
    }

    private fun mergePlaylists(value: Any?): Int {
        val json = value as? String ?: return 0
        val sourcePlaylists = runCatching {
            gson.fromJson<List<LPlaylist>>(json, playlistListType)
        }.getOrNull().orEmpty()
        if (sourcePlaylists.isEmpty()) return 0

        val currentPlaylists = PlaylistKV.playlistList.value.orEmpty()
        val currentById = currentPlaylists.associateBy { it.id }
        val sourceIds = sourcePlaylists.map { it.id }.toHashSet()
        val merged = sourcePlaylists.map { source ->
            val current = currentById[source.id] ?: return@map source
            source.copy(
                mediaIds = (source.mediaIds + current.mediaIds).distinct(),
                modifyTime = maxOf(source.modifyTime, current.modifyTime),
            )
        } + currentPlaylists.filter { it.id !in sourceIds }

        PlaylistKV.playlistList.value = merged
        if (!PlaylistKV.playlistList.autoSave) PlaylistKV.playlistList.save()
        return sourcePlaylists.size
    }

    private fun importHistories(
        element: JsonElement?,
        imported: MutableMap<String, Int>,
        skipped: MutableList<String>,
    ) {
        if (element == null || element.isJsonNull) {
            skipped += "播放历史"
            return
        }

        val histories = runCatching {
            gson.fromJson<List<LHistory>>(element, historyListType)
        }.getOrNull().orEmpty()

        val count = histories
            .filter { it.contentId.isNotBlank() && it.startTime > 0L }
            .count { history ->
                if (historyDao.countSimilar(history.contentId, history.startTime, history.duration) > 0) {
                    false
                } else {
                    historyDao.save(history.copy(id = 0L))
                    true
                }
            }

        if (count > 0) imported["播放历史"] = count else skipped += "播放历史"
    }

    private fun importHistoryFromSourceDatabase(sourceContext: Context): Int {
        val dbFile = sourceContext.getDatabasePath("lmedia.db")
        if (!dbFile.exists() || !dbFile.canRead()) return 0

        return runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT contentId, contentTitle, parentId, parentTitle, duration, repeatCount, startTime FROM m_history",
                    emptyArray()
                ).use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        val history = LHistory(
                            contentId = cursor.getString(0).orEmpty(),
                            contentTitle = cursor.getString(1).orEmpty(),
                            parentId = cursor.getString(2).orEmpty(),
                            parentTitle = cursor.getString(3).orEmpty(),
                            duration = cursor.getLong(4),
                            repeatCount = cursor.getInt(5),
                            startTime = cursor.getLong(6),
                        )
                        if (history.contentId.isBlank()) continue
                        if (historyDao.countSimilar(history.contentId, history.startTime, history.duration) > 0) continue
                        historyDao.save(history)
                        count += 1
                    }
                    count
                }
            }
        }.getOrDefault(0)
    }

    private fun exportSharedPreferences(): JsonObject {
        val result = JsonObject()
        listOf(
            application.packageName,
            "LMEDIA",
            "settings",
            "spUtils",
            "armusic_song_groups",
        ).forEach { name ->
            val prefs = application.getSharedPreferences(name, Application.MODE_PRIVATE)
            val item = JsonObject()
            for ((key, value) in prefs.all) {
                item.add(key, encodePreferenceValue(value))
            }
            result.add(name, item)
        }
        return result
    }

    private fun encodePreferenceValue(value: Any?): JsonObject = JsonObject().apply {
        when (value) {
            is Int -> {
                addProperty("type", "int")
                addProperty("value", value)
            }
            is Long -> {
                addProperty("type", "long")
                addProperty("value", value)
            }
            is Float -> {
                addProperty("type", "float")
                addProperty("value", value)
            }
            is Boolean -> {
                addProperty("type", "boolean")
                addProperty("value", value)
            }
            is String -> {
                addProperty("type", "string")
                addProperty("value", value)
            }
            is Set<*> -> {
                addProperty("type", "string_set")
                add("value", gson.toJsonTree(value.mapNotNull { it?.toString() }))
            }
            else -> {
                addProperty("type", "string")
                addProperty("value", value?.toString().orEmpty())
            }
        }
    }

    private fun decodePreferenceValue(element: JsonElement): Any? {
        if (!element.isJsonObject || !element.asJsonObject.has("type")) return decodeRawValue(element)

        val item = element.asJsonObject
        val value = item.get("value")
        return when (item.get("type").asString) {
            "int" -> value.asInt
            "long" -> value.asLong
            "float" -> value.asFloat
            "boolean" -> value.asBoolean
            "string" -> value.takeUnless { it.isJsonNull }?.asString
            "string_set" -> value.asJsonArray.map { it.asString }.toSet()
            else -> decodeRawValue(value)
        }
    }

    private fun decodeRawValue(element: JsonElement): Any? {
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            if (primitive.isBoolean) return primitive.asBoolean
            if (primitive.isString) return primitive.asString
            if (primitive.isNumber) {
                val number = primitive.asDouble
                return if (number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
                    number.toInt()
                } else if (number % 1.0 == 0.0) {
                    number.toLong()
                } else {
                    number
                }
            }
        }
        if (element.isJsonArray) return element.asJsonArray.map { it.asString }.toSet()
        return element.toString()
    }

    private fun buildMessage(imported: Map<String, Int>, skipped: List<String>): String {
        if (imported.isEmpty()) {
            return "没有迁移到私有数据。安卓大概率拦住了旧 LMusic 的目录；歌曲文件里的标题、封面、歌词会通过重新扫描读取。"
        }

        val summary = imported.entries.joinToString("，") { "${it.key}${it.value}项" }
        val skippedText = skipped.distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("，", prefix = "；未迁移：")
            .orEmpty()
        return "迁移完成：$summary$skippedText"
    }

    private fun prefsTitle(name: String): String = when (name) {
        application.packageName -> "基础设置"
        "LMEDIA" -> "媒体扫描目录"
        "settings" -> "歌词显示设置"
        "armusic_song_groups" -> "同歌不同版本分组"
        else -> name
    }

    companion object {
        private val SOURCE_PACKAGES = listOf(
            "com.lalilu.lmusic",
            "com.lalilu.lmusic.alpha",
            "com.lalilu.lmusic.beta",
            "com.lalilu.lmusic.debug",
        )
        private const val BACKUP_FORMAT = "armusic-backup-v1"
    }
}
