package com.lalilu.lplayer.extensions

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayModeTest {
    @Test
    fun storedValueParsingIsCaseInsensitiveAndSafe() {
        assertEquals(PlayMode.RepeatOne, PlayMode.parse("repeatone"))
        assertEquals(PlayMode.Shuffle, PlayMode.from("SHUFFLE"))
        assertNull(PlayMode.parse("broken"))
        assertEquals(PlayMode.ListRecycle, PlayMode.from("broken"))
        assertEquals(PlayMode.ListRecycle, PlayMode.from(null))
    }

    @Test
    fun playerFlagsResolveToOneAuthoritativeMode() {
        assertEquals(PlayMode.RepeatOne, PlayMode.of(Player.REPEAT_MODE_ONE, true))
        assertEquals(PlayMode.Shuffle, PlayMode.of(Player.REPEAT_MODE_ALL, true))
        assertEquals(PlayMode.ListRecycle, PlayMode.of(Player.REPEAT_MODE_ALL, false))
    }
}
