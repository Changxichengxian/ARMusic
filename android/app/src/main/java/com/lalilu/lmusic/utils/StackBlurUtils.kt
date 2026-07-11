package com.lalilu.lmusic.utils

import android.graphics.Bitmap
import android.util.LruCache
import com.enrique.stackblur.NativeBlurProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

object StackBlurUtils : NativeBlurProcess(), CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO

    const val MAX_RADIUS = 40
    private const val RADIUS_STEP = 5
    private const val CACHE_SIZE_BYTES = 12 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String?, value: Bitmap?): Int {
            return value?.byteCount ?: 0
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: Bitmap?,
            newValue: Bitmap?
        ) {
            runCatching {
                if (oldValue?.isRecycled == false) {
                    oldValue.recycle()
                }
            }
        }
    }
    private var preloadJob: Job? = null


    fun processWithCache(
        source: Bitmap,
        radius: Int,
        extraKey: String = ""
    ): Bitmap? {
        val boundedRadius = quantizeRadius(radius)
        if (boundedRadius == 0) return null

        val key = "${source.generationId}|$extraKey|$boundedRadius"
        return cache.get(key)
            ?: blur(source, boundedRadius.toFloat()).also { cache.put(key, it) }
    }

    fun preload(
        source: Bitmap,
        extraKey: String = ""
    ) {
        preloadJob?.cancel()
        preloadJob = launch {
            // Eight bounded variants are visually smooth enough behind the dark mask. Building
            // them sequentially avoids a burst of 40 native allocations and worker threads.
            for (radius in RADIUS_STEP..MAX_RADIUS step RADIUS_STEP) {
                if (!isActive) return@launch
                val key = "${source.generationId}|$extraKey|$radius"
                if (cache.get(key) != null) continue

                val temp = blur(source, radius.toFloat())
                if (!isActive) {
                    temp.recycle()
                    return@launch
                }
                if (cache.get(key) == null) {
                    cache.put(key, temp)
                } else {
                    temp.recycle()
                }
            }
        }
    }

    fun clearMemory() {
        preloadJob?.cancel()
        preloadJob = null
        cache.evictAll()
    }

    private fun quantizeRadius(radius: Int): Int {
        if (radius <= 0) return 0
        return (((radius + RADIUS_STEP - 1) / RADIUS_STEP) * RADIUS_STEP)
            .coerceAtMost(MAX_RADIUS)
    }
}
