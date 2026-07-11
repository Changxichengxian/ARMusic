package com.lalilu.lmusic.utils.coil.fetcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 为确实没有内嵌封面、媒体库封面或手动封面的音乐生成稳定的 ARMusic 艺术封面。
 *
 * 配色和构图与桌面端 CoverArt 保持一致：同一首歌会稳定地得到相同的配色、圆和线。
 * 该渲染器只能由各 Fetcher 在所有真实封面候选均失败后调用。
 */
internal object ARMusicFallbackCover {
    private const val SIZE = 512

    private val palettes = arrayOf(
        intArrayOf(0xFF6F1024.toInt(), 0xFFE4A6AF.toInt(), 0xFFF6EEE9.toInt()),
        intArrayOf(0xFF102A43.toInt(), 0xFF78B7D0.toInt(), 0xFFE9F1EF.toInt()),
        intArrayOf(0xFF4B2B20.toInt(), 0xFFD49D62.toInt(), 0xFFF4E7CF.toInt()),
        intArrayOf(0xFF24211F.toInt(), 0xFFA8A5A1.toInt(), 0xFFF0EDE7.toInt()),
        intArrayOf(0xFF1E3A34.toInt(), 0xFF78A892.toInt(), 0xFFE8EFE8.toInt()),
        intArrayOf(0xFF34245C.toInt(), 0xFFAD91D2.toInt(), 0xFFEFE9F3.toInt()),
        intArrayOf(0xFF6F3011.toInt(), 0xFFF1A054.toInt(), 0xFFF5E6C8.toInt()),
        intArrayOf(0xFF202A57.toInt(), 0xFF8EA3DF.toInt(), 0xFFECECF4.toInt()),
    )

    suspend fun create(
        identity: String,
        title: String,
        artist: String,
        year: String? = null,
    ): ByteArrayInputStream = withContext(Dispatchers.Default) {
        val safeTitle = title.ifBlank { "ARMusic" }
        val paletteSeed = stableNumber("$identity:$safeTitle")
        val layoutSeed = stableNumber(identity)
        val palette = palettes[(paletteSeed % palettes.size).toInt()]
        val variant = (layoutSeed % 4).toInt()
        val turn = (layoutSeed % 32).toFloat() - 16f
        val shift = 0.18f + (layoutSeed % 34).toFloat() / 100f

        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, palette, variant)
        drawVariantDetails(canvas, palette, variant)
        drawOrb(canvas, shift)
        drawLines(canvas, turn)
        drawTypography(canvas, safeTitle, artist, year)

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        ByteArrayInputStream(output.toByteArray())
    }

    private fun drawBackground(canvas: Canvas, palette: IntArray, variant: Int) {
        val angle = when (variant) {
            1 -> 150f
            2 -> 120f
            3 -> 25f
            else -> 140f
        }
        val radians = Math.toRadians(angle.toDouble())
        val half = SIZE / 2f
        val dx = cos(radians).toFloat() * half
        val dy = sin(radians).toFloat() * half
        val colors = when (variant) {
            1, 3 -> intArrayOf(palette[0], palette[1], palette[2])
            2 -> intArrayOf(palette[1], palette[0])
            else -> intArrayOf(palette[2], palette[1], palette[0])
        }
        val stops = when (colors.size) {
            3 -> floatArrayOf(0f, 0.54f, 1f)
            else -> null
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                half - dx,
                half - dy,
                half + dx,
                half + dy,
                colors,
                stops,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)
    }

    private fun drawVariantDetails(canvas: Canvas, palette: IntArray, variant: Int) {
        val translucentWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(42, 255, 255, 255)
            style = Paint.Style.FILL
        }
        when (variant) {
            1 -> {
                val ellipsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mix(palette[2], Color.WHITE, 0.15f)
                }
                canvas.drawOval(-SIZE * 0.02f, SIZE * 0.58f, SIZE * 0.42f, SIZE * 0.98f, ellipsePaint)
                var x = 0f
                while (x < SIZE) {
                    canvas.drawRect(x, 0f, x + SIZE * 0.006f, SIZE.toFloat(), translucentWhite)
                    x += SIZE * 0.072f
                }
            }

            2 -> {
                val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[2] }
                canvas.drawCircle(SIZE * 0.70f, SIZE * 0.30f, SIZE * 0.20f, circlePaint)
                val band = Path().apply {
                    moveTo(0f, SIZE * 0.58f)
                    lineTo(0f, SIZE * 0.52f)
                    lineTo(SIZE.toFloat(), SIZE * 0.08f)
                    lineTo(SIZE.toFloat(), SIZE * 0.14f)
                    close()
                }
                translucentWhite.color = Color.argb(56, 255, 255, 255)
                canvas.drawPath(band, translucentWhite)
            }

            3 -> {
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(64, 255, 255, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = SIZE * 0.008f
                }
                canvas.drawCircle(SIZE * 0.50f, SIZE * 0.50f, SIZE * 0.23f, ringPaint)
                ringPaint.color = Color.argb(38, 255, 255, 255)
                canvas.drawCircle(SIZE * 0.50f, SIZE * 0.50f, SIZE * 0.37f, ringPaint)
            }

            else -> {
                val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(70, Color.red(palette[0]), Color.green(palette[0]), Color.blue(palette[0]))
                }
                val band = Path().apply {
                    moveTo(-SIZE * 0.05f, SIZE * 0.72f)
                    lineTo(-SIZE * 0.05f, SIZE * 0.58f)
                    lineTo(SIZE * 1.05f, SIZE * 0.12f)
                    lineTo(SIZE * 1.05f, SIZE * 0.26f)
                    close()
                }
                canvas.drawPath(band, bandPaint)
            }
        }
    }

    private fun drawOrb(canvas: Canvas, shift: Float) {
        val radius = SIZE * 0.185f
        val centerX = (shift * SIZE).coerceIn(radius + SIZE * 0.08f, SIZE - radius - SIZE * 0.08f)
        val centerY = SIZE * 0.275f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(23, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(163, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = SIZE * 0.003f
        }
        val inset = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(35, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = SIZE * 0.020f
        }
        canvas.drawCircle(centerX, centerY, radius, fill)
        canvas.drawCircle(centerX, centerY, radius, outline)
        canvas.drawCircle(centerX, centerY, radius - SIZE * 0.016f, inset)
    }

    private fun drawLines(canvas: Canvas, turn: Float) {
        canvas.save()
        canvas.rotate(turn, SIZE * 0.25f, SIZE * 0.50f)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(128, 255, 255, 255)
            strokeWidth = SIZE * 0.002f
        }
        canvas.drawLine(SIZE * 0.25f, -SIZE * 0.16f, SIZE * 0.25f, SIZE * 1.14f, line)
        line.color = Color.argb(31, 255, 255, 255)
        canvas.drawLine(SIZE * 0.276f, -SIZE * 0.16f, SIZE * 0.276f, SIZE * 1.14f, line)
        canvas.drawLine(SIZE * 0.302f, -SIZE * 0.16f, SIZE * 0.302f, SIZE * 1.14f, line)
        canvas.restore()
    }

    private fun drawTypography(canvas: Canvas, title: String, artist: String, year: String?) {
        val edition = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(184, 255, 255, 255)
            textSize = SIZE * 0.028f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.14f
        }
        val yearLabel = year
            ?.trim()
            ?.take(4)
            ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: "MUSIC"
        canvas.drawText("AR / $yearLabel", SIZE * 0.08f, SIZE * 0.11f, edition)

        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.19f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(SIZE * 0.024f, 0f, SIZE * 0.004f, Color.argb(45, 0, 0, 0))
        }
        canvas.drawText(coverMark(title), SIZE * 0.93f, SIZE * 0.91f, mark)

        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(191, 255, 255, 255)
            textSize = SIZE * 0.024f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val captionText = fitText(
            artist.ifBlank { "UNKNOWN ARTIST" }.uppercase(Locale.ROOT),
            caption,
            SIZE * 0.40f,
        )
        canvas.drawText(captionText, SIZE * 0.08f, SIZE * 0.92f, caption)
    }

    private fun coverMark(title: String): String {
        return title
            .replace(Regex("[\\s·・,.!?，。！？()（）-]"), "")
            .take(2)
            .uppercase(Locale.ROOT)
            .ifBlank { "AR" }
    }

    private fun fitText(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) {
            end -= 1
        }
        return value.substring(0, end) + "…"
    }

    private fun stableNumber(value: String): Long {
        var result = 0
        value.forEach { char -> result = result * 31 + char.code }
        return abs(result.toLong())
    }

    private fun mix(first: Int, second: Int, secondWeight: Float): Int {
        val weight = secondWeight.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) * (1f - weight) + Color.red(second) * weight).toInt(),
            (Color.green(first) * (1f - weight) + Color.green(second) * weight).toInt(),
            (Color.blue(first) * (1f - weight) + Color.blue(second) * weight).toInt(),
        )
    }
}
