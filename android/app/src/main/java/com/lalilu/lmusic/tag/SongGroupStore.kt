package com.lalilu.lmusic.tag

import android.app.Application
import com.blankj.utilcode.util.LogUtils
import com.lalilu.lhistory.HistoryStatIdResolver
import com.lalilu.lhistory.HistoryStatIdentity
import com.lalilu.lhistory.HistoryMutationCoordinator
import com.lalilu.lhistory.repository.HistoryDao
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.sync.ARMusicAudioIdentity
import com.lalilu.lmusic.sync.ARMusicHistoryIdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.CoroutineContext

class SongGroupStore(
    application: Application,
    private val historyDao: HistoryDao,
    private val historyMutationCoordinator: HistoryMutationCoordinator,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    private val sp = application.getSharedPreferences("armusic_song_groups", Application.MODE_PRIVATE)

    init {
        LMedia.whenReady {
            launch { repairHistoryParentsForKnownGroups() }
        }
    }

    fun getGroup(song: LSong): String {
        return song.metadata.sameSongGroup.trim().takeIf(String::isNotEmpty)
            ?: sp.getString(idKey(song.id), null)
            ?: song.fileInfo.pathStr
                ?.takeIf { it.isNotBlank() && it != "<unknown_path>" }
                ?.let { sp.getString(pathKey(it), null) }
            ?: ""
    }

    fun setGroup(song: LSong, group: String) {
        val normalized = group.trim()
        sp.edit().apply {
            if (normalized.isBlank()) {
                remove(idKey(song.id))
                song.fileInfo.pathStr?.takeIf { it.isNotBlank() }?.let { remove(pathKey(it)) }
            } else {
                putString(idKey(song.id), normalized)
                song.fileInfo.pathStr?.takeIf { it.isNotBlank() && it != "<unknown_path>" }?.let {
                    putString(pathKey(it), normalized)
                }
            }
        }.apply()

        val identity = if (normalized.isBlank()) {
            HistoryStatIdentity(id = song.id, title = song.name)
        } else {
            HistoryStatIdentity(
                id = "armusic-group:${normalized.statKey()}",
                title = normalized,
            )
        }
        launch {
            historyMutationCoordinator.withMutation {
                historyDao.updateParentForContentId(
                    contentId = song.id,
                    parentId = identity.id.takeIf { it != song.id }.orEmpty(),
                    parentTitle = identity.title.takeIf { identity.id != song.id }.orEmpty(),
                )
            }
        }
    }

    fun resolve(mediaId: String, title: String): HistoryStatIdentity {
        val song = LMedia.get<LSong>(mediaId)
        val group = song?.let(::getGroup).orEmpty().trim()
        if (group.isBlank()) return HistoryStatIdentity(id = mediaId, title = title)

        return HistoryStatIdentity(
            id = "armusic-group:${group.statKey()}",
            title = group,
        )
    }

    private fun idKey(mediaId: String): String = "id:$mediaId"
    private fun pathKey(path: String): String = "path:$path"

    private suspend fun repairHistoryParentsForKnownGroups() {
        runCatching {
            historyMutationCoordinator.withMutation {
                LMedia.get<LSong>(blockFilter = false)
                    .forEach { song ->
                        val group = getGroup(song).trim()
                        if (group.isBlank()) return@forEach

                        val identity = HistoryStatIdentity(
                            id = "armusic-group:${group.statKey()}",
                            title = group,
                        )
                        historyDao.updateParentForContentId(
                            contentId = song.id,
                            parentId = identity.id,
                            parentTitle = identity.title,
                        )
                    }
            }
        }.onFailure {
            LogUtils.e("[ARMusic] Failed to repair song-group histories.", it)
        }
    }

    private fun String.statKey(): String {
        return trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
    }
}

class ARMusicHistoryStatIdResolver(
    private val songGroupStore: SongGroupStore,
    private val audioIdentity: ARMusicAudioIdentity,
    private val historyIdentityStore: ARMusicHistoryIdentityStore,
) : HistoryStatIdResolver {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun resolve(mediaId: String, title: String): HistoryStatIdentity {
        scope.launch {
            runCatching {
                val song = LMedia.get<LSong>(blockFilter = false)
                    .firstOrNull { it.id == mediaId }
                    ?: return@runCatching
                val fileName = song.fileInfo.fileName
                    ?: song.fileInfo.pathStr?.substringAfterLast('/')
                    ?: return@runCatching
                historyIdentityStore.remember(
                    mediaId = mediaId,
                    title = title.ifBlank { song.name },
                    stableId = audioIdentity.createUncached(song.uri, fileName).stableId,
                )
            }.onFailure {
                LogUtils.w("[ARMusic] Failed to remember playback audio identity.", it)
            }
        }
        return songGroupStore.resolve(mediaId = mediaId, title = title)
    }
}
