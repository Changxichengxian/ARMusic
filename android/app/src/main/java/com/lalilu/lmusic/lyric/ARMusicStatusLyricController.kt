package com.lalilu.lmusic.lyric

import StatusBarLyric.API.StatusBarLyric
import com.lalilu.lmusic.datastore.SettingsSp
import com.lalilu.lplayer.service.StatusLyricController

class ARMusicStatusLyricController(
    private val settingsSp: SettingsSp,
    private val statusBarLyric: StatusBarLyric,
) : StatusLyricController {
    override fun isEnabled(): Boolean = settingsSp.enableStatusLyric.value

    override fun updateLyric(text: String) {
        if (!isEnabled() || text.isBlank()) return
        runCatching { statusBarLyric.updateLyric(text) }
    }

    override fun stopLyric() {
        runCatching { statusBarLyric.stopLyric() }
    }
}
