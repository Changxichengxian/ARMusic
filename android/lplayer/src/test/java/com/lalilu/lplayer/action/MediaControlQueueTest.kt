package com.lalilu.lplayer.action

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaControlQueueTest {
    @Test
    fun selectedSongStartsAFullUniqueLibraryQueue() {
        val library = listOf("a", "b", "c", "b", "d", "e")

        val queue = buildLibraryPlaybackQueue(
            libraryIds = library,
            selectedId = "c",
            random = Random(42),
        )

        assertEquals("c", queue.first())
        assertEquals(setOf("a", "b", "c", "d", "e"), queue.toSet())
        assertEquals(queue.size, queue.distinct().size)
    }

    @Test
    fun missingSelectionNeverCreatesAnInvalidQueueEntry() {
        val queue = buildLibraryPlaybackQueue(
            libraryIds = listOf("a", "b", "c"),
            selectedId = "missing",
            random = Random(42),
        )

        assertEquals(setOf("a", "b", "c"), queue.toSet())
        assertEquals(3, queue.size)
    }
}
