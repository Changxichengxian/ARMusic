package com.lalilu.lhistory

data class HistoryStatIdentity(
    val id: String,
    val title: String,
)

interface HistoryStatIdResolver {
    fun resolve(mediaId: String, title: String): HistoryStatIdentity
}
