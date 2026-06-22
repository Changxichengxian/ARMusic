package com.lalilu.lhistory

import com.lalilu.lhistory.repository.HistoryRepository
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.entity.MusicParent
import com.lalilu.lmedia.extension.GroupIdentity
import com.lalilu.lmedia.extension.ListAction
import com.lalilu.lmedia.extension.SortDynamicAction
import com.lalilu.lmedia.extension.Sortable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

object HistorySortActionKeys {
    const val PLAY_COUNT = "history.play_count"
    const val LAST_PLAY_TIME = "history.last_play_time"
    const val PLAY_DURATION = "history.play_duration"
}

@Named("sort_rule_play_count")
@Single(binds = [ListAction::class])
class SortRulePlayCount(
    private val historyRepo: HistoryRepository,
    private val statIdResolver: HistoryStatIdResolver,
) : SortDynamicAction(titleRes = R.string.sort_preset_by_played_times) {
    override val actionKey: String = HistorySortActionKeys.PLAY_COUNT

    override fun <T : Sortable> doSort(
        items: Flow<List<T>>,
        reverse: Boolean
    ): Flow<Map<GroupIdentity, List<T>>> {
        return historyRepo
            .getHistoriesStatIdsMapWithCount()
            .combine(items) { map, sources ->
                sources.sortedByDescending { item ->
                    item.historyStatIds().sumOf { map[it] ?: 0 }
                }
                    .let { if (reverse) it.reversed() else it }
                    .let { mapOf(GroupIdentity.None to it) }
            }
    }

    private fun <T : Sortable> T.historyStatIds(): List<String> = historyStatIds(statIdResolver)
}

@Named("sort_rule_last_play_time")
@Single(binds = [ListAction::class])
class SortRuleLastPlayTime(
    private val historyRepo: HistoryRepository,
    private val statIdResolver: HistoryStatIdResolver,
) : SortDynamicAction(titleRes = R.string.sort_preset_by_last_play_time) {
    override val actionKey: String = HistorySortActionKeys.LAST_PLAY_TIME

    override fun <T : Sortable> doSort(
        items: Flow<List<T>>,
        reverse: Boolean
    ): Flow<Map<GroupIdentity, List<T>>> {
        return historyRepo
            .getHistoriesStatIdsMapWithLastTime()
            .combine(items) { map, sources ->
                sources.sortedByDescending { item ->
                    item.historyStatIds().maxOfOrNull { map[it] ?: 0L } ?: 0L
                }
                    .let { if (reverse) it.reversed() else it }
                    .let { mapOf(GroupIdentity.None to it) }
            }
    }

    private fun <T : Sortable> T.historyStatIds(): List<String> = historyStatIds(statIdResolver)
}

@Named("sort_rule_play_duration")
@Single(binds = [ListAction::class])
class SortRulePlayDuration(
    private val historyRepo: HistoryRepository,
    private val statIdResolver: HistoryStatIdResolver,
) : SortDynamicAction(titleRes = R.string.sort_preset_by_play_duration) {
    override val actionKey: String = HistorySortActionKeys.PLAY_DURATION

    override fun <T : Sortable> doSort(
        items: Flow<List<T>>,
        reverse: Boolean
    ): Flow<Map<GroupIdentity, List<T>>> {
        return historyRepo
            .getHistoriesStatIdsMapWithDuration()
            .combine(items) { map, sources ->
                sources.sortedByDescending { item ->
                    item.historyStatIds().sumOf { map[it] ?: 0L }
                }
                    .let { if (reverse) it.reversed() else it }
                    .let { mapOf(GroupIdentity.None to it) }
            }
    }

    private fun <T : Sortable> T.historyStatIds(): List<String> = historyStatIds(statIdResolver)
}

private fun Sortable.historyStatIds(statIdResolver: HistoryStatIdResolver): List<String> {
    return when (this) {
        is LSong -> listOf(statIdResolver.resolve(id, name).id, id).distinct()
        is MusicParent -> songs.flatMap {
            listOf(statIdResolver.resolve(it.id, it.name).id, it.id)
        }.distinct()
        else -> {
            val mediaId = getValueBy<String>(Sortable.COMPARE_KEY_ID) ?: return emptyList()
            val title = getValueBy<String>(Sortable.COMPARE_KEY_TITLE) ?: mediaId
            listOf(statIdResolver.resolve(mediaId, title).id, mediaId).distinct()
        }
    }
}
