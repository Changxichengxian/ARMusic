package com.lalilu.lmusic.utils

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class ARMusicFontManager(
    private val application: Application,
) {
    private val fontDir: File
        get() = File(application.filesDir, "fonts").apply { mkdirs() }

    fun listFonts(): List<File> {
        return fontDir.listFiles()
            ?.filter {
                it.isFile &&
                        it.name !in LEGACY_BUNDLED_FONT_NAMES &&
                        it.extension.lowercase() in FONT_EXTENSIONS
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun seedBundledFonts() {
        val assetRoot = "bundled_fonts"
        val marker = File(fontDir, ".bundled-fonts-v3")
        cleanupLegacyBundledFonts()

        val names = application.assets.list(assetRoot)
            .orEmpty()
            .filter { it.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS }
            .sorted()
        if (names.isEmpty()) return

        if (marker.exists() && names.all { name ->
                File(fontDir, name).let { it.exists() && it.length() > 0L }
            }
        ) return

        names.forEach { name ->
            val target = File(fontDir, name)
            if (target.exists() && target.length() > 0L) return@forEach

            application.assets.open("$assetRoot/$name").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        marker.writeText(names.joinToString(separator = "\n"))
    }

    private fun cleanupLegacyBundledFonts() {
        val marker = File(fontDir, ".bundled-fonts-cleanup-v3")
        if (marker.exists()) return

        LEGACY_BUNDLED_FONT_NAMES.forEach { name ->
            File(fontDir, name).takeIf { it.exists() }?.delete()
        }
        marker.writeText("")
    }

    fun importFont(uri: Uri): File {
        val displayName = queryDisplayName(uri)
            ?.sanitizeFileName()
            ?.takeIf { it.isNotBlank() }
            ?: "font-${System.currentTimeMillis()}.ttf"
        val target = fontDir.uniqueChild(displayName.ensureFontExtension())

        application.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("无法读取字体文件")

        return target
    }

    private fun queryDisplayName(uri: Uri): String? {
        return application.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
    }

    private fun File.uniqueChild(fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ""

        var index = 0
        while (true) {
            val candidate = if (index == 0) {
                File(this, fileName)
            } else {
                File(this, "$baseName ($index)$extension")
            }
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private fun String.ensureFontExtension(): String {
        return if (substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS) this else "$this.ttf"
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_")

    private companion object {
        val FONT_EXTENSIONS = setOf("ttf", "otf")
        val LEGACY_BUNDLED_FONT_NAMES = setOf(
            "LiuJianMaoCao-Regular.ttf",
            "LongCang-Regular.ttf",
            "MaShanZheng-Regular.ttf",
            "ZCOOLQingKeHuangYou-Regular.ttf",
            "ZCOOLXiaoWei-Regular.ttf",
        )
    }
}
