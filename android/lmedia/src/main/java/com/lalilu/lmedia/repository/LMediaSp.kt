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
    private val defaultCallRecordingExclusionsSeeded =
        obtain<Boolean>("DEFAULT_CALL_RECORDING_EXCLUSIONS_SEEDED_20260622")

    fun seedDefaultCallRecordingExclusions() {
        if (defaultCallRecordingExclusionsSeeded.value) return

        excludePath.add(
            listOf(
                "/storage/emulated/0/Music/Recordings/Call Recordings",
                "/storage/emulated/0/.SoundRecordRecycler/Call Recordings"
            )
        )
        defaultCallRecordingExclusionsSeeded.value = true
    }
}
