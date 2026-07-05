package com.lalilu.component.work

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lalilu.component.R

object WorkLabel {
    const val KEY_SETTINGS_WORK_LABEL_MODE = "KEY_SETTINGS_WORK_LABEL_MODE"
    const val DEFAULT_MODE = 2

    val options = listOf("album", "series", "work")

    fun labelOf(mode: Int): String {
        return options.getOrElse(mode) { options[DEFAULT_MODE] }
    }

    fun readMode(context: Context): Int {
        return context
            .getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
            .getInt(KEY_SETTINGS_WORK_LABEL_MODE, DEFAULT_MODE)
    }
}

@Composable
fun rememberWorkLabelOptions(): List<String> {
    return listOf(
        stringResource(id = R.string.work_label_album),
        stringResource(id = R.string.work_label_series),
        stringResource(id = R.string.work_label_work),
    )
}

@Composable
fun rememberWorkLabel(): String {
    val context = LocalContext.current.applicationContext
    val mode = remember(context) { mutableStateOf(WorkLabel.readMode(context)) }
    val options = rememberWorkLabelOptions()

    DisposableEffect(context) {
        val sp = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WorkLabel.KEY_SETTINGS_WORK_LABEL_MODE) {
                mode.value = WorkLabel.readMode(context)
            }
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return options.getOrElse(mode.value) { options[WorkLabel.DEFAULT_MODE] }
}
