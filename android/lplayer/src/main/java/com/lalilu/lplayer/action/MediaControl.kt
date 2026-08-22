package com.lalilu.lplayer.action

import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lplayer.MPlayer
import kotlin.random.Random

/**
 * 常用的媒体操作的方法封装
 */
object MediaControl {

    /**
     * 将当前元素添加进播放列表的下一位置，并开始播放
     * 若当前播放歌曲就是目标歌曲，则暂停或播放
     */
    fun addAndPlay(mediaId: String) {
        // 将当前元素添加进播放列表的下一位置，若已存在则不移动
        PlayerAction.AddToNext(mediaId).action()

        if (MPlayer.currentMediaItem?.mediaId == mediaId) {
            PlayerAction.PlayOrPause.action()
        } else {
            PlayerAction.PlayById(mediaId = mediaId)
                .action()
        }
    }

    /**
     * 替换播放列表，并播放目标歌曲
     */
    fun playWithList(
        mediaIds: List<String>,
        mediaId: String,
        start: Boolean = true
    ) {
        PlayerAction
            .UpdateList(mediaIds, mediaId, start)
            .action()
    }

    /**
     * Starts a discovery-style queue from the selected song, then continues through the whole
     * library without duplicate media ids. Album and playlist callers should keep using
     * [playWithList] so their explicit playback scope is preserved.
     */
    fun playFromLibrary(mediaId: String) {
        val queue = buildLibraryPlaybackQueue(
            libraryIds = LMedia.get<LSong>().map(LSong::id),
            selectedId = mediaId,
            random = Random.Default,
        )
        if (queue.isEmpty()) return

        PlayerAction.UpdateList(queue, mediaId, start = true).action()
    }
}

internal fun buildLibraryPlaybackQueue(
    libraryIds: List<String>,
    selectedId: String,
    random: Random,
): List<String> {
    val uniqueIds = libraryIds
        .asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (selectedId !in uniqueIds) return uniqueIds.shuffled(random)

    return buildList(uniqueIds.size) {
        add(selectedId)
        addAll(uniqueIds.filterNot { it == selectedId }.shuffled(random))
    }
}
