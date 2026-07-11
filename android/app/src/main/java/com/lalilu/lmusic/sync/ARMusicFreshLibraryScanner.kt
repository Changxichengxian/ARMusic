package com.lalilu.lmusic.sync

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ARMusicFreshSyncTrack(
    val uri: Uri,
    val displayName: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val identity: ARMusicTrackIdentity,
)

/** Reads MediaStore directly and hashes every eligible MP3 without LMedia or identity caches. */
class ARMusicFreshLibraryScanner(
    private val context: Application,
    private val audioIdentity: ARMusicAudioIdentity,
) {
    suspend fun scan(requireUnique: Boolean = true): List<ARMusicFreshSyncTrack> = withContext(Dispatchers.IO) {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.IS_PENDING)
            }
        }.toTypedArray()
        val failures = mutableListOf<String>()
        val tracks = mutableListOf<ARMusicFreshSyncTrack>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val pendingIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.IS_PENDING)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                if (pendingIndex >= 0 && cursor.getInt(pendingIndex) != 0) continue
                val displayName = cursor.getString(nameIndex).orEmpty()
                val durationMs = cursor.getLong(durationIndex)
                if (!displayName.endsWith(".mp3", ignoreCase = true) || durationMs < 15_000L) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idIndex),
                )
                runCatching {
                    tracks += ARMusicFreshSyncTrack(
                        uri = uri,
                        displayName = displayName,
                        durationSeconds = durationMs / 1000L,
                        sizeBytes = cursor.getLong(sizeIndex),
                        identity = audioIdentity.createUncached(uri, displayName),
                    )
                }.onFailure { error ->
                    failures += "$displayName：${error.message ?: error.javaClass.simpleName}"
                }
            }
        } ?: error("无法读取 Android MediaStore 音乐清单")

        check(failures.isEmpty()) {
            "有 ${failures.size} 首 MP3 无法重新校验，同步已中止：${failures.take(3).joinToString("；")}"
        }
        if (requireUnique) requireUniqueIdentities(tracks)
        tracks
    }

    private fun requireUniqueIdentities(tracks: List<ARMusicFreshSyncTrack>) {
        val byIdentity = mutableMapOf<String, ARMusicFreshSyncTrack>()
        tracks.forEach { track ->
            listOf(track.identity.stableId, track.identity.legacyId).forEach { id ->
                val existing = byIdentity.putIfAbsent(id, track)
                check(existing == null) {
                    "Android 曲库存在重复音频身份，无法安全发布：${existing?.displayName} / ${track.displayName}"
                }
            }
        }
    }
}

internal fun List<ARMusicFreshSyncTrack>.matching(track: ARMusicSyncTrack): List<ARMusicFreshSyncTrack> {
    val expected = (listOf(track.syncId) + track.legacySyncIds).filter(String::isNotBlank).toSet()
    return filter { local ->
        local.identity.stableId in expected || local.identity.legacyId in expected
    }
}
