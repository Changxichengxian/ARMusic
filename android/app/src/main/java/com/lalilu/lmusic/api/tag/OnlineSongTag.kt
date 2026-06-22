package com.lalilu.lmusic.api.tag

data class OnlineSongTag(
    val id: String,
    val source: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtist: String = "",
    val composer: String = "",
    val lyricist: String = "",
    val comment: String = "",
    val genre: String = "",
    val track: String = "",
    val disc: String = "",
    val year: String = "",
    val cover: String = "",
    val lyric: String = "",
    val lrcShareId: Int? = null,
    val neteaseId: Long? = null,
)
