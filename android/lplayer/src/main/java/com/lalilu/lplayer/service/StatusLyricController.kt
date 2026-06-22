package com.lalilu.lplayer.service

interface StatusLyricController {
    fun isEnabled(): Boolean
    fun updateLyric(text: String)
    fun stopLyric()
}
