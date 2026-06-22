package com.lalilu.lmusic.api.lrcshare

import retrofit2.http.GET
import retrofit2.http.Query

interface LrcShareApi {

    @GET("/musicTag/get-tag")
    suspend fun searchForSong(
        @Query("song") song: String,
        @Query("artist") artist: String? = null,
        @Query("album") album: String? = null
    ): List<SongResult>

    @GET("/musicTag/get-lyric")
    suspend fun getLyricById(
        @Query("id") id: Int
    ): LyricResult?
}
