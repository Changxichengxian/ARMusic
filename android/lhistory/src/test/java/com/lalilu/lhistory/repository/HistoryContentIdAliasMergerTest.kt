package com.lalilu.lhistory.repository

import com.lalilu.lhistory.entity.LHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HistoryContentIdAliasMergerTest {
    private val aliases = mapOf("1000000866" to "1000129139")

    @Test
    fun duplicateOldAndNewRowsBecomeOneCanonicalEventWithMaximumCounters() {
        val old = history(
            id = 1,
            contentId = "1000000866",
            startTime = 1_700_000_000_000,
            duration = 73_790,
            repeatCount = 1,
            title = "旧标题",
            parentId = "work-id",
            parentTitle = "作品",
        )
        val current = history(
            id = 2,
            contentId = "1000129139",
            startTime = old.startTime,
            duration = 70_000,
            repeatCount = 3,
            title = "当前标题",
        )

        val plan = planHistoryContentIdAliasMerge(listOf(old, current), aliases)

        assertEquals(listOf(old), plan.deletes)
        assertEquals(1, plan.updates.size)
        assertEquals(
            current.copy(
                duration = 73_790,
                repeatCount = 3,
                parentId = "work-id",
                parentTitle = "作品",
            ),
            plan.updates.single(),
        )
        assertEquals(1, plan.stats.removedDuplicates)
    }

    @Test
    fun differentStartTimesAreMigratedButNeverCollapsed() {
        val first = history(1, "1000000866", 10_000, 1_000)
        val second = history(2, "1000129139", 10_001, 2_000)

        val plan = planHistoryContentIdAliasMerge(listOf(first, second), aliases)

        assertTrue(plan.deletes.isEmpty())
        assertEquals(listOf(first.copy(contentId = "1000129139")), plan.updates)
        assertEquals(0, plan.stats.removedDuplicates)
    }

    @Test
    fun unrelatedRowsAreNeverTouched() {
        val unrelated = history(9, "another-song", 10_000, 9_000)

        val plan = planHistoryContentIdAliasMerge(listOf(unrelated), aliases)

        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deletes.isEmpty())
        assertEquals(0, plan.stats.affectedRows)
    }

    @Test
    fun cyclicAliasConfigurationIsRejectedBeforePlanningWrites() {
        assertFailsWith<IllegalStateException> {
            planHistoryContentIdAliasMerge(
                histories = listOf(history(1, "old", 10_000, 1_000)),
                aliases = mapOf("old" to "new", "new" to "old"),
            )
        }
    }

    private fun history(
        id: Long,
        contentId: String,
        startTime: Long,
        duration: Long,
        repeatCount: Int = 0,
        title: String = "歌",
        parentId: String = "",
        parentTitle: String = "",
    ) = LHistory(
        id = id,
        contentId = contentId,
        contentTitle = title,
        parentId = parentId,
        parentTitle = parentTitle,
        duration = duration,
        repeatCount = repeatCount,
        startTime = startTime,
    )
}
