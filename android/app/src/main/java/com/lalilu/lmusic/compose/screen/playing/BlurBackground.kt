package com.lalilu.lmusic.compose.screen.playing

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.lalilu.common.getAutomaticColor
import com.lalilu.lmusic.utils.StackBlurUtils
import com.lalilu.lmusic.utils.coil.fetcher.IndexedMediaItemCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val BACKGROUND_CROSSFADE_MS = 1400
private const val BACKGROUND_DECODE_SIZE = 720
private const val BACKGROUND_SAMPLING_SIZE = 256

@Composable
fun BlurBackground(
    modifier: Modifier = Modifier,
    imageData: () -> Any,
    onBackgroundColorFetched: (Color) -> Unit,
    blurProgress: () -> Float,
) {
    val context = LocalContext.current
    val currentData = imageData()
    val background = remember { mutableStateOf<BlurBackgroundData?>(null) }
    val outgoingBackground = remember { mutableStateOf<BlurBackgroundData?>(null) }
    val crossfadeProgress = remember { Animatable(1f) }
    val bitmapPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
    val maskPaint =
        remember { Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = android.graphics.Color.BLACK } }
    val targetRect = remember { Rect() }

    DisposableEffect(Unit) {
        onDispose { StackBlurUtils.clearMemory() }
    }

    LaunchedEffect(currentData) {
        val loaded = withContext(Dispatchers.IO) {
            val key = backgroundKey(currentData)
            val request = ImageRequest.Builder(context)
                .data(currentData)
                .size(BACKGROUND_DECODE_SIZE)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult
                ?: return@withContext null
            val source = result.image.toBitmap()
            val displayBitmap = createBoundedBitmap(source, BACKGROUND_DECODE_SIZE)
            val samplingBitmap = createSamplingBitmap(source, BACKGROUND_SAMPLING_SIZE)
            val color = Palette.from(samplingBitmap)
                .generate()
                .getAutomaticColor()

            BlurBackgroundData(
                displayBitmap = displayBitmap,
                samplingBitmap = samplingBitmap,
                key = key,
                displaySrcRect = centeredSquareRect(displayBitmap),
                samplingSrcRect = centeredSquareRect(samplingBitmap),
                color = Color(color)
            )
        }

        if (loaded != null && isActive) {
            val previous = background.value
            if (previous?.key == loaded.key) {
                background.value = loaded
                outgoingBackground.value = null
                crossfadeProgress.snapTo(1f)
            } else {
                outgoingBackground.value = previous
                background.value = loaded
                crossfadeProgress.snapTo(if (previous == null) 1f else 0f)
            }

            onBackgroundColorFetched(loaded.color)
            StackBlurUtils.preload(loaded.samplingBitmap, loaded.key)

            if (previous != null && previous.key != loaded.key) {
                crossfadeProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = BACKGROUND_CROSSFADE_MS,
                        easing = FastOutSlowInEasing
                    )
                )
                outgoingBackground.value = null
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                val data = background.value ?: return@drawWithContent
                val outgoing = outgoingBackground.value
                val transitionProgress = crossfadeProgress.value
                targetRect.set(0, 0, size.width.toInt(), size.height.toInt())
                val canvas = drawContext.canvas.nativeCanvas

                val blurAmount = blurProgress()
                val radius = (blurAmount * StackBlurUtils.MAX_RADIUS).toInt()

                fun alphaOf(value: Float): Int {
                    return (value * 255f)
                        .coerceIn(0f, 255f)
                        .toInt()
                }

                fun drawDisplayLayer(layer: BlurBackgroundData, alpha: Float) {
                    if (alpha <= 0f) return
                    bitmapPaint.alpha = alphaOf(alpha)

                    canvas.drawBitmap(
                        layer.displayBitmap,
                        layer.displaySrcRect,
                        targetRect,
                        bitmapPaint
                    )
                }

                fun drawBlurLayer(layer: BlurBackgroundData, alpha: Float) {
                    if (alpha <= 0f || radius <= 0) return
                    bitmapPaint.alpha = alphaOf(alpha)

                    StackBlurUtils.processWithCache(
                        layer.samplingBitmap,
                        radius,
                        layer.key
                    )?.let { blurredBitmap ->
                        canvas.drawBitmap(
                            blurredBitmap,
                            layer.samplingSrcRect,
                            targetRect,
                            bitmapPaint
                        )
                    }
                }

                if (outgoing != null) {
                    drawDisplayLayer(outgoing, 1f)
                    drawDisplayLayer(data, transitionProgress)
                    drawBlurLayer(outgoing, 1f)
                    drawBlurLayer(data, transitionProgress)
                } else {
                    drawDisplayLayer(data, 1f)
                    drawBlurLayer(data, 1f)
                }
                bitmapPaint.alpha = 255

                if (radius > 0) {
                    maskPaint.alpha = (blurAmount * 100f)
                        .coerceIn(0f, 255f)
                        .toInt()
                    canvas.drawRect(targetRect, maskPaint)
                }
            }
    )
}

private data class BlurBackgroundData(
    val displayBitmap: Bitmap,
    val samplingBitmap: Bitmap,
    val key: String,
    val displaySrcRect: Rect,
    val samplingSrcRect: Rect,
    val color: Color,
)

private fun backgroundKey(data: Any): String {
    return when (data) {
        is IndexedMediaItemCover -> {
            "indexed:${data.item.mediaId}:${data.item.mediaMetadata.hashCode()}:${data.index}"
        }

        is MediaItem -> "media:${data.mediaId}:${data.mediaMetadata.hashCode()}"
        else -> data.toString()
    }
}

private fun centeredSquareRect(bitmap: Bitmap): Rect {
    return if (bitmap.width > bitmap.height) {
        val left = (bitmap.width - bitmap.height) / 2
        Rect(left, 0, left + bitmap.height, bitmap.height)
    } else {
        val top = (bitmap.height - bitmap.width) / 2
        Rect(0, top, bitmap.width, top + bitmap.width)
    }
}

private fun createBoundedBitmap(source: Bitmap, maxSide: Int): Bitmap {
    val largestSide = maxOf(source.width, source.height)
    if (largestSide <= maxSide) return source

    val scale = maxSide.toFloat() / largestSide.toFloat()
    val matrix = Matrix().apply { setScale(scale, scale) }

    return Bitmap.createBitmap(
        source, 0, 0, source.width, source.height, matrix, true
    )
}

/**
 * 重采样图片，降低图片大小，用于Blur
 *
 * @param source 源图
 * @param samplingValue 输出图片的最大边大小
 * @return 经过重采样的Bitmap
 */
fun createSamplingBitmap(source: Bitmap, samplingValue: Int): Bitmap {
    val width = source.width
    val height = source.height
    val largestSide = maxOf(width, height)
    if (largestSide <= samplingValue) return source

    val matrix = Matrix()
    val scale = samplingValue.toFloat() / largestSide.toFloat()
    matrix.setScale(scale, scale)

    return Bitmap.createBitmap(
        source, 0, 0, width, height, matrix, false
    )
}
