package com.lalilu.lmusic.sync

import kotlinx.serialization.Serializable

@Serializable
data class ARMusicSyncHealth(
    val ok: Boolean = false,
    val name: String = "",
    val time: String = "",
)

@Serializable
data class ARMusicSyncTrack(
    val syncId: String,
    val legacySyncIds: List<String> = emptyList(),
    val revisionHash: String? = null,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Long = 0,
    val sizeBytes: Long = 0,
    val relativePath: String,
    val modifiedAt: String? = null,
    val playSeconds: Long = 0,
    val lastPlayedAt: String? = null,
    val source: String = "desktop",
)

@Serializable
data class ARMusicSyncManifest(
    val libraryId: String,
    val deviceName: String,
    val generatedAt: String,
    val tracks: List<ARMusicSyncTrack> = emptyList(),
    val ignoredTracks: List<ARMusicIgnoredSyncTrack> = emptyList(),
)

@Serializable
data class ARMusicIgnoredSyncTrack(
    val title: String,
    val relativePath: String,
    val durationSeconds: Long,
    val reason: String,
    val source: String,
)

data class ARMusicSyncPeer(
    val baseUrl: String,
    val name: String = baseUrl,
)

@Serializable
data class ARMusicListeningSession(
    val eventId: String,
    val syncId: String,
    val sourceDevice: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val repeatCount: Int = 0,
    val mediaId: String? = null,
    val contentTitle: String? = null,
)

@Serializable
data class ARMusicRawHistory(
    val id: Long,
    val contentId: String,
    val contentTitle: String,
    val parentId: String = "",
    val parentTitle: String = "",
    val duration: Long,
    val repeatCount: Int = 0,
    val startTime: Long,
)

@Serializable
data class ARMusicHistoryPayload(
    val schema: String = "armusic-listening-history-v2",
    val deviceId: String,
    val generatedAt: String,
    val snapshotId: String,
    val snapshotComplete: Boolean = true,
    val rawHistoryCount: Int = 0,
    val rawSnapshotId: String = "",
    val rawHistories: List<ARMusicRawHistory> = emptyList(),
    val coveredSyncIds: List<String> = emptyList(),
    val sessions: List<ARMusicListeningSession> = emptyList(),
)

@Serializable
data class ARMusicHistoryMergeResult(
    val added: Int = 0,
    val updated: Int = 0,
    val duplicates: Int = 0,
    val removedProvisional: Int = 0,
    val totalSessions: Int = 0,
    val totalDurationMs: Long = 0,
    val snapshotId: String = "",
    val persisted: Boolean = false,
)

@Serializable
data class ARMusicHistoryMergeResponse(
    val result: ARMusicHistoryMergeResult,
    val history: ARMusicHistoryPayload,
)
