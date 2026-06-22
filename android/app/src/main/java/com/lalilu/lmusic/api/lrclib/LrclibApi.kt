package com.lalilu.lmusic.api.lrclib

import retrofit2.http.GET
import retrofit2.http.Query

interface LrclibApi {
    @GET("/api/search")
    suspend fun search(
        @Query("q") query: String? = null,
        @Query("track_name") trackName: String? = null,
        @Query("artist_name") artistName: String? = null,
        @Query("album_name") albumName: String? = null,
    ): List<LrclibLyricsResult>
}

data class LrclibLyricsResult(
    val id: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Long? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
