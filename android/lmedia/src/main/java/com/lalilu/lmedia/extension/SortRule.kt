package com.lalilu.lmedia.extension

import androidx.annotation.CallSuper
import androidx.annotation.StringRes
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.TimeUtils
import com.lalilu.lmedia.R
import kotlinx.coroutines.flow.Flow
import java.io.Serializable
import java.text.Collator

interface Sortable {
    companion object {
        const val COMPARE_KEY_ID: String = "ID"
        const val COMPARE_KEY_TITLE: String = "TITLE"
        const val COMPARE_KEY_SUB_TITLE: String = "SUB_TITLE"
        const val COMPARE_KEY_CREATE_TIME: String = "CREATE_TIME"
        const val COMPARE_KEY_MODIFY_TIME: String = "MODIFY_TIME"
        const val COMPARE_KEY_ITEMS_COUNT: String = "ITEMS_COUNT"
        const val COMPARE_KEY_CONTENT_TYPE: String = "CONTENT_TYPE"
        const val COMPARE_KEY_FILE_SIZE: String = "FILE_SIZE"
        const val COMPARE_KEY_DISK_NUMBER: String = "DISK_NUMBER"
        const val COMPARE_KEY_TRACK_NUMBER: String = "TRACK_NUMBER"
        const val COMPARE_KEY_DURATION: String = "DURATION"
    }

    /**
     * 获取类的指定元素的值的方法
     *
     * 实现时参考以下样例
     * ```kotlin
     *     @Suppress("UNCHECKED_CAST", "IMPLICIT_CAST_TO_ANY")
     *     override fun <T : Any> getValueBy(key: String): T? {
     *         return when (key) {
     *             Sortable.COMPARE_KEY_ID -> id
     *             Sortable.COMPARE_KEY_TITLE -> name
     *             else -> super.getValueBy<T>(key) // 必须传递泛型T给父类方法，否则会导致运行时类型推断错误
     *         } as? T? // 最终强制转换后返回类型为T?
     *     }
     * ```
     */
    @CallSuper
    fun <T : Any> getValueBy(key: String): T? = null
}

/**
 * sealed class不能直接作为Map的Key，需要实现Serializable接口使其可序列化，否则会报错
 */
sealed class GroupIdentity : Serializable {
    data object None : GroupIdentity() {
        private fun readResolve(): Any = None
    }

    data class FirstLetter(val letter: String) : GroupIdentity()
    data class DiskNumber(val number: Long) : GroupIdentity()
    data class Time(val time: String) : GroupIdentity()

    val text: String by lazy {
        when (this) {
            is DiskNumber -> number.toString()
            is FirstLetter -> letter
            is Time -> time
            None -> "NONE"
        }
    }

    override fun toString(): String = text
}

interface ListAction {
    @get:StringRes
    val titleRes: Int
}

private val timeStrJustNow: String? =
    StringUtils.getString(R.string.group_identity_time_just_now)
private val timeStrMinutesAgo: String? =
    StringUtils.getString(R.string.group_identity_time_minutes_ago)
private val timeStrHoursAgo: String? =
    StringUtils.getString(R.string.group_identity_time_hours_ago)
private val timeStrExactDay: String? =
    StringUtils.getString(R.string.group_identity_time_exact_day_pattern)


abstract class SortStaticAction(override val titleRes: Int) : ListAction {
    open fun <T : Sortable> doSort(
        items: List<T>,
        reverse: Boolean = false
    ): Map<GroupIdentity, List<T>> {
        return items.let { if (reverse) it.asReversed() else it }
            .let { mapOf(GroupIdentity.None to it) }
    }

    protected fun <T : Sortable> groupByCreateTime(items: List<T>): Map<GroupIdentity, List<T>> {
        val now = System.currentTimeMillis()
        return items.groupBy {
            val time = (it.getValueBy(Sortable.COMPARE_KEY_CREATE_TIME) ?: -1L) * 1000
            when {
                now - time < 300000 -> timeStrJustNow
                now - time < 3600000 -> timeStrMinutesAgo?.format((now - time) / 60000)
                now - time < 86400000 -> timeStrHoursAgo?.format((now - time) / 3600000)
                else -> timeStrExactDay?.let {
                    TimeUtils.millis2String(time, timeStrExactDay)
                }
            }
        }.mapKeys { GroupIdentity.Time(it.key ?: "#") }
    }

    data object Normal : SortStaticAction(titleRes = R.string.sort_preset_by_normal)

    data object AddTime : SortStaticAction(titleRes = R.string.sort_preset_by_add_time) {
        override fun <T : Sortable> doSort(
            items: List<T>,
            reverse: Boolean
        ): Map<GroupIdentity, List<T>> {
            return items.sortedByDescending {
                (it.getValueBy(Sortable.COMPARE_KEY_CREATE_TIME) ?: -1L)
            }.let { if (reverse) it.asReversed() else it }
                .let { groupByCreateTime(it) }
        }
    }

    data object Title : SortStaticAction(titleRes = R.string.sort_preset_by_title) {
        override fun <T : Sortable> doSort(
            items: List<T>,
            reverse: Boolean
        ): Map<GroupIdentity, List<T>> {
            return items.sortedWith { a, b ->
                val aText = a.getValueBy<String>(Sortable.COMPARE_KEY_TITLE) ?: return@sortedWith 0
                val bText = b.getValueBy<String>(Sortable.COMPARE_KEY_TITLE) ?: return@sortedWith 0

                Collator.getInstance().compare(aText, bText)
            }.let { if (reverse) it.asReversed() else it }
                .groupBy {
                    val text = it.getValueBy<String>(Sortable.COMPARE_KEY_TITLE)
                    PinyinUtils.getPinyinFirstLetter(text)?.uppercase() ?: ""
                }
                .mapKeys { GroupIdentity.FirstLetter(it.key) }
        }
    }

    data object Duration : SortStaticAction(titleRes = R.string.sort_preset_by_song_duration) {
        override fun <T : Sortable> doSort(
            items: List<T>,
            reverse: Boolean
        ): Map<GroupIdentity, List<T>> {
            return items.sortedByDescending {
                (it.getValueBy(Sortable.COMPARE_KEY_DURATION) ?: -1L)
            }.let { if (reverse) it.asReversed() else it }
                .let { mapOf(GroupIdentity.None to it) }
        }
    }

    data object Shuffle : SortStaticAction(titleRes = R.string.sort_preset_by_shuffle) {
        override fun <T : Sortable> doSort(
            items: List<T>,
            reverse: Boolean
        ): Map<GroupIdentity, List<T>> {
            return items.shuffled()
                .let { if (reverse) it.asReversed() else it }
                .let { mapOf(GroupIdentity.None to it) }
        }
    }

    /**
     * 元素内歌曲数量排序
     */
    data object ItemsCount : SortStaticAction(titleRes = R.string.sort_preset_by_item_count) {
        override fun <T : Sortable> doSort(
            items: List<T>,
            reverse: Boolean
        ): Map<GroupIdentity, List<T>> {
            return items.sortedByDescending {
                it.getValueBy(Sortable.COMPARE_KEY_ITEMS_COUNT) ?: 0L
            }
                .let { if (reverse) it.asReversed() else it }
                .let { mapOf(GroupIdentity.None to it) }
        }
    }
}

abstract class SortDynamicAction(
    override val titleRes: Int
) : ListAction {
    abstract fun <T : Sortable> doSort(
        items: Flow<List<T>>,
        reverse: Boolean = false
    ): Flow<Map<GroupIdentity, List<T>>>
}
