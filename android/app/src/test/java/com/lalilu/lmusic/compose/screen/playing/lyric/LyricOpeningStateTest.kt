package com.lalilu.lmusic.compose.screen.playing.lyric

import com.lalilu.lmedia.lyric.LyricItem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricOpeningStateTest {
    private val lyrics = listOf(
        LyricItem.NormalLyric(content = "first", time = 10_000L, key = "first"),
        LyricItem.NormalLyric(content = "second", time = 20_000L, key = "second"),
    )

    @Test
    fun `first line is display target before it starts`() {
        assertEquals(0, lyrics.findDisplayIndex(0L))
        assertEquals(0, lyrics.findDisplayIndex(9_999L))
        assertEquals(0, lyrics.findDisplayIndex(10_000L))
    }

    @Test
    fun `seeking back before first line returns display target to first line`() {
        assertEquals(1, lyrics.findDisplayIndex(25_000L))
        assertEquals(0, lyrics.findDisplayIndex(0L))
    }

    @Test
    fun `empty lyrics keep no display target`() {
        assertEquals(Int.MAX_VALUE, emptyList<LyricItem>().findDisplayIndex(0L))
    }

    @Test
    fun `blur opening reveals during the final transition window`() {
        assertEquals(0f, lyricOpeningProgress(0L, 10_000L, true))
        assertEquals(0f, lyricOpeningProgress(8_200L, 10_000L, true))
        assertEquals(0.5f, lyricOpeningProgress(9_100L, 10_000L, true))
        assertEquals(1f, lyricOpeningProgress(10_000L, 10_000L, true))
    }

    @Test
    fun `disabled blur opening highlights first line immediately`() {
        assertEquals(1f, lyricOpeningProgress(0L, 10_000L, false))
    }

    @Test
    fun `anchor delta compares actual row center with configured focus`() {
        assertEquals(
            40f,
            lyricAnchorDelta(
                itemOffset = 500,
                itemSize = 80,
                viewportHeight = 1_000,
                focusPosition = 0.5f,
            )
        )
        assertEquals(
            0f,
            lyricAnchorDelta(
                itemOffset = 460,
                itemSize = 80,
                viewportHeight = 1_000,
                focusPosition = 0.5f,
            )
        )
    }

    @Test
    fun `middle height setting resolves to middle of the screen`() {
        assertEquals(0.5f, lyricFocusPosition(0.25f))
        assertEquals(0.25f, lyricFocusPosition(0.375f))
        assertEquals(0.75f, lyricFocusPosition(0.125f))
    }

    @Test
    fun `old direct screen positions remain compatible`() {
        assertEquals(0.5f, lyricFocusPosition(0.5f))
        assertEquals(0.75f, lyricFocusPosition(0.75f))
    }

    @Test
    fun `zero time credits are evenly scheduled before first timed lyric`() {
        val displayLyrics = listOf(
            normalLyric("作词", 0L),
            normalLyric("作曲", 0L),
            normalLyric("编曲", 0L),
            normalLyric("first lyric", 6_000L),
        ).prepareLyricsForDisplay(hideZeroTimeCredits = false)

        assertContentEquals(
            listOf(0L, 2_000L, 4_000L, 6_000L),
            displayLyrics.map { it.time },
        )
        assertContentEquals(
            listOf("作词", "作曲", "编曲", "first lyric"),
            displayLyrics.map { (it as LyricItem.NormalLyric).content },
        )
    }

    @Test
    fun `scheduled zero time credits advance in source order`() {
        val displayLyrics = listOf(
            normalLyric("作词", 0L),
            normalLyric("作曲", 0L),
            normalLyric("编曲", 0L),
            normalLyric("first lyric", 6_000L),
        ).prepareLyricsForDisplay(hideZeroTimeCredits = false)

        assertEquals(0, displayLyrics.findDisplayIndex(0L))
        assertEquals(1, displayLyrics.findDisplayIndex(2_500L))
        assertEquals(2, displayLyrics.findDisplayIndex(4_500L))
        assertEquals(3, displayLyrics.findDisplayIndex(6_000L))
    }

    @Test
    fun `zero time credits can be hidden`() {
        val displayLyrics = listOf(
            normalLyric("作词", 0L),
            normalLyric("作曲", 0L),
            normalLyric("first lyric", 6_000L),
        ).prepareLyricsForDisplay(hideZeroTimeCredits = true)

        assertEquals(1, displayLyrics.size)
        assertEquals("first lyric", (displayLyrics.single() as LyricItem.NormalLyric).content)
        assertEquals(6_000L, displayLyrics.single().time)
    }

    @Test
    fun `zero time credits without a timed lyric still get stable order`() {
        val displayLyrics = listOf(
            normalLyric("作词", 0L),
            normalLyric("作曲", 0L),
            normalLyric("编曲", 0L),
        ).prepareLyricsForDisplay(hideZeroTimeCredits = false)

        assertContentEquals(listOf(0L, 1_000L, 2_000L), displayLyrics.map { it.time })
    }

    @Test
    fun `credit labels are recognized without matching ordinary lyric sentences`() {
        listOf(
            "作词：张三",
            "作曲/编曲：李四",
            "制作人 王五",
            "Producer: Alice",
            "Lyrics by Bob",
            "[混音] Carol",
            "作詞：山田太郎",
            "プロデューサー：佐藤花子",
            "작곡: 홍길동",
        ).forEach { assertTrue(isCreditLineContent(it), it) }

        listOf(
            "是谁在作词",
            "作曲的人还没睡",
            "制作一场不会醒的梦",
            "I mixed up all my memories",
            "Music is all I need",
            "Words are hard to say",
        ).forEach { assertFalse(isCreditLineContent(it), it) }
    }

    @Test
    fun `scheduled credits keep their original times when visible`() {
        val displayLyrics = listOf(
            normalLyric("歌曲标题", 0L),
            normalLyric("作词：张三", 1_000L),
            normalLyric("作曲：李四", 2_000L),
            normalLyric("制作人：王五", 3_000L),
            normalLyric("first lyric", 4_000L),
        ).prepareLyricsForDisplay(hideZeroTimeCredits = false)

        assertContentEquals(
            listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L),
            displayLyrics.map { it.time },
        )
    }

    @Test
    fun `semantic credit hiding removes scheduled credits but keeps title and lyric`() {
        val displayLyrics = listOf(
            normalLyric("歌曲标题", 0L),
            normalLyric("作词：张三", 1_000L),
            normalLyric("作曲：李四", 2_000L),
            normalLyric("制作人：王五", 3_000L),
            normalLyric("first lyric", 4_000L),
        ).prepareLyricsForDisplay(hideZeroTimeCredits = true)

        assertContentEquals(
            listOf("歌曲标题", "first lyric"),
            displayLyrics.map { (it as LyricItem.NormalLyric).content },
        )
    }

    @Test
    fun `zero time lyric is not hidden only because of its timestamp`() {
        val displayLyrics = listOf(
            normalLyric("唱出第一句", 0L),
            normalLyric("作词：张三", 0L),
            normalLyric("唱出第二句", 5_000L),
        ).prepareLyricsForDisplay(hideZeroTimeCredits = true)

        assertContentEquals(
            listOf("唱出第一句", "唱出第二句"),
            displayLyrics.map { (it as LyricItem.NormalLyric).content },
        )
    }

    @Test
    fun `credit-like sentence after lyrics begin is not treated as a credit`() {
        val lyricsWithBody = listOf(
            normalLyric("唱出第一句", 0L),
            normalLyric("唱出第二句", 3_000L),
            normalLyric("作词：我的人生", 6_000L),
        )

        assertTrue(lyricsWithBody.detectCreditLineIndices().isEmpty())
    }

    private fun normalLyric(content: String, time: Long): LyricItem.NormalLyric {
        return LyricItem.NormalLyric(
            content = content,
            time = time,
            key = "$content-$time",
        )
    }
}
