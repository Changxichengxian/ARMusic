package com.lalilu.lhistory

import androidx.compose.runtime.Composable
import com.lalilu.lhistory.repository.HistoryRepository
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.extension.ListAction
import org.koin.compose.koinInject

@Composable
fun historySortMetricText(
    song: LSong,
    selectedSortAction: ListAction,
    historyRepository: HistoryRepository = koinInject(),
): String? {
    return when (selectedSortAction.actionKey) {
        HistorySortActionKeys.PLAY_COUNT -> {
            val count = historyRepository.getHistoriesCountByMediaId(song.id)
            count.takeIf { it > 0 }?.let { "播放 $it 次" }
        }

        HistorySortActionKeys.PLAY_DURATION -> {
            val duration = historyRepository.getHistoriesDurationByMediaId(song.id)
            duration.takeIf { it > 0L }?.let { "听了 ${it.toHistoryDurationText()}" }
        }

        else -> null
    }
}

private fun Long.toHistoryDurationText(): String {
    val totalSeconds = this / 1000L
    val day = totalSeconds / 86400L
    val hour = totalSeconds / 3600L % 24L
    val minute = totalSeconds / 60L % 60L
    val second = totalSeconds % 60L

    return if (day > 0L) {
        buildString {
            append(day).append("天")
            if (hour > 0L) append(hour).append("小时")
            if (minute > 0L) append(minute).append("分")
            if (second > 0L) append(second).append("秒")
        }
    } else if (hour > 0L) {
        "%d:%02d:%02d".format(hour, minute, second)
    } else {
        "%d:%02d".format(minute, second)
    }
}
