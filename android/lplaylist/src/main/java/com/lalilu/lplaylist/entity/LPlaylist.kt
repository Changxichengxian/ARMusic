package com.lalilu.lplaylist.entity

import com.google.gson.annotations.SerializedName
import com.lalilu.lmedia.extension.Searchable
import java.io.Serializable
import kotlin.String

data class LPlaylist(
    @field:SerializedName("id")
    val id: String = "",
    @field:SerializedName("title")
    val title: String = "",
    @field:SerializedName("subTitle")
    val subTitle: String = "",
    @field:SerializedName("coverUri")
    val coverUri: String = "",
    @field:SerializedName("mediaIds")
    val mediaIds: List<String> = emptyList(),
    @field:SerializedName("createTime")
    val createTime: Long = System.currentTimeMillis(),
    @field:SerializedName("modifyTime")
    val modifyTime: Long = System.currentTimeMillis()
) : Serializable, Searchable {
    override fun getMatchSource(): String = "$title$subTitle"
}

fun LPlaylist?.sanitizedOrNull(): LPlaylist? {
    val source = this ?: return null
    val safeId = source.safeString { id }.takeIf { it.isNotBlank() } ?: return null
    val now = System.currentTimeMillis()
    val safeCreateTime = source.safeLong { createTime }.takeIf { it > 0L } ?: now
    val safeModifyTime = source.safeLong { modifyTime }.takeIf { it > 0L } ?: safeCreateTime

    return LPlaylist(
        id = safeId,
        title = source.safeString { title }.ifBlank { safeId },
        subTitle = source.safeString { subTitle },
        coverUri = source.safeString { coverUri },
        mediaIds = source.safeMediaIds(),
        createTime = safeCreateTime,
        modifyTime = safeModifyTime,
    )
}

fun List<LPlaylist>?.sanitizePlaylists(): List<LPlaylist> {
    return this.orEmpty()
        .mapNotNull { it.sanitizedOrNull() }
        .filterNot { it.id in REMOVED_SYSTEM_PLAYLIST_IDS }
        .distinctBy { it.id }
}

private fun LPlaylist.safeString(read: LPlaylist.() -> String): String {
    val value: String? = runCatching { read() }.getOrNull()
    return value.orEmpty()
}

private fun LPlaylist.safeLong(read: LPlaylist.() -> Long): Long {
    return runCatching { read() }.getOrDefault(0L)
}

private fun LPlaylist.safeMediaIds(): List<String> {
    return runCatching { mediaIds }.getOrNull()
        .orEmpty()
        .mapNotNull { item ->
            runCatching { item.takeIf { value -> value.isNotBlank() } }.getOrNull()
        }
        .distinct()
}

private val REMOVED_SYSTEM_PLAYLIST_IDS = setOf("FAVOURITE")
