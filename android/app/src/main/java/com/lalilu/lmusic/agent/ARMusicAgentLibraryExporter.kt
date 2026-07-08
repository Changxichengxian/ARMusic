package com.lalilu.lmusic.agent

import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.BuildConfig
import com.lalilu.lmusic.tag.SongGroupStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ARMusicAgentLibraryExporter(
    private val files: ARMusicAgentFiles,
    private val songWorkStore: SongWorkStore,
    private val songGroupStore: SongGroupStore,
) {
    suspend fun exportLibrary(outputPath: String): AgentCommandResult {
        awaitARMusicLibraryReady()
        val songs = LMedia.get<LSong>(blockFilter = false)
            .sortedWith(compareBy<LSong> { it.metadata.title.lowercase(Locale.ROOT) }
                .thenBy { it.metadata.artist.lowercase(Locale.ROOT) })

        val root = JSONObject()
            .put("schema", SCHEMA_LIBRARY)
            .put("packageName", BuildConfig.APPLICATION_ID)
            .put("exportedAt", System.currentTimeMillis())
            .put("defaultAgentDir", files.agentDir().absolutePath)
            .put("songs", JSONArray().also { array ->
                songs.forEach { song ->
                    array.put(
                        JSONObject()
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
                    )
                }
            })

        files.writeTextFile(outputPath, root.toString(2))

        return AgentCommandResult(
            ok = true,
            command = ARMusicAgentManager.COMMAND_EXPORT_LIBRARY,
            message = "Exported ${songs.size} songs to $outputPath",
            outputPath = outputPath,
            exportedSongs = songs.size,
        )
    }

    private companion object {
        const val SCHEMA_LIBRARY = "armusic-agent-library-v1"
    }
}
