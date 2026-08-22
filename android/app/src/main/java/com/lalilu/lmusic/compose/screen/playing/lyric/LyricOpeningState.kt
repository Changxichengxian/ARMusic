package com.lalilu.lmusic.compose.screen.playing.lyric

import com.lalilu.lmedia.lyric.LyricItem
import com.lalilu.lmedia.lyric.findPlayingIndex

internal const val LYRIC_OPENING_TRANSITION_MS = 1_800L
private const val MAX_CREDIT_SCAN_ITEMS = 16
private const val MAX_LEADING_NON_CREDIT_ITEMS = 8
private const val MAX_BODY_ITEMS_AFTER_CREDITS = 2

private val creditLabels = setOf(
    "词", "作词", "填词", "歌词", "歌词创作", "译词", "翻译",
    "曲", "作曲", "谱曲", "编曲", "编曲制作",
    "作词作曲", "词曲", "作编曲", "作曲编曲",
    "制作", "制作人", "音乐制作人", "总制作人", "联合制作", "监制", "制片人", "统筹", "企划",
    "演唱", "歌手", "原唱", "翻唱", "主唱", "和声", "和声编写", "和声设计", "配唱", "监唱",
    "录音", "录音师", "混音", "混音师", "母带", "母带处理", "音频编辑", "人声编辑",
    "吉他", "贝斯", "鼓", "鼓手", "键盘", "弦乐", "管弦乐", "乐器",
    "出品", "发行", "版权", "封面", "封面设计", "视觉设计", "设计", "鸣谢", "特别鸣谢",
    "作詞", "編曲", "製作", "製作人", "錄音", "錄音師", "母帶", "母帶處理", "發行",
    "歌唱", "唄", "プロデュース", "プロデューサー", "ミックス", "マスタリング",
    "ボーカル", "ギター", "ベース", "ドラム", "ピアノ", "ストリングス",
    "작사", "작곡", "편곡", "프로듀서", "보컬", "믹싱", "마스터링",
    "op", "sp", "lrc", "歌词制作",
    "lyrics", "lyricist", "words", "writer", "written", "songwriter", "composer", "composed", "music",
    "arranger", "arranged", "arrangement", "producer", "produced", "executiveproducer",
    "musicproducer",
    "vocal", "vocals", "singer", "artist", "chorus", "backingvocals",
    "recording", "recorded", "recordingengineer", "mix", "mixing", "mixed", "mixer",
    "master", "mastering", "mastered", "guitar", "bass", "drums", "keyboard", "strings",
    "orchestra", "publisher", "label", "translation", "translated",
)

private val creditLabelSeparators = Regex("""[/／&＆、,，·・+＋|｜]""")
private val creditBracketLine = Regex("""^[\[【(（]\s*(.+?)\s*[\]】)）]\s*(.*)$""")
private val ambiguousWhitespaceCreditLabels = setOf(
    "词", "曲", "歌词", "制作", "设计", "翻译",
    "lyrics", "words", "music", "written", "composed", "arranged", "produced",
    "recording", "recorded", "mix", "mixed", "master", "mastered", "label",
)

internal fun List<LyricItem>.findDisplayIndex(time: Long): Int {
    if (isEmpty()) return Int.MAX_VALUE
    return findPlayingIndex(time).takeUnless { it == Int.MAX_VALUE } ?: 0
}

internal fun lyricOpeningProgress(
    time: Long,
    firstLineTime: Long?,
    blurOpeningEnabled: Boolean,
    transitionDurationMs: Long = LYRIC_OPENING_TRANSITION_MS,
): Float {
    if (!blurOpeningEnabled || firstLineTime == null || time >= firstLineTime) return 1f
    if (transitionDurationMs <= 0L) return 1f

    return (1f - (firstLineTime - time).toFloat() / transitionDurationMs)
        .coerceIn(0f, 1f)
}

internal fun lyricAnchorDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportHeight: Int,
    focusPosition: Float,
): Float {
    val actualCenter = itemOffset + itemSize / 2f
    val expectedCenter = viewportHeight * focusPosition
    return actualCenter - expectedCenter
}

internal fun lyricFocusPosition(storedPosition: Float): Float {
    if (storedPosition <= 0.375f) {
        val legacyPosition = storedPosition.coerceIn(0.125f, 0.375f)
        return 1f - legacyPosition * 2f
    }

    return storedPosition.coerceIn(0.25f, 0.75f)
}

internal fun List<LyricItem>.prepareLyricsForDisplay(
    hideZeroTimeCredits: Boolean,
): List<LyricItem> {
    val creditLineIndices = detectCreditLineIndices()
    val visibleLyrics = if (hideZeroTimeCredits) {
        filterIndexed { index, _ -> index !in creditLineIndices }
    } else {
        this
    }
    val zeroTimeItems = visibleLyrics.filter { it.time == 0L }
    if (zeroTimeItems.size <= 1) return visibleLyrics

    val firstTimedLine = visibleLyrics.firstOrNull { it.time > 0L }?.time
    val fallbackWindow = zeroTimeItems.size * 1_000L
    val distributionWindow = firstTimedLine ?: fallbackWindow
    var zeroIndex = 0

    return visibleLyrics.map { item ->
        if (item.time != 0L) return@map item

        val displayTime = distributionWindow * zeroIndex / zeroTimeItems.size
        zeroIndex += 1
        item.withDisplayTime(displayTime)
    }
}

internal fun List<LyricItem>.detectCreditLineIndices(): Set<Int> {
    val result = linkedSetOf<Int>()
    var nonCreditItemsAfterCredits = 0

    for (index in indices.take(MAX_CREDIT_SCAN_ITEMS)) {
        val item = get(index)
        val isCredit = isCreditLineContent(item.displayContent())

        if (result.isEmpty()) {
            if (isCredit) {
                result += index
                continue
            }
            if (item.time > 0L || index + 1 >= MAX_LEADING_NON_CREDIT_ITEMS) break
            continue
        }

        if (isCredit) {
            result += index
            nonCreditItemsAfterCredits = 0
        } else {
            nonCreditItemsAfterCredits += 1
            if (nonCreditItemsAfterCredits >= MAX_BODY_ITEMS_AFTER_CREDITS) break
        }
    }

    return result
}

internal fun isCreditLineContent(content: String): Boolean {
    val line = content.trim()
    if (line.isEmpty()) return false

    val colonIndex = line.indexOfFirst { it == ':' || it == '：' }
    if (colonIndex > 0 && isKnownCreditLabelBlock(line.substring(0, colonIndex))) {
        return true
    }

    creditBracketLine.matchEntire(line)?.let { match ->
        val label = match.groupValues[1]
        val value = match.groupValues[2]
        if (isKnownCreditLabelBlock(label) && value.isNotBlank()) return true
    }

    val normalizedLine = line.lowercase()
    return creditLabels
        .asSequence()
        .sortedByDescending(String::length)
        .any { label ->
            if (!normalizedLine.startsWith(label)) return@any false
            val rest = line.substring(label.length)
            val restWithoutLeadingSpace = rest.trimStart()
            rest.isBlank() ||
                    (rest.firstOrNull()?.isWhitespace() == true &&
                            (label !in ambiguousWhitespaceCreditLabels ||
                                    restWithoutLeadingSpace.startsWith("by ", ignoreCase = true))) ||
                    rest.firstOrNull() in setOf('/', '／', '-', '—', '–', '|', '｜', '·')
        }
}

private fun isKnownCreditLabelBlock(rawLabel: String): Boolean {
    val cleaned = rawLabel.trim()
        .trim('[', ']', '【', '】', '(', ')', '（', '）')
    if (cleaned.isBlank()) return false

    return cleaned.split(creditLabelSeparators)
        .map { it.normalizedCreditLabel() }
        .filter(String::isNotBlank)
        .takeIf(List<String>::isNotEmpty)
        ?.all { it in creditLabels }
        ?: false
}

private fun String.normalizedCreditLabel(): String {
    return lowercase()
        .replace("by", "")
        .filterNot(Char::isWhitespace)
}

private fun LyricItem.displayContent(): String {
    return when (this) {
        is LyricItem.NormalLyric -> content
        is LyricItem.WordsLyric -> words.joinToString(separator = "") { it.content }
    }
}

private fun LyricItem.withDisplayTime(displayTime: Long): LyricItem {
    return when (this) {
        is LyricItem.NormalLyric -> copy(time = displayTime)
        is LyricItem.WordsLyric -> {
            val shift = displayTime - startTime
            copy(
                startTime = displayTime,
                endTime = (endTime + shift).coerceAtLeast(displayTime),
                words = words.map { word ->
                    val wordStart = (word.startTime + shift).coerceAtLeast(displayTime)
                    word.copy(
                        startTime = wordStart,
                        endTime = (word.endTime + shift).coerceAtLeast(wordStart),
                    )
                },
            )
        }
    }
}
