package com.lalilu.lhistory

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
            count.takeIf { it > 0 }?.let {
                stringResource(id = R.string.history_metric_play_count, it)
            }
        }

        HistorySortActionKeys.PLAY_DURATION -> {
            val duration = historyRepository.getHistoriesDurationByMediaId(song.id)
            duration.takeIf { it > 0L }?.let {
                stringResource(
                    id = R.string.history_metric_play_duration,
                    it.toHistoryDurationText()
                )
            }
        }

        else -> null
    }
}

@Composable
private fun Long.toHistoryDurationText(): String {
    val totalSeconds = this / 1000L
    val day = totalSeconds / 86400L
    val hour = totalSeconds / 3600L % 24L
    val minute = totalSeconds / 60L % 60L
    val second = totalSeconds % 60L

    return if (day > 0L) {
        buildString {
            append(stringResource(id = R.string.history_duration_day, day))
            if (hour > 0L) append(stringResource(id = R.string.history_duration_hour, hour))
            if (minute > 0L) append(stringResource(id = R.string.history_duration_minute, minute))
            if (second > 0L) append(stringResource(id = R.string.history_duration_second, second))
        }
    } else if (hour > 0L) {
        "%d:%02d:%02d".format(hour, minute, second)
    } else {
        "%d:%02d".format(minute, second)
    }
}
