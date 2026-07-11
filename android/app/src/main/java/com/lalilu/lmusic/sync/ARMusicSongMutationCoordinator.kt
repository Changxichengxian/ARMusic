package com.lalilu.lmusic.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes Android song manifests and every app-owned song publication. */
class ARMusicSongMutationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withMutation(block: suspend () -> T): T = mutex.withLock { block() }
}
