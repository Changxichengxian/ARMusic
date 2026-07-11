package com.lalilu.lmusic.agent

import java.security.MessageDigest

/**
 * The byte-level playlist protocol shared with the desktop Rust implementation. Keep field order,
 * UTF-8 byte lengths, unsigned-compatible non-negative longs and the 128-bit display prefix in
 * lockstep with desktop/src-tauri/src/playlists.rs.
 */
internal fun armusicPlaylistSnapshotId(
    playlists: List<PlaylistRecord>,
    tombstones: List<PlaylistTombstone>,
    removedTracks: List<PlaylistTrackTombstone>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("armusic-playlists-v1\u0000".toByteArray(Charsets.UTF_8))
    updatePlaylistLong(digest, playlists.size.toLong())
    playlists.forEach { playlist ->
        updatePlaylistField(digest, playlist.id)
        updatePlaylistField(digest, playlist.title)
        updatePlaylistField(digest, playlist.subTitle)
        updatePlaylistField(digest, playlist.coverUri)
        updatePlaylistLong(digest, playlist.createTime)
        updatePlaylistLong(digest, playlist.modifyTime)
        updatePlaylistLong(digest, playlist.trackIds.size.toLong())
        playlist.trackIds.forEach { updatePlaylistField(digest, it) }
    }
    updatePlaylistLong(digest, tombstones.size.toLong())
    tombstones.forEach { tombstone ->
        updatePlaylistField(digest, tombstone.id)
        updatePlaylistLong(digest, tombstone.deletedAt)
    }
    updatePlaylistLong(digest, removedTracks.size.toLong())
    removedTracks.forEach { tombstone ->
        updatePlaylistField(digest, tombstone.playlistId)
        updatePlaylistField(digest, tombstone.trackId)
        updatePlaylistLong(digest, tombstone.removedAt)
    }
    return "playlists-sha256-${digest.digest().toHex().take(32)}"
}

private fun updatePlaylistField(digest: MessageDigest, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    updatePlaylistLong(digest, bytes.size.toLong())
    digest.update(bytes)
}

private fun updatePlaylistLong(digest: MessageDigest, value: Long) {
    require(value >= 0L) { "歌单时间或数量不能为负数" }
    for (shift in 56 downTo 0 step 8) {
        digest.update(((value ushr shift) and 0xFF).toByte())
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

internal data class PlaylistRecord(
    val id: String,
    val title: String,
    val subTitle: String,
    val coverUri: String,
    val createTime: Long,
    val modifyTime: Long,
    val trackIds: List<String>,
)

internal data class PlaylistTombstone(val id: String, val deletedAt: Long)

internal data class PlaylistTrackTombstone(
    val playlistId: String,
    val trackId: String,
    val removedAt: Long,
)
