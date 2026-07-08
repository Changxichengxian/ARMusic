package com.lalilu.lplaylist.repository

import com.lalilu.lplaylist.entity.LPlaylist
import com.lalilu.lplaylist.entity.sanitizePlaylists
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import org.koin.core.annotation.Single

@Single(binds = [PlaylistRepository::class])
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaylistRepositoryImpl : PlaylistRepository {

    override fun getPlaylistsFlow(): Flow<List<LPlaylist>> {
        return PlaylistKV.playlistList.flow()
            .mapLatest { playlists ->
                sanitizeAndPersist(playlists)
            }
    }

    override fun getPlaylists(): List<LPlaylist> {
        return sanitizeAndPersist(runCatching { PlaylistKV.playlistList.value }.getOrNull())
    }

    override fun setPlaylists(playlists: List<LPlaylist>) {
        PlaylistKV.playlistList.apply {
            value = playlists.sanitizePlaylists()
            if (!autoSave) save()
        }
    }

    override fun save(playlist: LPlaylist) {
        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlist.id }

        // 若已存在则更新
        if (index >= 0) {
            playlists[index] = playlist
            setPlaylists(playlists)
            return
        }

        playlists.add(0, playlist)
        setPlaylists(playlists)
    }

    override fun remove(playlist: LPlaylist) {
        val playlists = getPlaylists().toMutableList()
        playlists.remove(playlist)
        setPlaylists(playlists)
    }

    override fun removeById(id: String) {
        // 筛选不用删除的元素
        val result = getPlaylists().filter { it.id != id }
        setPlaylists(result)
    }

    override fun removeByIds(ids: List<String>) {
        // 筛选不用删除的元素
        val result = getPlaylists().filter { it.id !in ids }
        setPlaylists(result)
    }

    override fun isExist(playlistId: String): Boolean {
        return getPlaylists().any { it.id == playlistId }
    }

    override fun isExistInPlaylist(playlistId: String, mediaId: String): Boolean {
        val playlists = getPlaylists()
        val playlist = playlists.firstOrNull { it.id == playlistId } ?: return false
        return playlist.mediaIds.contains(mediaId)
    }

    override fun updateMediaIdsToPlaylist(mediaIds: List<String>, playlistId: String) {
        updatePlaylist(playlistId) { it.copy(mediaIds = mediaIds.distinct()) }
    }

    override fun addMediaIdsToPlaylist(mediaIds: List<String>, playlistId: String) {
        updatePlaylist(playlistId) { it.copy(mediaIds = mediaIds.plus(it.mediaIds).distinct()) }
    }

    override fun addMediaIdsToPlaylists(mediaIds: List<String>, playlistIds: List<String>) {
        var changed = false
        val playlists = getPlaylists().toMutableList()

        for (index in playlists.indices) {
            val playlist = playlists[index]
            val playlistId = playlist.id
            val exist = playlistIds.any { it == playlistId }
            if (!exist) continue

            val mediaIdsSet = playlist.mediaIds.toHashSet()
                .also {
                    changed = true
                    it.addAll(mediaIds)
                }

            playlists[index] = playlist.copy(mediaIds = mediaIdsSet.toList())
        }

        if (!changed) return
        setPlaylists(playlists)
    }

    override fun removeMediaIdsFromPlaylist(mediaIds: List<String>, playlistId: String) {
        updatePlaylist(playlistId) { it.copy(mediaIds = it.mediaIds.minus(mediaIds.toSet())) }
    }

    override fun removeMediaIdsFromPlaylists(mediaIds: List<String>, playlistIds: List<String>) {
        var changed = false
        val playlists = getPlaylists().toMutableList()

        for (index in playlists.indices) {
            val playlist = playlists[index]
            val playlistId = playlist.id
            val exist = playlistIds.any { it == playlistId }
            if (!exist) continue

            changed = true
            val newMediaIds = playlist.mediaIds.minus(mediaIds.toSet())

            playlists[index] = playlist.copy(mediaIds = newMediaIds)
        }

        if (!changed) return
        setPlaylists(playlists)
    }

    override fun relinkMediaId(oldMediaId: String, newMediaId: String): Int {
        if (oldMediaId.isBlank() || newMediaId.isBlank() || oldMediaId == newMediaId) return 0

        var changedCount = 0
        val playlists = getPlaylists().map { playlist ->
            if (oldMediaId !in playlist.mediaIds) return@map playlist

            changedCount += 1
            playlist.copy(
                mediaIds = playlist.mediaIds
                    .map { if (it == oldMediaId) newMediaId else it }
                    .distinct(),
                modifyTime = System.currentTimeMillis(),
            )
        }

        if (changedCount > 0) setPlaylists(playlists)
        return changedCount
    }

    private fun updatePlaylist(playlistId: String, action: (LPlaylist) -> LPlaylist) {
        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return

        playlists[index] = action(playlists[index])
        setPlaylists(playlists)
    }

    private fun sanitizeAndPersist(playlists: List<LPlaylist>?): List<LPlaylist> {
        val cleaned = playlists.sanitizePlaylists()
        if (cleaned != playlists.orEmpty()) {
            PlaylistKV.playlistList.apply {
                value = cleaned
                if (!autoSave) save()
            }
        }
        return cleaned
    }
}
