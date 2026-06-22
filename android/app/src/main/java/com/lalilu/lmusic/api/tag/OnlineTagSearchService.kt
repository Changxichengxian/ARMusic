package com.lalilu.lmusic.api.tag

import com.lalilu.lmusic.api.itunes.ItunesSearchApi
import com.lalilu.lmusic.api.itunes.ItunesSongResult
import com.lalilu.lmusic.api.lrcshare.LrcShareApi
import com.lalilu.lmusic.api.lrcshare.SongResult
import com.lalilu.lmusic.api.lrclib.LrclibApi
import com.lalilu.lmusic.api.lrclib.LrclibLyricsResult
import com.lalilu.lmusic.api.netease.NeteaseLyricSong
import com.lalilu.lmusic.api.netease.NeteaseLyricResponse
import com.lalilu.lmusic.api.netease.NeteaseMusicApi
import com.lalilu.lmusic.api.netease.NeteaseSong
import com.lalilu.lmusic.api.netease.NeteaseSongDetail
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class OnlineTagSearchService(
    private val lrcShareApi: LrcShareApi,
    private val itunesSearchApi: ItunesSearchApi,
    private val lrclibApi: LrclibApi,
    private val neteaseMusicApi: NeteaseMusicApi,
) {
    suspend fun search(
        keyword: String,
        title: String,
        artist: String,
        album: String,
        limit: Int = 10,
        extended: Boolean = false,
    ): List<OnlineSongTag> = coroutineScope {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return@coroutineScope emptyList()
        val resultLimit = limit.coerceAtLeast(1)
        val queryCandidates = buildQueryCandidates(
            keyword = cleanKeyword,
            title = title,
            artist = artist,
            album = album,
        )

        val netease = async {
            sourceOrEmpty(timeoutMillis = 4500L) {
                val songs = queryCandidates.firstNotNullOfOrNull { query ->
                    neteaseMusicApi.searchSongs(query)
                        .result
                        ?.songs
                        .orEmpty()
                        .takeIf { it.isNotEmpty() }
                }.orEmpty()

                val detailMap = songs
                    .map { it.id }
                    .filter { it > 0L }
                    .takeIf(List<Long>::isNotEmpty)
                    ?.let { ids ->
                        neteaseMusicApi.songDetails(ids.joinToString(",", prefix = "[", postfix = "]"))
                            .songs
                            .associateBy { it.id }
                    }
                    .orEmpty()

                songs.mapNotNull { it.toOnlineSongTag(detailMap[it.id]) }
            }
        }

        if (!extended) {
            return@coroutineScope netease.await()
                .distinctBy { "${it.source}:${it.normalizedTitle}:${it.normalizedArtist}:${it.album.lowercase(Locale.ROOT)}" }
                .take(resultLimit)
        }

        val itunes = async {
            sourceOrEmpty(timeoutMillis = 3500L) {
                queryCandidates.firstNotNullOfOrNull { query ->
                    itunesSearchApi.searchSongs(term = query)
                        .results
                        .mapNotNull(ItunesSongResult::toOnlineSongTag)
                        .takeIf { it.isNotEmpty() }
                }.orEmpty()
            }
        }

        val lrclib = async {
            sourceOrEmpty(timeoutMillis = 3500L) {
                lrclibApi.search(
                    query = cleanKeyword,
                    trackName = title.trim().takeIf(String::isNotBlank),
                    artistName = artist.trim().takeIf(String::isNotBlank),
                    albumName = album.trim().takeIf(String::isNotBlank),
                ).mapNotNull(LrclibLyricsResult::toOnlineSongTag)
            }
        }

        val lrcShare = async {
            sourceOrEmpty(timeoutMillis = 1800L) {
                lrcShareApi.searchForSong(
                    song = title.trim().takeIf(String::isNotBlank) ?: cleanKeyword,
                    artist = artist.trim().takeIf(String::isNotBlank),
                    album = album.trim().takeIf(String::isNotBlank),
                ).map(SongResult::toOnlineSongTag)
            }
        }

        (netease.await() + itunes.await() + lrclib.await() + lrcShare.await())
            .distinctBy { "${it.source}:${it.normalizedTitle}:${it.normalizedArtist}:${it.album.lowercase(Locale.ROOT)}" }
            .take(50)
    }

    suspend fun lyricFor(tag: OnlineSongTag): String {
        if (tag.lyric.isNotBlank()) return tag.lyric

        tag.neteaseId
            ?.let { id ->
                withTimeoutOrNull(2500L) {
                    runCatching { neteaseMusicApi.lyric(id).mergedLyric() }.getOrDefault("")
                }
            }
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val targetTitle = tag.title.normalizedLyricTitle()

        buildQueryCandidates(
            keyword = listOf(tag.title.withoutBracketInfo(), tag.artist)
                .filter(String::isNotBlank)
                .joinToString(" "),
            title = tag.title,
            artist = tag.artist,
            album = tag.album,
        ).firstNotNullOfOrNull { query ->
            withTimeoutOrNull(2500L) {
                runCatching {
                    neteaseMusicApi.searchLyrics(query)
                        .result
                        ?.songs
                        .orEmpty()
                        .firstOrNull {
                            val name = it.name.orEmpty().normalizedLyricTitle()
                            name == targetTitle || targetTitle.contains(name) || name.contains(targetTitle)
                        }
                        ?.lyrics
                        ?.joinToString("\n")
                        .orEmpty()
                }.getOrDefault("")
            }?.takeIf(String::isNotBlank)
        }.orEmpty()
            .takeIf(String::isNotBlank)
            ?.let { return it }

        return tag.lrcShareId
            ?.let { id ->
                withTimeoutOrNull(1800L) {
                    runCatching { lrcShareApi.getLyricById(id)?.lyric.orEmpty() }.getOrDefault("")
                }
            }
            .orEmpty()
    }

    private val OnlineSongTag.normalizedTitle: String
        get() = title.trim().lowercase(Locale.ROOT)

    private val OnlineSongTag.normalizedArtist: String
        get() = artist.trim().lowercase(Locale.ROOT)
}

private suspend fun <T> sourceOrEmpty(
    timeoutMillis: Long,
    block: suspend () -> List<T>,
): List<T> {
    return withTimeoutOrNull(timeoutMillis) {
        runCatching { block() }.getOrDefault(emptyList())
    }.orEmpty()
}

private fun buildQueryCandidates(
    keyword: String,
    title: String,
    artist: String,
    album: String,
): List<String> {
    val cleanTitle = title.withoutBracketInfo()
    val firstArtist = artist.split('/', ';', '、', ',', '，')
        .firstOrNull()
        .orEmpty()
        .trim()

    return listOf(
        keyword,
        listOf(title, artist).joinNonBlank(),
        listOf(cleanTitle, artist).joinNonBlank(),
        listOf(title, firstArtist).joinNonBlank(),
        listOf(cleanTitle, firstArtist).joinNonBlank(),
        cleanTitle,
        title,
        album,
    )
        .map { it.cleanQuery() }
        .filter(String::isNotBlank)
        .distinct()
}

private fun List<String>.joinNonBlank(): String {
    return map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
}

private fun String.cleanQuery(): String {
    return replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.withoutBracketInfo(): String {
    return replace(Regex("""\([^)]*\)|（[^）]*）|\[[^\]]*]"""), " ")
        .cleanQuery()
}

private fun NeteaseSong.toOnlineSongTag(detail: NeteaseSongDetail?): OnlineSongTag? {
    val title = name?.takeIf(String::isNotBlank) ?: return null
    val neteaseArtists = detail?.artists?.takeIf(List<*>::isNotEmpty) ?: artists
    val neteaseAlbum = detail?.album ?: album
    val translatedTitle = detail?.transName?.takeIf(String::isNotBlank)
        ?: detail?.transNames?.firstOrNull()
        ?: transNames.firstOrNull()
        ?: alias.firstOrNull()

    return OnlineSongTag(
        id = "netease:$id",
        source = "网易云",
        title = if (translatedTitle.isNullOrBlank()) title else "$title ($translatedTitle)",
        artist = neteaseArtists.mapNotNull { it.name?.takeIf(String::isNotBlank) }.joinToString("/"),
        album = neteaseAlbum?.name.orEmpty(),
        albumArtist = neteaseAlbum?.artists
            ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }
            ?.joinToString("/")
            ?.ifBlank { null }
            ?: neteaseAlbum?.artist?.name.orEmpty(),
        cover = neteaseAlbum?.picUrl ?: neteaseAlbum?.blurPicUrl.orEmpty(),
        neteaseId = id,
    )
}

private fun NeteaseLyricSong.toOnlineSongTag(): OnlineSongTag? {
    val title = name?.takeIf(String::isNotBlank) ?: return null
    val artist = artists.mapNotNull { it.name?.takeIf(String::isNotBlank) }
        .joinToString("/")

    return OnlineSongTag(
        id = "netease-lyric:$id",
        source = "网易云",
        title = title,
        artist = artist,
        album = album?.name.orEmpty(),
        lyric = lyrics.joinToString("\n"),
        neteaseId = id,
    )
}

private fun NeteaseLyricResponse.mergedLyric(): String {
    val main = lrc?.lyric.orEmpty()
    val translation = tlyric?.lyric.orEmpty()
    return listOf(main, translation)
        .filter(String::isNotBlank)
        .joinToString("\n")
}

private fun SongResult.toOnlineSongTag(): OnlineSongTag {
    return OnlineSongTag(
        id = "lrcshare:$id",
        source = "LrcShare",
        title = song,
        artist = artist,
        album = album.orEmpty(),
        albumArtist = album_artist.orEmpty(),
        composer = composer.orEmpty(),
        lyricist = writer.orEmpty(),
        comment = comment.orEmpty(),
        genre = genre.orEmpty(),
        track = track?.toString().orEmpty(),
        disc = disc?.toString().orEmpty(),
        year = year.orEmpty(),
        cover = cover.orEmpty(),
        lrcShareId = id,
    )
}

private fun ItunesSongResult.toOnlineSongTag(): OnlineSongTag? {
    val title = trackName?.takeIf(String::isNotBlank) ?: return null
    val artist = artistName?.takeIf(String::isNotBlank) ?: return null

    return OnlineSongTag(
        id = "itunes:$trackId",
        source = "iTunes",
        title = title,
        artist = artist,
        album = collectionName.orEmpty(),
        albumArtist = collectionArtistName.orEmpty(),
        genre = primaryGenreName.orEmpty(),
        track = trackNumber?.toString().orEmpty(),
        disc = discNumber?.toString().orEmpty(),
        year = releaseDate?.take(4).orEmpty(),
        cover = artworkUrl100?.upgradeItunesCover().orEmpty(),
    )
}

private fun LrclibLyricsResult.toOnlineSongTag(): OnlineSongTag? {
    val title = trackName?.takeIf(String::isNotBlank) ?: return null
    val artist = artistName?.takeIf(String::isNotBlank) ?: return null
    val lyric = syncedLyrics?.takeIf(String::isNotBlank)
        ?: plainLyrics?.takeIf(String::isNotBlank)
        ?: if (instrumental) "[00:00.00] Instrumental" else ""

    return OnlineSongTag(
        id = "lrclib:$id",
        source = "LRCLIB",
        title = title,
        artist = artist,
        album = albumName.orEmpty(),
        lyric = lyric,
    )
}

private fun String.upgradeItunesCover(): String {
    return replace(Regex("\\d+x\\d+bb"), "1000x1000bb")
}

private fun String.normalizedLyricTitle(): String {
    return lowercase(Locale.ROOT)
        .withoutBracketInfo()
        .replace(Regex("""\s+"""), "")
}
