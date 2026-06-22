package com.lalilu.lmusic.migration

import android.app.Application
import com.lalilu.lmusic.datastore.SettingsSp

class ARMusicMemoSeedImporter(
    private val application: Application,
    private val settingsSp: SettingsSp,
) {
    init {
        importOnce()
    }

    private fun importOnce() {
        if (settingsSp.memoSeed20260622Imported.value) return

        val sections = runCatching {
            application.assets.open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { parseSections(it.readText()) }
        }.getOrDefault(emptyMap())

        sections["anime"]?.takeIf { it.isNotBlank() }?.let { text ->
            if (settingsSp.wishlistAnimeText.value.isBlank()) {
                settingsSp.wishlistAnimeText.value = text
            }
        }
        sections["manga"]?.takeIf { it.isNotBlank() }?.let { text ->
            if (settingsSp.wishlistMangaText.value.isBlank()) {
                settingsSp.wishlistMangaText.value = text
            }
        }
        sections["novel"]?.takeIf { it.isNotBlank() }?.let { text ->
            if (settingsSp.wishlistNovelText.value.isBlank()) {
                settingsSp.wishlistNovelText.value = text
            }
        }

        settingsSp.memoSeed20260622Imported.value = true
    }

    private fun parseSections(text: String): Map<String, String> {
        val result = linkedMapOf<String, StringBuilder>()
        var currentKey: String? = null

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.startsWith("[") && line.endsWith("]")) {
                currentKey = line.removePrefix("[")
                    .removeSuffix("]")
                    .trim()
                    .lowercase()
                    .takeIf(String::isNotBlank)
                currentKey?.let { result.getOrPut(it) { StringBuilder() } }
                return@forEach
            }

            currentKey?.let { key ->
                result.getOrPut(key) { StringBuilder() }
                    .appendLine(line)
            }
        }

        return result.mapValues { (_, builder) -> builder.toString().trim() }
    }

    private companion object {
        const val ASSET_NAME = "armusic_memo_seed_20260622.txt"
    }
}
