package com.lalilu.lmedia.indexer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseLibraryReadyTest {
    @Test
    fun callbackIsDeliveredExactlyOnceAcrossReadyRegistrationRace() {
        repeat(500) {
            val library = object : BaseLibrary() {}
            val start = CountDownLatch(1)
            val calls = AtomicInteger()
            val register = thread {
                start.await()
                library.whenReady { calls.incrementAndGet() }
            }
            val ready = thread {
                start.await()
                library.updateState(LibraryState.Ready)
            }

            start.countDown()
            register.join()
            ready.join()
            assertEquals(1, calls.get())

            library.updateState(LibraryState.Ready)
            assertEquals(1, calls.get())
        }
    }
}
