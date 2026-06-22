package com.lalilu.lmusic.api.itunes

import retrofit2.http.GET
import retrofit2.http.Query

interface ItunesSearchApi {
    @GET("/search")
    suspend fun searchSongs(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("country") country: String = "CN",
        @Query("limit") limit: Int = 20,
    ): ItunesSearchResponse
}

data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesSongResult> = emptyList(),
)

data class ItunesSongResult(
    val trackId: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val collectionArtistName: String? = null,
    val primaryGenreName: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val releaseDate: String? = null,
    val artworkUrl100: String? = null,
)
