package com.lalilu.lmusic.agent

import com.lalilu.lmedia.LMedia
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

internal suspend fun awaitARMusicLibraryReady() {
    try {
        withTimeout(LIBRARY_READY_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                LMedia.whenReady {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    } catch (throwable: TimeoutCancellationException) {
        error("Music library is not ready. Open ARMusic once and grant audio permission first.")
    }
}

private const val LIBRARY_READY_TIMEOUT_MS = 60_000L
