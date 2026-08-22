package com.lalilu.lplayer.extensions

import androidx.media3.common.Player

enum class PlayMode {
    ListRecycle,
    RepeatOne,
    Shuffle;

    companion object {
        fun of(repeatMode: Int, shuffleModeEnabled: Boolean): PlayMode {
            if (repeatMode == Player.REPEAT_MODE_ONE) return RepeatOne
            if (shuffleModeEnabled) return Shuffle
            return ListRecycle
        }

        fun parse(string: String?): PlayMode? =
            entries.firstOrNull { it.name.equals(string, ignoreCase = true) }

        fun from(string: String?): PlayMode = parse(string) ?: ListRecycle
    }
}

var Player.playMode
    get() = PlayMode.of(repeatMode, shuffleModeEnabled)
    set(value) {
        when (value) {
            PlayMode.ListRecycle -> {
                if (shuffleModeEnabled) shuffleModeEnabled = false
                if (repeatMode != Player.REPEAT_MODE_ALL) repeatMode = Player.REPEAT_MODE_ALL
            }

            PlayMode.RepeatOne -> {
                if (shuffleModeEnabled) shuffleModeEnabled = false
                if (repeatMode != Player.REPEAT_MODE_ONE) repeatMode = Player.REPEAT_MODE_ONE
            }

            PlayMode.Shuffle -> {
                // Leave repeat-one before enabling shuffle so the player never exposes both
                // mutually exclusive modes as its final state.
                if (repeatMode != Player.REPEAT_MODE_ALL) repeatMode = Player.REPEAT_MODE_ALL
                if (!shuffleModeEnabled) shuffleModeEnabled = true
            }
        }
    }

fun Player.hasPlayMode(playMode: PlayMode): Boolean = when (playMode) {
    PlayMode.ListRecycle -> repeatMode == Player.REPEAT_MODE_ALL && !shuffleModeEnabled
    PlayMode.RepeatOne -> repeatMode == Player.REPEAT_MODE_ONE && !shuffleModeEnabled
    PlayMode.Shuffle -> repeatMode == Player.REPEAT_MODE_ALL && shuffleModeEnabled
}
