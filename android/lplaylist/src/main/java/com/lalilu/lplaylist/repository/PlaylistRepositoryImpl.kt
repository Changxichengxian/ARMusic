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
                playlists.sanitizePlaylists()
            }
    }

    override fun getPlaylists(): List<LPlaylist> {
        return synchronized(MUTATION_LOCK) {
            sanitizeAndPersist(runCatching { PlaylistKV.playlistList.value }.getOrNull())
        }
    }

    override fun setPlaylists(playlists: List<LPlaylist>) {
        synchronized(MUTATION_LOCK) {
            persist(playlists.sanitizePlaylists())
        }
    }

    override fun mutatePlaylists(action: (List<LPlaylist>) -> List<LPlaylist>): List<LPlaylist> {
        return synchronized(MUTATION_LOCK) {
            val current = sanitizeAndPersist(runCatching { PlaylistKV.playlistList.value }.getOrNull())
            val next = action(current).sanitizePlaylists()
            persist(next)
            sanitizeAndPersist(runCatching { PlaylistKV.playlistList.value }.getOrNull())
        }
    }

    override fun save(playlist: LPlaylist) {
        mutatePlaylists { source ->
            val playlists = source.toMutableList()
            val index = playlists.indexOfFirst { it.id == playlist.id }
            val now = System.currentTimeMillis()
            val updated = playlist.copy(
                createTime = source.getOrNull(index)?.createTime ?: playlist.createTime,
                modifyTime = now,
            )
            if (index >= 0) playlists[index] = updated else playlists.add(0, updated)
            playlists
        }
    }

    override fun remove(playlist: LPlaylist) {
        mutatePlaylists { it.filterNot { item -> item.id == playlist.id } }
    }

    override fun removeById(id: String) {
        // 筛选不用删除的元素
        mutatePlaylists { it.filter { item -> item.id != id } }
    }

    override fun removeByIds(ids: List<String>) {
        // 筛选不用删除的元素
        mutatePlaylists { it.filter { item -> item.id !in ids } }
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
        updatePlaylist(playlistId) { it.copy(mediaIds = mediaIds.distinct(), modifyTime = System.currentTimeMillis()) }
    }

    override fun addMediaIdsToPlaylist(mediaIds: List<String>, playlistId: String) {
        updatePlaylist(playlistId) { it.copy(mediaIds = mediaIds.plus(it.mediaIds).distinct(), modifyTime = System.currentTimeMillis()) }
    }

    override fun addMediaIdsToPlaylists(mediaIds: List<String>, playlistIds: List<String>) {
        mutatePlaylists { source ->
            source.map { playlist ->
                if (playlist.id !in playlistIds) playlist else playlist.copy(
                    mediaIds = (mediaIds + playlist.mediaIds).distinct(),
                    modifyTime = System.currentTimeMillis(),
                )
            }
        }
    }

    override fun removeMediaIdsFromPlaylist(mediaIds: List<String>, playlistId: String) {
        updatePlaylist(playlistId) { it.copy(mediaIds = it.mediaIds.minus(mediaIds.toSet()), modifyTime = System.currentTimeMillis()) }
    }

    override fun removeMediaIdsFromPlaylists(mediaIds: List<String>, playlistIds: List<String>) {
        mutatePlaylists { source ->
            source.map { playlist ->
                if (playlist.id !in playlistIds) playlist else playlist.copy(
                    mediaIds = playlist.mediaIds.minus(mediaIds.toSet()),
                    modifyTime = System.currentTimeMillis(),
                )
            }
        }
    }

    override fun relinkMediaId(oldMediaId: String, newMediaId: String): Int {
        if (oldMediaId.isBlank() || newMediaId.isBlank() || oldMediaId == newMediaId) return 0

        var changedCount = 0
        mutatePlaylists { source ->
            source.map { playlist ->
                if (oldMediaId !in playlist.mediaIds) return@map playlist
                changedCount += 1
                playlist.copy(
                    mediaIds = playlist.mediaIds
                        .map { if (it == oldMediaId) newMediaId else it }
                        .distinct(),
                    modifyTime = System.currentTimeMillis(),
                )
            }
        }
        return changedCount
    }

    private fun updatePlaylist(playlistId: String, action: (LPlaylist) -> LPlaylist) {
        mutatePlaylists { source ->
            val playlists = source.toMutableList()
            val index = playlists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 }
                ?: return@mutatePlaylists source
            playlists[index] = action(playlists[index])
            playlists
        }
    }

    private fun sanitizeAndPersist(playlists: List<LPlaylist>?): List<LPlaylist> {
        return synchronized(MUTATION_LOCK) {
            val cleaned = playlists.sanitizePlaylists()
            if (cleaned != playlists.orEmpty()) {
                PlaylistKV.playlistList.apply {
                    value = cleaned
                    if (!autoSave) save()
                }
            }
            cleaned
        }
    }

    private fun persist(playlists: List<LPlaylist>) {
        PlaylistKV.playlistList.apply {
            value = playlists
            if (!autoSave) save()
        }
    }

    private companion object {
        val MUTATION_LOCK = Any()
    }
}
