package com.lalilu.lmedia.wrapper

import com.lalilu.lmedia.entity.Metadata


object Taglib {

    external suspend fun retrieveMetadataWithFD(fileDescriptor: Int): Metadata?
    external suspend fun getLyricWithFD(fileDescriptor: Int): String?
    external suspend fun getPictureWithFD(fileDescriptor: Int): ByteArray?

    // TODO 加suspend 会异常
    external fun writeLyricInto(fileDescriptor: Int, lyric: String): Boolean

    external fun writeMetadataWithFD(
        fileDescriptor: Int,
        title: String,
        album: String,
        artist: String,
        albumArtist: String,
        composer: String,
        lyricist: String,
        comment: String,
        genre: String,
        track: String,
        disc: String,
        date: String,
        sameSongGroup: String,
        lyric: String
    ): Boolean

    external fun writeCoverWithFD(
        fileDescriptor: Int,
        cover: ByteArray,
        mimeType: String
    ): Boolean

    external fun removeCoverWithFD(fileDescriptor: Int): Boolean
}
