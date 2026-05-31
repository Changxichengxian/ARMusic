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
)

data class ARMusicSyncPeer(
    val baseUrl: String,
    val name: String = baseUrl,
)
