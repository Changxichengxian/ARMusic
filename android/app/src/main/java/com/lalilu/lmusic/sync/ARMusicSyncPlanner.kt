package com.lalilu.lmusic.sync

data class ARMusicSyncPlan(
    val download: List<ARMusicSyncTrack>,
    val upload: List<ARMusicSyncTrack>,
    val conflicts: List<ARMusicSyncConflict>,
) {
    val totalChanges: Int
        get() = download.size + upload.size + conflicts.size
}

data class ARMusicSyncConflict(
    val local: ARMusicSyncTrack,
    val remote: ARMusicSyncTrack,
)

object ARMusicSyncPlanner {
    fun buildPlan(
        localTracks: List<ARMusicSyncTrack>,
        remoteTracks: List<ARMusicSyncTrack>,
    ): ARMusicSyncPlan {
        val localById = localTracks.associateBy { it.syncId }
        val remoteById = remoteTracks.associateBy { it.syncId }
        val download = remoteTracks.filter { it.syncId !in localById }
        val upload = localTracks.filter { it.syncId !in remoteById }
        val conflicts = remoteTracks.mapNotNull { remote ->
            val local = localById[remote.syncId] ?: return@mapNotNull null
            if (local.hasMetadataConflict(remote)) {
                ARMusicSyncConflict(local = local, remote = remote)
            } else {
                null
            }
        }

        return ARMusicSyncPlan(
            download = download,
            upload = upload,
            conflicts = conflicts,
        )
    }

    private fun ARMusicSyncTrack.hasMetadataConflict(other: ARMusicSyncTrack): Boolean {
        return title != other.title ||
            artist != other.artist ||
            album != other.album ||
            relativePath != other.relativePath
    }
}
