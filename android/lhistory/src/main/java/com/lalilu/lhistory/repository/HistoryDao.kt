package com.lalilu.lhistory.repository

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapInfo
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.lalilu.lhistory.entity.LHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(history: LHistory): Long

    @Update(entity = LHistory::class)
    fun update(vararg history: LHistory)

    @Query("UPDATE m_history SET duration = :duration, repeatCount = :repeatCount, startTime = :startTime WHERE id = :id;")
    fun updateHistory(id: Long, duration: Long, repeatCount: Int, startTime: Long)

    @Query("UPDATE m_history SET parentId = :parentId, parentTitle = :parentTitle WHERE contentId = :contentId;")
    fun updateParentForContentId(contentId: String, parentId: String, parentTitle: String)

    @Query("UPDATE m_history SET contentId = :newContentId, contentTitle = :newContentTitle WHERE contentId = :oldContentId;")
    fun relinkContentId(oldContentId: String, newContentId: String, newContentTitle: String): Int

    @Query("SELECT * FROM m_history WHERE contentId IN (:contentIds) ORDER BY startTime ASC, id ASC")
    fun getByContentIds(contentIds: List<String>): List<LHistory>

    @Transaction
    fun mergeKnownContentIdAliases(aliases: Map<String, String>): HistoryAliasMergeStats {
        val relevantIds = (aliases.keys + aliases.values)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (relevantIds.isEmpty()) return HistoryAliasMergeStats()
        val plan = planHistoryContentIdAliasMerge(getByContentIds(relevantIds), aliases)
        if (plan.updates.isNotEmpty()) update(*plan.updates.toTypedArray())
        if (plan.deletes.isNotEmpty()) delete(*plan.deletes.toTypedArray())
        return plan.stats
    }

    @Query("DELETE FROM m_history;")
    fun clear()

    @Transaction
    fun clearIfUnchanged(expected: List<LHistory>): Int {
        val current = getAllForBackup()
        if (current != expected) return -1
        clear()
        return current.size
    }

    /** Atomic merge without a schema migration or a destructive replace. */
    @Transaction
    fun mergeHistories(incoming: List<LHistory>): HistoryMergeStats {
        var inserted = 0
        var merged = 0
        var unchanged = 0
        var skipped = 0
        incoming.forEach { history ->
            if (history.contentId.isBlank() || history.startTime <= 0L) {
                skipped += 1
                return@forEach
            }

            val existing = getByContentAndStart(history.contentId, history.startTime)
            if (existing == null) {
                save(history.copy(id = 0L))
                inserted += 1
                return@forEach
            }

            val next = existing.copy(
                contentTitle = history.contentTitle.ifBlank { existing.contentTitle },
                parentId = history.parentId.ifBlank { existing.parentId },
                parentTitle = history.parentTitle.ifBlank { existing.parentTitle },
                duration = maxOf(existing.duration, history.duration),
                repeatCount = maxOf(existing.repeatCount, history.repeatCount),
            )
            if (next == existing) {
                unchanged += 1
            } else {
                update(next)
                merged += 1
            }
        }
        return HistoryMergeStats(
            inserted = inserted,
            merged = merged,
            unchanged = unchanged,
            skipped = skipped,
        )
    }

    /** Restores every raw metadata field while still keeping larger accumulated counters. */
    @Transaction
    fun restoreRawHistories(incoming: List<LHistory>): HistoryMergeStats {
        var inserted = 0
        var merged = 0
        var unchanged = 0
        var skipped = 0
        incoming.forEach { history ->
            if (history.contentId.isBlank() || history.startTime <= 0L) {
                skipped += 1
                return@forEach
            }
            val existing = getByContentAndStart(history.contentId, history.startTime)
            if (existing == null) {
                save(history.copy(id = 0L))
                inserted += 1
                return@forEach
            }
            val next = existing.copy(
                contentTitle = history.contentTitle,
                parentId = history.parentId,
                parentTitle = history.parentTitle,
                duration = maxOf(existing.duration, history.duration),
                repeatCount = maxOf(existing.repeatCount, history.repeatCount),
            )
            if (next == existing) {
                unchanged += 1
            } else {
                update(next)
                merged += 1
            }
        }
        return HistoryMergeStats(inserted, merged, unchanged, skipped)
    }

    @Delete(entity = LHistory::class)
    fun delete(vararg history: LHistory)

    @Query("SELECT * FROM m_history WHERE duration >= :minDuration ORDER BY startTime DESC")
    fun getAllData(minDuration: Long): PagingSource<Int, LHistory>

    @Query("SELECT * FROM m_history ORDER BY startTime DESC")
    fun getAllForBackup(): List<LHistory>

    @Query("SELECT * FROM m_history WHERE id = :id;")
    fun getById(id: Long): LHistory?

    @Query("SELECT COUNT(*) FROM m_history WHERE contentId = :contentId AND startTime = :startTime AND duration = :duration")
    fun countSimilar(contentId: String, startTime: Long, duration: Long): Int

    @Query("SELECT * FROM m_history WHERE contentId = :contentId AND startTime = :startTime ORDER BY id ASC LIMIT 1")
    fun getByContentAndStart(contentId: String, startTime: Long): LHistory?

    @Query("SELECT * FROM m_history ORDER BY id DESC LIMIT 1")
    fun getLatestHistory(): LHistory?

    /**
     * 查询播放历史，去除重复的记录，只保留最近的一条，按照最近播放时间排序
     */
    @Query(
        "SELECT * FROM " +
                "(SELECT id, contentId, contentTitle, parentId, parentTitle, duration, repeatCount, max(startTime) as 'startTime' FROM m_history WHERE duration >= :minDuration GROUP BY contentId) as A " +
                "ORDER BY A.startTime DESC LIMIT :limit;"
    )
    fun getFlow(limit: Int, minDuration: Long): Flow<List<LHistory>>

    /**
     * 查询播放历史，按照最近播放时间排序且计算每首歌的播放次数
     */
    @MapInfo(valueColumn = "count")
    @Query(
        "SELECT * FROM " +
                "(SELECT id, contentId, contentTitle, parentId, parentTitle, duration, repeatCount, CAST((count(contentId) + sum(repeatCount)) AS INTEGER) as 'count', max(startTime) as 'startTime' FROM m_history WHERE duration >= :minDuration GROUP BY contentId) as A " +
                "ORDER BY A.startTime DESC LIMIT :limit;"
    )
    fun getFlowWithCount(limit: Int, minDuration: Long): Flow<Map<LHistory, Int>>

    @MapInfo(keyColumn = "contentId", valueColumn = "count")
    @Query(
        "SELECT contentId, CAST((count(contentId) + sum(repeatCount)) AS INTEGER) as 'count' FROM m_history WHERE duration >= :minDuration GROUP BY contentId " +
                "LIMIT :limit;"
    )
    fun getFlowIdsMapWithCount(limit: Int, minDuration: Long): Flow<Map<String, Int>>

    @MapInfo(keyColumn = "contentId", valueColumn = "startTime")
    @Query(
        "SELECT contentId, max(startTime) as 'startTime' FROM m_history WHERE duration >= :minDuration GROUP BY contentId " +
                "LIMIT :limit;"
    )
    fun getFlowIdsMapWithLastTime(limit: Int, minDuration: Long): Flow<Map<String, Long>>

    @MapInfo(keyColumn = "contentId", valueColumn = "duration")
    @Query(
        "SELECT contentId, sum(CASE WHEN duration > 0 THEN duration ELSE 0 END) as 'duration' FROM m_history WHERE duration >= :minDuration GROUP BY contentId " +
                "LIMIT :limit;"
    )
    fun getFlowIdsMapWithDuration(limit: Int, minDuration: Long): Flow<Map<String, Long>>

    @MapInfo(keyColumn = "statId", valueColumn = "count")
    @Query(
                "SELECT " +
                "CASE WHEN parentId IS NOT NULL AND parentId != '' THEN parentId ELSE contentId END as 'statId', " +
                "CAST((count(*) + sum(repeatCount)) AS INTEGER) as 'count' " +
                "FROM m_history WHERE duration >= :minDuration GROUP BY statId LIMIT :limit;"
    )
    fun getFlowStatIdsMapWithCount(limit: Int, minDuration: Long): Flow<Map<String, Int>>

    @MapInfo(keyColumn = "statId", valueColumn = "startTime")
    @Query(
                "SELECT " +
                "CASE WHEN parentId IS NOT NULL AND parentId != '' THEN parentId ELSE contentId END as 'statId', " +
                "max(startTime) as 'startTime' " +
                "FROM m_history WHERE duration >= :minDuration GROUP BY statId LIMIT :limit;"
    )
    fun getFlowStatIdsMapWithLastTime(limit: Int, minDuration: Long): Flow<Map<String, Long>>

    @MapInfo(keyColumn = "statId", valueColumn = "duration")
    @Query(
                "SELECT " +
                "CASE WHEN parentId IS NOT NULL AND parentId != '' THEN parentId ELSE contentId END as 'statId', " +
                "sum(CASE WHEN duration > 0 THEN duration ELSE 0 END) as 'duration' " +
                "FROM m_history WHERE duration >= :minDuration GROUP BY statId LIMIT :limit;"
    )
    fun getFlowStatIdsMapWithDuration(limit: Int, minDuration: Long): Flow<Map<String, Long>>
}

data class HistoryMergeStats(
    val inserted: Int,
    val merged: Int,
    val unchanged: Int,
    val skipped: Int,
)
