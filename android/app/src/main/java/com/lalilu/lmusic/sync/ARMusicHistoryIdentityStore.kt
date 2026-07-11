package com.lalilu.lmusic.sync

import android.app.Application
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Keeps MediaStore ids linked to audio identities across moves and rescans. */
class ARMusicHistoryIdentityStore(
    application: Application,
) {
    private val preferences = application.getSharedPreferences(
        "armusic_history_identity_v1",
        Application.MODE_PRIVATE,
    )

    fun remember(mediaId: String, title: String, stableId: String) {
        if (mediaId.isBlank() || stableId.isBlank()) return
        val normalizedTitle = title.normalizedTitle()
        val value = JSONObject()
            .put("stableId", stableId)
            .put("title", normalizedTitle)
            .toString()
        check(
            preferences.edit()
            .putString(exactKey(mediaId, normalizedTitle), value)
            .putString(currentKey(mediaId), value)
            .commit()
        ) { "无法持久保存听歌记录的稳定音频映射" }
    }

    fun resolve(mediaId: String, title: String): String? {
        if (mediaId.isBlank()) return null
        val normalizedTitle = title.normalizedTitle()
        read(exactKey(mediaId, normalizedTitle))?.let { return it.stableId }
        val current = read(currentKey(mediaId)) ?: return null
        return current.stableId.takeIf {
            normalizedTitle.isBlank() || current.title.isBlank() || current.title == normalizedTitle
        }
    }

    private fun read(key: String): StoredIdentity? = preferences.getString(key, null)
        ?.let { value ->
            runCatching {
                val json = JSONObject(value)
                StoredIdentity(
                    stableId = json.getString("stableId"),
                    title = json.optString("title"),
                )
            }.getOrNull()
        }
        ?.takeIf { it.stableId.startsWith("audio-sha256-") }

    private fun exactKey(mediaId: String, normalizedTitle: String): String =
        "exact-${sha256("$mediaId\u0000$normalizedTitle")}"

    private fun currentKey(mediaId: String): String = "current-${sha256(mediaId)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun String.normalizedTitle(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    private data class StoredIdentity(
        val stableId: String,
        val title: String,
    )
}
