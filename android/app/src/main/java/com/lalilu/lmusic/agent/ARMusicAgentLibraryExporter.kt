package com.lalilu.lmusic.agent

import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.BuildConfig
import com.lalilu.lmusic.sync.ARMusicAndroidManifestBuilder
import com.lalilu.lmusic.tag.SongGroupStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ARMusicAgentLibraryExporter(
    private val files: ARMusicAgentFiles,
    private val songWorkStore: SongWorkStore,
    private val songGroupStore: SongGroupStore,
    private val manifestBuilder: ARMusicAndroidManifestBuilder,
) {
    suspend fun exportLibrary(outputPath: String): AgentCommandResult {
        Log.i(LOG_TAG, "exportLibrary waiting for LMedia")
        awaitARMusicLibraryReady()
        Log.i(LOG_TAG, "exportLibrary LMedia ready; building manifest")
        val library = manifestBuilder.buildSyncLibrary()
        Log.i(LOG_TAG, "exportLibrary manifest built: ${library.eligible.size} eligible")
        val songs = library.eligible
            .sortedWith(compareBy { it.song.metadata.title.lowercase(Locale.ROOT) })

        val root = JSONObject()
            .put("schema", SCHEMA_LIBRARY)
            .put("packageName", BuildConfig.APPLICATION_ID)
            .put("exportedAt", System.currentTimeMillis())
            .put("defaultAgentDir", files.agentDir().absolutePath)
            .put("ignoredSongs", JSONArray().also { array ->
                library.ignored.forEach { ignored ->
                    array.put(
                        JSONObject()
                            .put("title", ignored.title)
                            .put("relativePath", ignored.relativePath)
                            .put("durationSeconds", ignored.durationSeconds)
                            .put("reason", ignored.reason)
                    )
                }
            })
            .put("songs", JSONArray().also { array ->
                songs.forEach { local ->
                    val song = local.song
                    val track = local.track
                    array.put(
                        JSONObject()
                            .put("syncId", track.syncId)
                            .put("legacySyncIds", JSONArray(track.legacySyncIds))
                            .put("revisionHash", track.revisionHash.orEmpty())
                            .put("mediaId", song.id)
                            .put("title", song.metadata.title)
                            .put("artist", song.metadata.artist)
                            .put("album", song.metadata.album)
                            .put("work", songWorkStore.getWork(song))
                            .put("sameSongGroup", songGroupStore.getGroup(song))
                            .put("durationMs", song.metadata.duration)
                            .put("filePath", song.fileInfo.pathStr.orEmpty())
                            .put("directoryPath", song.fileInfo.directoryPath)
                            .put("mimeType", song.fileInfo.mimeType)
                            .put("sizeBytes", track.sizeBytes)
                            .put("modifiedAt", track.modifiedAt.orEmpty())
                            .put("relativePath", track.relativePath)
                    )
                }
            })

        files.writeTextFile(outputPath, root.toString(2))

        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_EXPORT_LIBRARY,
            message = "Exported ${songs.size} songs to $outputPath; ignored ${library.ignored.size} unsupported songs",
            outputPath = outputPath,
            exportedSongs = songs.size,
        )
    }

    private companion object {
        const val LOG_TAG = "ARMusicAgent"
        const val SCHEMA_LIBRARY = "armusic-agent-library-v1"
    }
}
