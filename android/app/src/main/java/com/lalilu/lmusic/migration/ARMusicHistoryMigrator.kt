package com.lalilu.lmusic.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.HistoryMutationCoordinator
import com.lalilu.lhistory.repository.HistoryDao

class ARMusicHistoryMigrator(
    private val historyDao: HistoryDao,
    private val mutationCoordinator: HistoryMutationCoordinator,
) {
    private val gson = Gson()
    private val historyListType = object : TypeToken<List<LHistory>>() {}.type

    suspend fun importFromBackup(
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

        val stats = mutationCoordinator.withMutation {
            historyDao.mergeHistories(histories)
        }
        val count = stats.inserted + stats.merged

        if (count > 0) imported["播放历史"] = count else skipped += "播放历史"
    }

    suspend fun importFromSourceDatabase(sourceContext: Context): Int {
        val dbFile = sourceContext.getDatabasePath("lmedia.db")
        if (!dbFile.exists() || !dbFile.canRead()) return 0

        val histories = runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT contentId, contentTitle, parentId, parentTitle, duration, repeatCount, startTime FROM m_history",
                    emptyArray()
                ).use { cursor ->
                    val rows = mutableListOf<LHistory>()
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
                        rows += history
                    }
                    rows
                }
            }
        }.getOrDefault(emptyList())
        val stats = mutationCoordinator.withMutation {
            historyDao.mergeHistories(histories)
        }
        return stats.inserted + stats.merged
    }

    fun exportForBackup(): JsonElement {
        return gson.toJsonTree(historyDao.getAllForBackup())
    }
}
