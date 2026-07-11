package com.lalilu.lmusic.sync

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ARMusicTrackUploader(
    private val context: Application,
    private val manifestBuilder: ARMusicAndroidManifestBuilder,
    private val syncClient: ARMusicLanSyncClient,
) {
    suspend fun uploadToDesktop(
        baseUrl: String,
        track: ARMusicSyncTrack,
    ): Unit = withContext(Dispatchers.IO) {
        val localTrack = manifestBuilder.findLocalTrack(track.syncId)
            ?: error("Android 本地没有找到这首歌")

        context.contentResolver.openInputStream(localTrack.song.uri)?.use { input ->
            syncClient.uploadTrack(baseUrl, localTrack.track, input).getOrThrow()
        } ?: error("Android 本地无法读取这首歌")
    }

    suspend fun replaceOnDesktop(
        baseUrl: String,
        track: ARMusicSyncTrack,
        expectedDesktopRevision: String,
    ): Unit = withContext(Dispatchers.IO) {
        val localTrack = manifestBuilder.findLocalTrack(track.syncId)
            ?: error("Android 本地没有找到这首歌")
        context.contentResolver.openInputStream(localTrack.song.uri)?.use { input ->
            syncClient.replaceTrack(
                baseUrl,
                localTrack.track,
                input,
                expectedDesktopRevision,
            ).getOrThrow()
        } ?: error("Android 本地无法读取这首歌")
    }
}
