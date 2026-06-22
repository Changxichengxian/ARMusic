package com.lalilu.lmusic.api.netease

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface NeteaseMusicApi {
    @Headers(
        "User-Agent: Mozilla/5.0",
        "Referer: https://music.163.com/"
    )
    @GET("/api/search/get/web")
    suspend fun searchSongs(
        @Query("s") keyword: String,
        @Query("type") type: Int = 1,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 20,
    ): NeteaseSearchResponse

    @Headers(
        "User-Agent: Mozilla/5.0",
        "Referer: https://music.163.com/"
    )
    @GET("/api/search/get/web")
    suspend fun searchLyrics(
        @Query("s") keyword: String,
        @Query("type") type: Int = 1006,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 10,
    ): NeteaseLyricSearchResponse

    @Headers(
        "User-Agent: Mozilla/5.0",
        "Referer: https://music.163.com/"
    )
    @GET("/api/song/detail/")
    suspend fun songDetails(
        @Query("ids") ids: String,
    ): NeteaseSongDetailResponse

    @Headers(
        "User-Agent: Mozilla/5.0",
        "Referer: https://music.163.com/"
    )
    @GET("/api/song/lyric")
    suspend fun lyric(
        @Query("id") id: Long,
        @Query("lv") lyricVersion: Int = 1,
        @Query("kv") karaokeVersion: Int = 1,
        @Query("tv") translationVersion: Int = -1,
    ): NeteaseLyricResponse
}

data class NeteaseSearchResponse(
    val result: NeteaseSearchResult? = null,
    val code: Int = 0,
)

data class NeteaseSearchResult(
    val songs: List<NeteaseSong> = emptyList(),
    val songCount: Int = 0,
)

data class NeteaseLyricSearchResponse(
    val result: NeteaseLyricSearchResult? = null,
    val code: Int = 0,
)

data class NeteaseLyricSearchResult(
    val songs: List<NeteaseLyricSong> = emptyList(),
    val songCount: Int = 0,
)

data class NeteaseSongDetailResponse(
    val songs: List<NeteaseSongDetail> = emptyList(),
    val code: Int = 0,
)

data class NeteaseSong(
    val id: Long = 0L,
    val name: String? = null,
    val artists: List<NeteaseArtist> = emptyList(),
    val album: NeteaseAlbum? = null,
    val duration: Long = 0L,
    val transNames: List<String> = emptyList(),
    val alias: List<String> = emptyList(),
)

data class NeteaseSongDetail(
    val id: Long = 0L,
    val name: String? = null,
    val artists: List<NeteaseArtist> = emptyList(),
    val album: NeteaseAlbum? = null,
    val duration: Long = 0L,
    val transName: String? = null,
    val transNames: List<String> = emptyList(),
)

data class NeteaseLyricSong(
    val id: Long = 0L,
    val name: String? = null,
    val artists: List<NeteaseArtist> = emptyList(),
    val album: NeteaseAlbum? = null,
    val duration: Long = 0L,
    val lyrics: List<String> = emptyList(),
)

data class NeteaseArtist(
    val id: Long = 0L,
    val name: String? = null,
)

data class NeteaseAlbum(
    val id: Long = 0L,
    val name: String? = null,
    val picUrl: String? = null,
    val blurPicUrl: String? = null,
    val artists: List<NeteaseArtist> = emptyList(),
    val artist: NeteaseArtist? = null,
)

data class NeteaseLyricResponse(
    val lrc: NeteaseLyricBody? = null,
    val tlyric: NeteaseLyricBody? = null,
    val code: Int = 0,
)

data class NeteaseLyricBody(
    val lyric: String? = null,
)
