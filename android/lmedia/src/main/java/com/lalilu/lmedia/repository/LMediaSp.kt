package com.lalilu.lmedia.repository

import android.app.Application
import android.content.SharedPreferences
import com.lalilu.common.base.BaseSp

class LMediaSp(private val context: Application) : BaseSp() {
    override fun obtainSourceSp(): SharedPreferences {
        return context.getSharedPreferences("LMEDIA", Application.MODE_PRIVATE)
    }

    val includePath = obtainSet<String>("INCLUDE_PATH")
    val excludePath = obtainSet<String>("EXCLUDE_PATH")
    private val defaultRecordingExclusionsSeeded =
        obtain<Boolean>("DEFAULT_RECORDING_EXCLUSIONS_SEEDED_20260623")

    fun seedDefaultRecordingExclusions() {
        if (defaultRecordingExclusionsSeeded.value) return

        excludePath.add(
            listOf(
                "/storage/emulated/0/Music/Recordings",
                "/storage/emulated/0/Recordings",
                "/storage/emulated/0/.SoundRecordRecycler"
            )
        )
        defaultRecordingExclusionsSeeded.value = true
    }
}
