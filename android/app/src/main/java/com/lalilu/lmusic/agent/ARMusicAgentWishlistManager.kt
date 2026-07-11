package com.lalilu.lmusic.agent

import android.os.Build
import com.lalilu.lmusic.datastore.SettingsSp
import com.lalilu.lmusic.wishlist.ARMusicWishlistStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ARMusicAgentWishlistManager(
    private val files: ARMusicAgentFiles,
    private val store: ARMusicWishlistStore,
    private val settingsSp: SettingsSp,
) {
    fun exportWishlist(outputPath: String): AgentCommandResult {
        val categories = currentCategories()
        val payload = buildPayload(categories)
        files.writeTextFile(outputPath, payload.toString(2))
        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_EXPORT_WISHLIST,
            message = "Exported ${categories.size} wishlist categories to $outputPath",
            outputPath = outputPath,
            wishlistCategories = categories.size,
            wishlistItems = categories.sumOf { it.items.size },
            wishlistSnapshotId = payload.getString("snapshotId"),
        )
    }

    fun importWishlist(inputPath: String): AgentCommandResult {
        val text = files.readTextFile(inputPath)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "愿望单同步文件超过 16 MB，手机原数据没有改动"
        }
        val incoming = parsePayload(text)
        val merged = store.mutateRaw { currentRaw ->
            val current = if (currentRaw.isBlank()) {
                legacyCategories()
            } else {
                decodeCategories(JSONArray(currentRaw))
            }
            val categories = unionCategories(current, incoming.categories)
            encodeCategories(categories).toString() to categories
        }
        val persisted = currentCategories()
        check(persisted == merged) { "愿望单写入后内容复核失败" }
        val payload = buildPayload(persisted)
        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_IMPORT_WISHLIST,
            message = "Merged ${persisted.size} wishlist categories without deleting either side",
            outputPath = inputPath,
            wishlistCategories = persisted.size,
            wishlistItems = persisted.sumOf { it.items.size },
            wishlistSnapshotId = payload.getString("snapshotId"),
        )
    }

    private fun currentCategories(): List<WishlistCategory> {
        val raw = store.readRaw()
        return if (raw.isBlank()) legacyCategories() else decodeCategories(JSONArray(raw))
    }

    private fun parsePayload(text: String): WishlistPayload {
        val normalized = text.trim { it <= ' ' || it == '\uFEFF' }
        require(normalized.isNotBlank()) { "愿望单同步文件为空" }
        val payload = if (normalized.startsWith("[")) {
            WishlistPayload(
                deviceId = "legacy",
                categories = decodeCategories(JSONArray(normalized)),
                suppliedSnapshotId = "",
            )
        } else {
            val root = JSONObject(normalized)
            require(root.optString("schema") in setOf(WISHLIST_SCHEMA, LEGACY_SCHEMA)) {
                "不支持的愿望单格式"
            }
            WishlistPayload(
                deviceId = root.optString("deviceId").ifBlank { "unknown" },
                categories = decodeCategories(root.getJSONArray("categories")),
                suppliedSnapshotId = root.optString("snapshotId"),
            )
        }
        val actualSnapshot = snapshotId(payload.categories)
        require(
            payload.suppliedSnapshotId.isBlank() || payload.suppliedSnapshotId == actualSnapshot
        ) { "愿望单快照校验失败，手机原数据没有改动" }
        return payload
    }

    private fun buildPayload(categories: List<WishlistCategory>): JSONObject {
        val normalized = normalizeCategories(categories)
        return JSONObject()
            .put("schema", WISHLIST_SCHEMA)
            .put("deviceId", androidDeviceId())
            .put("generatedAt", isoNow())
            .put("snapshotId", snapshotId(normalized))
            .put("categories", encodeCategories(normalized))
    }

    private fun decodeCategories(array: JSONArray): List<WishlistCategory> {
        require(array.length() <= MAX_CATEGORIES) { "愿望单栏目超过 $MAX_CATEGORIES 个" }
        val categories = buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index)
                    ?: error("第 ${index + 1} 个愿望单栏目格式不正确")
                val itemsArray = value.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (itemIndex in 0 until itemsArray.length()) {
                        val item = itemsArray.optString(itemIndex).trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
                add(
                    WishlistCategory(
                        id = value.optString("id"),
                        title = value.optString("title"),
                        color = value.optLong("color", 0L),
                        items = items,
                    )
                )
            }
        }
        return normalizeCategories(categories)
    }

    private fun encodeCategories(categories: List<WishlistCategory>): JSONArray = JSONArray().also { array ->
        normalizeCategories(categories).forEach { category ->
            array.put(
                JSONObject()
                    .put("id", category.id)
                    .put("title", category.title)
                    .put("color", category.color)
                    .put("items", JSONArray(category.items))
            )
        }
    }

    private fun normalizeCategories(categories: List<WishlistCategory>): List<WishlistCategory> {
        require(categories.size <= MAX_CATEGORIES) { "愿望单栏目超过 $MAX_CATEGORIES 个" }
        val usedIds = mutableSetOf<String>()
        val normalized = mutableListOf<WishlistCategory>()
        var itemCount = 0
        categories.forEachIndexed { index, category ->
            val title = category.title.trim()
            require(title.isNotBlank() && title.toByteArray(Charsets.UTF_8).size <= MAX_TITLE_BYTES) {
                "第 ${index + 1} 个愿望单栏目名称为空或过长"
            }
            require(category.color in 0L..0xFFFF_FFFFL) {
                "愿望单栏目“$title”的颜色值超出 ARGB 范围"
            }
            val items = category.items.mapNotNull { value ->
                value.trim().takeIf(String::isNotBlank)?.also { item ->
                    require(item.toByteArray(Charsets.UTF_8).size <= MAX_ITEM_BYTES) {
                        "愿望单栏目“$title”中有一条内容超过 64 KB"
                    }
                }
            }
            itemCount += items.size
            require(itemCount <= MAX_ITEMS) { "愿望单内容超过 $MAX_ITEMS 条" }
            var id = category.id.trim()
            if (id.isBlank() || id.length > 512 || id in usedIds) {
                id = conflictSafeId(category.copy(id = id, title = title, items = items), normalized)
            }
            usedIds += id
            normalized += category.copy(id = id, title = title, items = items)
        }
        return normalized
    }

    private fun unionCategories(
        base: List<WishlistCategory>,
        additional: List<WishlistCategory>,
    ): List<WishlistCategory> {
        val merged = normalizeCategories(base).toMutableList()
        normalizeCategories(additional).forEach { source ->
            val index = merged.indexOfFirst { existing -> categoriesMatch(existing, source) }
            if (index >= 0) {
                merged[index] = merged[index].copy(
                    items = multisetUnion(merged[index].items, source.items),
                )
            } else {
                val incoming = if (merged.any { it.id == source.id }) {
                    source.copy(id = conflictSafeId(source, merged))
                } else {
                    source
                }
                merged += incoming
            }
        }
        return normalizeCategories(merged)
    }

    private fun categoriesMatch(left: WishlistCategory, right: WishlistCategory): Boolean {
        val leftCanonical = canonicalCategory(left)
        val rightCanonical = canonicalCategory(right)
        return if (leftCanonical != null && rightCanonical != null) {
            leftCanonical == rightCanonical
        } else {
            left.id == right.id && left.title.trim() == right.title.trim() && left.color == right.color
        }
    }

    private fun canonicalCategory(category: WishlistCategory): String? {
        val id = category.id.trim().lowercase(Locale.ROOT)
        val title = category.title.trim().lowercase(Locale.ROOT)
        return when {
            id == "to-listen" || title in setOf(
                "准备听", "准备听的音乐", "想听的歌", "to listen", "聴きたい曲"
            ) -> "to-listen"
            id == "anime" || title in setOf("动漫", "動畫", "动画", "anime", "アニメ") -> "anime"
            id == "manga" || title in setOf("漫画", "漫畫", "manga", "マンガ") -> "manga"
            id == "novel" || title in setOf("小说", "小說", "novel", "novels", "小説") -> "novel"
            else -> null
        }
    }

    private fun multisetUnion(base: List<String>, additional: List<String>): List<String> {
        val result = base.toMutableList()
        val available = base.groupingBy { it }.eachCount()
        val seen = mutableMapOf<String, Int>()
        additional.forEach { item ->
            val count = (seen[item] ?: 0) + 1
            seen[item] = count
            if (count > (available[item] ?: 0)) result += item
        }
        return result
    }

    private fun conflictSafeId(
        category: WishlistCategory,
        existing: List<WishlistCategory>,
    ): String {
        var nonce = 0L
        while (true) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("armusic-wishlist-category-conflict-v1\u0000".toByteArray())
            digest.update(category.id.toByteArray())
            digest.update(category.title.toByteArray())
            updateColor(digest, category.color)
            updateLong(digest, nonce)
            val candidate = "category-${digest.digest().toHex().take(32)}"
            if (existing.none { it.id == candidate }) return candidate
            nonce += 1
        }
    }

    private fun snapshotId(categories: List<WishlistCategory>): String {
        val normalized = normalizeCategories(categories)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("armusic-wishlist-v2\u0000".toByteArray(Charsets.UTF_8))
        updateLong(digest, normalized.size.toLong())
        normalized.forEach { category ->
            updateField(digest, category.id)
            updateField(digest, category.title)
            // Rust stores the ARGB color as a u32, so the wire digest must use exactly four
            // big-endian bytes here as well (not an eight-byte Kotlin Long).
            updateColor(digest, category.color)
            updateLong(digest, category.items.size.toLong())
            category.items.forEach { updateField(digest, it) }
        }
        return "wishlist-sha256-${digest.digest().toHex().take(32)}"
    }

    private fun updateField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        updateLong(digest, bytes.size.toLong())
        digest.update(bytes)
    }

    private fun updateLong(digest: MessageDigest, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            digest.update(((value ushr shift) and 0xFF).toByte())
        }
    }

    private fun updateColor(digest: MessageDigest, value: Long) {
        for (shift in 24 downTo 0 step 8) {
            digest.update(((value ushr shift) and 0xFF).toByte())
        }
    }

    private fun legacyCategories(): List<WishlistCategory> {
        val seeds = listOf(
            LegacySeed(
                "legacy-to-listen", "准备听", 0xFF2F7D73L,
                settingsSp.wishlistItems.value, settingsSp.wishlistText.value,
            ),
            LegacySeed(
                "legacy-anime", "动漫", 0xFFD05A4EL,
                settingsSp.wishlistAnimeItems.value, settingsSp.wishlistAnimeText.value,
            ),
            LegacySeed(
                "legacy-manga", "漫画", 0xFF7666B0L,
                settingsSp.wishlistMangaItems.value, settingsSp.wishlistMangaText.value,
            ),
            LegacySeed(
                "legacy-novel", "小说", 0xFFB97825L,
                settingsSp.wishlistNovelItems.value, settingsSp.wishlistNovelText.value,
            ),
        )
        return normalizeCategories(
            seeds.mapNotNull { seed ->
                val items = seed.items.ifEmpty { parseLegacyItems(seed.text) }
                seed.takeIf { items.isNotEmpty() }?.let {
                    WishlistCategory(seed.id, seed.title, seed.color, items)
                }
            }
        )
    }

    private fun parseLegacyItems(text: String): List<String> = text.lineSequence()
        .map { it.trim().trim('\uFEFF') }
        .filter(String::isNotBlank)
        .filterNot { it in LEGACY_HEADERS }
        .filterNot { LEGACY_GROUP_MARKER.matches(it) }
        .toList()

    private fun androidDeviceId(): String = "android-${Build.MANUFACTURER}-${Build.MODEL}"
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "-")

    private fun isoNow(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        Locale.ROOT,
    ).format(Date())

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private data class WishlistCategory(
        val id: String,
        val title: String,
        val color: Long,
        val items: List<String>,
    )

    private data class WishlistPayload(
        val deviceId: String,
        val categories: List<WishlistCategory>,
        val suppliedSnapshotId: String,
    )

    private data class LegacySeed(
        val id: String,
        val title: String,
        val color: Long,
        val items: List<String>,
        val text: String,
    )

    private companion object {
        const val WISHLIST_SCHEMA = "armusic-wishlist-v2"
        const val LEGACY_SCHEMA = "armusic-wishlist-v1"
        const val MAX_CATEGORIES = 200
        const val MAX_ITEMS = 20_000
        const val MAX_TITLE_BYTES = 4 * 1024
        const val MAX_ITEM_BYTES = 64 * 1024
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        val LEGACY_GROUP_MARKER = Regex("""^\d+[.、]?$""")
        val LEGACY_HEADERS = setOf("愿望单", "准备听", "准备听的音乐", "动漫", "漫画", "小说")
    }
}
