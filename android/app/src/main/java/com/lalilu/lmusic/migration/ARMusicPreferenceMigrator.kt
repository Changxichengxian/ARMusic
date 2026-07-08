package com.lalilu.lmusic.migration

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.lalilu.lplaylist.entity.LPlaylist
import com.lalilu.lplaylist.entity.sanitizePlaylists
import com.lalilu.lplaylist.repository.PlaylistKV

class ARMusicPreferenceMigrator(
    private val application: Application,
) {
    private val gson = Gson()
    private val playlistListType = object : TypeToken<List<LPlaylist>>() {}.type

    fun importFromInstalled(
        sourceContext: Context,
        sourcePackage: String,
        imported: MutableMap<String, Int>,
        skipped: MutableList<String>,
    ) {
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
            sourceName = "armusic_song_works",
            targetName = "armusic_song_works",
            title = "作品分类",
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
            return
        }

        val playlistCount = mergePlaylists(sourceKv["PLAYLIST"])
        if (playlistCount > 0) imported["歌单"] = playlistCount

        val kvCount = copyPrefs(
            values = sourceKv,
            target = application.getSharedPreferences("spUtils", Application.MODE_PRIVATE),
            overwrite = true,
            skipKeys = setOf("PLAYLIST"),
        )
        if (kvCount > 0) imported["播放器状态"] = kvCount
    }

    fun importFromBackup(
        prefsObject: JsonObject,
        imported: MutableMap<String, Int>,
        skipped: MutableList<String>,
    ) {
        for ((sourceName, element) in prefsObject.entrySet()) {
            val values = element.asJsonObject
            val targetName = when (sourceName) {
                in ARMusicMigrationPackages.sourcePackages, application.packageName -> application.packageName
                else -> sourceName
            }

            if (targetName == "spUtils") {
                val playlistCount = values.get("PLAYLIST")?.let { mergePlaylists(decodePreferenceValue(it)) } ?: 0
                if (playlistCount > 0) imported["歌单"] = playlistCount

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

    fun exportForBackup(): JsonObject {
        val result = JsonObject()
        listOf(
            application.packageName,
            "LMEDIA",
            "settings",
            "spUtils",
            "armusic_song_works",
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

    fun copyLegacyJsonSettings(root: JsonObject): Int {
        return copyJsonPrefs(
            values = root,
            target = application.getSharedPreferences(application.packageName, Application.MODE_PRIVATE),
            overwrite = true,
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

    private fun mergePlaylists(value: Any?): Int {
        val json = value as? String ?: return 0
        val sourcePlaylists = runCatching {
            gson.fromJson<List<LPlaylist>>(json, playlistListType)
        }.getOrNull().sanitizePlaylists()
        if (sourcePlaylists.isEmpty()) return 0

        val currentPlaylists = runCatching { PlaylistKV.playlistList.value }
            .getOrNull()
            .sanitizePlaylists()
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

    private fun prefsTitle(name: String): String = when (name) {
        application.packageName -> "基础设置"
        "LMEDIA" -> "媒体扫描目录"
        "settings" -> "歌词显示设置"
        "armusic_song_works" -> "作品分类"
        "armusic_song_groups" -> "同歌不同版本分组"
        else -> name
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
}
