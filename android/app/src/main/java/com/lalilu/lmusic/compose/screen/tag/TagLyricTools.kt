package com.lalilu.lmusic.compose.screen.tag

private data class LyricTimestampKey(
    val value: String,
    val isZero: Boolean,
)

private val lrcTimestampPrefixRegex = Regex("""^(?:\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?])+""")
private val lrcTimestampTokenRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

internal fun swapSameTimeLyricLines(text: String): String {
    val newline = if (text.contains("\r\n")) "\r\n" else "\n"
    val lines = text.split(Regex("\r\n|\n|\r"))
    val result = mutableListOf<String>()
    var index = 0

    while (index < lines.size) {
        val key = sameTimeKey(lines[index])
        if (key == null) {
            result += lines[index]
            index += 1
            continue
        }

        val group = mutableListOf<String>()
        while (index < lines.size && sameTimeKey(lines[index])?.value == key.value) {
            group += lines[index]
            index += 1
        }

        if (!key.isZero && group.size == 2) {
            result += group[1]
            result += group[0]
        } else {
            result += group
        }
    }

    return result.joinToString(newline)
}

private fun sameTimeKey(line: String): LyricTimestampKey? {
    val prefix = lrcTimestampPrefixRegex.find(line)?.value ?: return null
    val tokens = lrcTimestampTokenRegex.findAll(prefix)
        .mapNotNull { match ->
            val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val millis = match.groupValues.getOrNull(3)
                ?.takeIf(String::isNotBlank)
                ?.padEnd(3, '0')
                ?.take(3)
                ?.toLongOrNull()
                ?: 0L
            minute * 60_000L + second * 1_000L + millis
        }
        .toList()

    if (tokens.isEmpty()) return null

    return LyricTimestampKey(
        value = tokens.joinToString("|"),
        isZero = tokens.all { it == 0L },
    )
}
