package com.lalilu.lhistory

import android.content.SharedPreferences

internal const val KEY_SETTINGS_HISTORY_DURATION_FILTER = "KEY_SETTINGS_HISTORY_DURATION_FILTER"
private const val DEFAULT_SETTINGS_HISTORY_DURATION_FILTER = 15

internal fun SharedPreferences.historyDurationFilterMs(): Long {
    return getInt(
        KEY_SETTINGS_HISTORY_DURATION_FILTER,
        DEFAULT_SETTINGS_HISTORY_DURATION_FILTER
    ).coerceAtLeast(0) * 1000L
}
