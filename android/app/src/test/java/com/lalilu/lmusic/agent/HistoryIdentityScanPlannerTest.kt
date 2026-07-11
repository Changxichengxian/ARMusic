package com.lalilu.lmusic.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryIdentityScanPlannerTest {
    @Test
    fun skipsScanWhenEveryRequestedIdentityIsCached() {
        assertFalse(
            needsHistoryIdentityScan(
                requestedSyncIds = listOf("audio-sha256-a", "audio-sha256-b"),
                cachedSyncIds = setOf("audio-sha256-a", "audio-sha256-b", "audio-sha256-c"),
            )
        )
    }

    @Test
    fun ignoresMissingIdentitiesThatWereNotRequested() {
        assertFalse(
            needsHistoryIdentityScan(
                requestedSyncIds = listOf("audio-sha256-a"),
                cachedSyncIds = setOf("audio-sha256-a"),
            )
        )
    }

    @Test
    fun scansWhenOneRequestedIdentityIsMissing() {
        assertTrue(
            needsHistoryIdentityScan(
                requestedSyncIds = listOf("audio-sha256-a", "legacy-b"),
                cachedSyncIds = setOf("audio-sha256-a"),
            )
        )
    }

    @Test
    fun rowsWithoutSyncIdsDoNotForceAHashScan() {
        assertFalse(
            needsHistoryIdentityScan(
                requestedSyncIds = listOf("", "   "),
                cachedSyncIds = emptySet(),
            )
        )
    }
}
