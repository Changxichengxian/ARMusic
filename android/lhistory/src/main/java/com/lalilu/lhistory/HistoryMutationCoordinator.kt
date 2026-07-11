package com.lalilu.lhistory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/** Serializes every app-owned history write, import and destructive clear. */
@Single
class HistoryMutationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withMutation(block: suspend () -> T): T = mutex.withLock { block() }
}
