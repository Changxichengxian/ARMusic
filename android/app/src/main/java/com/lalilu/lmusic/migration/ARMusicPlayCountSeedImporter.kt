package com.lalilu.lmusic.migration

import android.app.Application
import com.blankj.utilcode.util.LogUtils
import com.lalilu.lhistory.entity.LHistory
import com.lalilu.lhistory.HistoryMutationCoordinator
import com.lalilu.lhistory.repository.HistoryDao
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.tag.SongGroupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.CoroutineContext

class ARMusicPlayCountSeedImporter(
    private val application: Application,
    private val historyDao: HistoryDao,
    private val songGroupStore: SongGroupStore,
    private val mutationCoordinator: HistoryMutationCoordinator,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    private val sp = application.getSharedPreferences("armusic_play_count_seed", Application.MODE_PRIVATE)

    init {
        LMedia.whenReady { launch { importDatasets() } }
    }

    private suspend fun importDatasets() {
        DATASETS.forEach { importDataset(it) }
    }

    private suspend fun importDataset(dataset: PlayCountDataset) {
        if (sp.getBoolean(dataset.importedKey, false)) return

        val seeds = readSeeds(dataset.assetName)
        if (seeds.isEmpty()) return

        val songs = LMedia.get<LSong>(blockFilter = false)
        val histories = seeds.mapNotNull { seed ->
            val song = songs.findBestMatch(seed) ?: return@mapNotNull null
            val duration = (song.metadata.duration.takeIf { it > 0L } ?: seed.durationMs)
                .coerceAtLeast(0L)
                .times(seed.playCount)
            val statIdentity = songGroupStore.resolve(song.id, song.name)
            val hasParent = statIdentity.id != song.id

            LHistory(
                contentId = song.id,
                contentTitle = song.name,
                parentId = if (hasParent) statIdentity.id else "",
                parentTitle = if (hasParent) statIdentity.title else "",
                duration = duration,
                repeatCount = (seed.playCount - 1).coerceAtLeast(0),
                startTime = SEED_START_TIME - historiesOffset(dataset, seed),
            )
        }

        if (histories.isEmpty()) {
            LogUtils.i("[ARMusic] Play count seed ${dataset.assetName} found no matched songs.")
            return
        }

        val stats = mutationCoordinator.withMutation {
            historyDao.mergeHistories(histories)
        }
        sp.edit()
            .putBoolean(dataset.importedKey, true)
            .putInt(dataset.matchedCountKey, histories.size)
            .putInt(dataset.insertedCountKey, stats.inserted)
            .apply()

        LogUtils.i("[ARMusic] Seeded ${stats.inserted}/${histories.size} play-count histories from ${dataset.assetName}.")
    }

    private fun readSeeds(assetName: String): List<PlayCountSeed> {
        return runCatching {
            application.assets.open(assetName)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.drop(1)
                        .mapNotNull(::parseSeed)
                        .toList()
                }
        }.getOrDefault(emptyList())
    }

    private fun parseSeed(line: String): PlayCountSeed? {
        val columns = line.split('\t')
            .map { it.trim().trim('"') }
        if (columns.size < 4) return null

        val playCount = columns[0].toIntOrNull() ?: return null
        if (playCount <= 0) return null

        return PlayCountSeed(
            playCount = playCount,
            title = columns[1],
            artist = columns[2],
            durationMs = columns[3].toDurationMs(),
        )
    }

    private fun List<LSong>.findBestMatch(seed: PlayCountSeed): LSong? {
        val seedTitle = seed.title.normalizedForMatch()
        val seedTitleWithoutNote = seed.title.removeBracketText().normalizedForMatch()
        val seedArtist = seed.artist.normalizedForMatch()

        fun LSong.titleMatches(): Boolean {
            val title = metadata.title.normalizedForMatch()
            val titleWithoutNote = metadata.title.removeBracketText().normalizedForMatch()
            val fileName = name.substringBeforeLast('.').normalizedForMatch()

            return title == seedTitle ||
                    title == seedTitleWithoutNote ||
                    titleWithoutNote == seedTitleWithoutNote ||
                    fileName == seedTitle ||
                    fileName == seedTitleWithoutNote ||
                    (seedTitleWithoutNote.length >= 3 && title.contains(seedTitleWithoutNote)) ||
                    (titleWithoutNote.length >= 3 && seedTitle.contains(titleWithoutNote)) ||
                    (seedTitleWithoutNote.length >= 3 && fileName.contains(seedTitleWithoutNote))
        }

        fun LSong.artistMatches(): Boolean {
            if (seedArtist.isBlank()) return true
            val songArtist = metadata.artist.normalizedForMatch()
            if (songArtist.isBlank()) return true
            if (songArtist == seedArtist) return true

            val seedParts = seed.artist.artistParts()
                .map { it.normalizedForMatch() }
                .filter { it.length >= 2 }
            val songParts = metadata.artist.artistParts()
                .map { it.normalizedForMatch() }
                .filter { it.length >= 2 }

            return seedParts.any { songArtist.contains(it) } ||
                    songParts.any { seedArtist.contains(it) }
        }

        return firstOrNull { it.titleMatches() && it.artistMatches() }
            ?: firstOrNull { it.titleMatches() }
    }

    private fun String.toDurationMs(): Long {
        val parts = split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> toLongOrNull()?.takeIf { it > 1000L } ?: 0L
        }
    }

    private fun String.removeBracketText(): String {
        return replace(Regex("""\([^)]*\)|（[^）]*）|\[[^\]]*]|【[^】]*】"""), "")
    }

    private fun String.normalizedForMatch(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .removeBracketText()
            .replace("匕", "と")
            .replace("<", "く")
            .replace("才", "オ")
            .replace(Regex("""(?i)tv|动画|動漫|電影|电影|超清母带|其它版本可播|vip|沉浸声|ver\.?|version"""), "")
            .replace(Regex("""[\s_\-·・.。!！?？,，:：;；/\\|丨~～\[\]【】()（）《》「」『』"“”‘’'…]+"""), "")
            .trim()
    }

    private fun String.artistParts(): List<String> {
        return split('/', ';', '、', ',', '，')
    }

    private fun historiesOffset(dataset: PlayCountDataset, seed: PlayCountSeed): Long {
        return "${dataset.assetName}:${seed.title}:${seed.playCount}"
            .hashCode()
            .toLong()
            .and(0xFFFF) * 1000L
    }

    private data class PlayCountSeed(
        val playCount: Int,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    private data class PlayCountDataset(
        val assetName: String,
        val importedKey: String,
        val matchedCountKey: String,
        val insertedCountKey: String,
    )

    companion object {
        private const val SEED_START_TIME = 1782057600000L

        private val DATASETS = listOf(
            PlayCountDataset(
                assetName = "armusic_seed_play_counts_20260622.tsv",
                importedKey = "imported_20260622",
                matchedCountKey = "matched_20260622",
                insertedCountKey = "inserted_20260622",
            ),
            PlayCountDataset(
                assetName = "armusic_seed_play_counts_netease_20260622.tsv",
                importedKey = "imported_netease_20260622",
                matchedCountKey = "matched_netease_20260622",
                insertedCountKey = "inserted_netease_20260622",
            ),
            PlayCountDataset(
                assetName = "armusic_seed_play_counts_netease_fix_20260622.tsv",
                importedKey = "imported_netease_fix_20260622",
                matchedCountKey = "matched_netease_fix_20260622",
                insertedCountKey = "inserted_netease_fix_20260622",
            ),
        )
    }
}
