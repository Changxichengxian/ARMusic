package com.lalilu.lmusic.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.repository.HistoryDao

class ARMusicHistoryMigrator(
    private val historyDao: HistoryDao,
) {
    private val gson = Gson()
    private val historyListType = object : TypeToken<List<LHistory>>() {}.type

    fun importFromBackup(
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

    fun importFromSourceDatabase(sourceContext: Context): Int {
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

    fun exportForBackup(): JsonElement {
        return gson.toJsonTree(historyDao.getAllForBackup())
    }
}
