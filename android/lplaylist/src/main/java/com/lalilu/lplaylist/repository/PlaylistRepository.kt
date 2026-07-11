package com.lalilu.lplaylist.repository

import com.lalilu.lplaylist.entity.LPlaylist
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun getPlaylistsFlow(): Flow<List<LPlaylist>>
    fun getPlaylists(): List<LPlaylist>
    fun setPlaylists(playlists: List<LPlaylist>)
    /** Runs a read-modify-write under the same process-wide lock used by every playlist edit. */
    fun mutatePlaylists(action: (List<LPlaylist>) -> List<LPlaylist>): List<LPlaylist>

    fun save(playlist: LPlaylist)
    fun remove(playlist: LPlaylist)
    fun removeById(id: String)
    fun removeByIds(ids: List<String>)
    fun isExist(playlistId: String): Boolean
    fun isExistInPlaylist(playlistId: String, mediaId: String): Boolean

    fun updateMediaIdsToPlaylist(mediaIds: List<String>, playlistId: String)
    fun addMediaIdsToPlaylist(mediaIds: List<String>, playlistId: String)
    fun addMediaIdsToPlaylists(mediaIds: List<String>, playlistIds: List<String>)
    fun removeMediaIdsFromPlaylist(mediaIds: List<String>, playlistId: String)
    fun removeMediaIdsFromPlaylists(mediaIds: List<String>, playlistIds: List<String>)
    fun relinkMediaId(oldMediaId: String, newMediaId: String): Int
}
