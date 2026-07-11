package com.lalilu.lmusic.sync

data class ARMusicSyncPlan(
    val download: List<ARMusicSyncTrack>,
    val upload: List<ARMusicSyncTrack>,
    val conflicts: List<ARMusicSyncConflict>,
    val ignoredLocal: List<ARMusicSyncTrack> = emptyList(),
    val ignoredRemote: List<ARMusicSyncTrack> = emptyList(),
) {
    val totalChanges: Int
        get() = download.size + upload.size + conflicts.size
}

data class ARMusicSyncConflict(
    val local: ARMusicSyncTrack,
    val remote: ARMusicSyncTrack,
    val recommendedResolution: ARMusicConflictResolution?,
)

enum class ARMusicConflictResolution {
    DESKTOP_TO_ANDROID,
    ANDROID_TO_DESKTOP,
    SKIP,
}

object ARMusicSyncPlanner {
    fun buildPlan(
        localTracks: List<ARMusicSyncTrack>,
        remoteTracks: List<ARMusicSyncTrack>,
    ): ARMusicSyncPlan {
        val ignoredLocal = localTracks.filter { it.syncExclusionReason() != null }
        val ignoredRemote = remoteTracks.filter { it.syncExclusionReason() != null }
        val eligibleLocal = localTracks - ignoredLocal.toSet()
        val eligibleRemote = remoteTracks - ignoredRemote.toSet()
        val localById = eligibleLocal.identityIndex("Android")
        val remoteById = eligibleRemote.identityIndex("桌面端")
        val download = eligibleRemote.filter { remote -> remote.allIdentityIds().none(localById::containsKey) }
        val upload = eligibleLocal.filter { local -> local.allIdentityIds().none(remoteById::containsKey) }
        val conflicts = eligibleRemote.mapNotNull { remote ->
            val local = remote.allIdentityIds().firstNotNullOfOrNull(localById::get)
                ?: return@mapNotNull null
            if (local.hasMetadataConflict(remote)) {
                ARMusicSyncConflict(
                    local = local,
                    remote = remote,
                    recommendedResolution = recommendResolution(local, remote),
                )
            } else {
                null
            }
        }

        return ARMusicSyncPlan(
            download = download,
            upload = upload,
            conflicts = conflicts,
            ignoredLocal = ignoredLocal,
            ignoredRemote = ignoredRemote,
        )
    }

    private fun recommendResolution(
        local: ARMusicSyncTrack,
        remote: ARMusicSyncTrack,
    ): ARMusicConflictResolution? {
        val localTime = local.modifiedAt.toEpochMillisOrNull() ?: return null
        val remoteTime = remote.modifiedAt.toEpochMillisOrNull() ?: return null
        if (localTime == remoteTime) return null
        return if (remoteTime > localTime) {
            ARMusicConflictResolution.DESKTOP_TO_ANDROID
        } else {
            ARMusicConflictResolution.ANDROID_TO_DESKTOP
        }
    }

    private fun String?.toEpochMillisOrNull(): Long? {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) return null
        value.toLongOrNull()?.let { numeric ->
            return if (numeric < 100_000_000_000L) numeric * 1000L else numeric
        }
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun ARMusicSyncTrack.hasMetadataConflict(other: ARMusicSyncTrack): Boolean {
        return revisionHash != null && other.revisionHash != null && revisionHash != other.revisionHash ||
            title != other.title ||
            artist != other.artist ||
            album != other.album ||
            relativePath != other.relativePath
    }

    private fun ARMusicSyncTrack.allIdentityIds(): List<String> =
        (listOf(syncId) + legacySyncIds).filter(String::isNotBlank).distinct()

    private fun List<ARMusicSyncTrack>.identityIndex(side: String): Map<String, ARMusicSyncTrack> = buildMap {
        this@identityIndex.forEachIndexed { index, track ->
            track.allIdentityIds().forEach { id ->
                val existing = putIfAbsent(id, track)
                check(existing == null) {
                    "$side 清单存在重复音频身份，无法安全同步：${existing?.title} / ${track.title}（第 ${index + 1} 项）"
                }
            }
        }
    }
}
