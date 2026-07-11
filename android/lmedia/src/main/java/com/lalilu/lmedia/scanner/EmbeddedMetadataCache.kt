package com.lalilu.lmedia.scanner

import android.content.Context
import android.util.AtomicFile
import com.blankj.utilcode.util.LogUtils
import com.lalilu.lmedia.entity.Metadata
import com.lalilu.lmedia.extension.mediaUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Small, disposable cache for the embedded text tags read by TagLib during a MediaStore scan.
 *
 * The file identity deliberately includes both MediaStore and filesystem modification values.
 * A miss or any cache problem falls back to TagLib; this file is never a source of truth.
 */
internal class EmbeddedMetadataCache(context: Context) {
    private val atomicFile = AtomicFile(
        File(context.cacheDir, "armusic/embedded-metadata-v1.json")
    )
    private var loaded = false
    private var entries: Map<Long, EmbeddedMetadataCacheEntry> = emptyMap()

    @Synchronized
    fun snapshot(): Map<Long, EmbeddedMetadataCacheEntry> {
        ensureLoaded()
        return entries
    }

    @Synchronized
    fun replace(nextEntries: Collection<EmbeddedMetadataCacheEntry>) {
        ensureLoaded()
        val normalized = nextEntries
            .asSequence()
            .distinctBy { it.key.mediaId }
            .sortedBy { it.key.mediaId }
            .take(MAX_ENTRIES)
            .associateBy { it.key.mediaId }
        if (normalized == entries) return

        // Keep the in-process cache useful even when the OS has made cacheDir temporarily
        // unwritable. A later process simply performs a safe TagLib scan again.
        entries = normalized
        writeSafely(normalized.values)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        entries = readSafely()
    }

    private fun readSafely(): Map<Long, EmbeddedMetadataCacheEntry> {
        val baseFile = atomicFile.baseFile
        if (!baseFile.exists()) return emptyMap()
        if (baseFile.length() <= 0L || baseFile.length() > MAX_CACHE_BYTES) {
            atomicFile.delete()
            return emptyMap()
        }

        return try {
            val root = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                .let(::JSONObject)
            require(root.optString("schema") == CACHE_SCHEMA)
            val array = root.getJSONArray("entries")
            require(array.length() <= MAX_ENTRIES)

            buildMap {
                for (index in 0 until array.length()) {
                    val entry = array.getJSONObject(index).toCacheEntry()
                    put(entry.key.mediaId, entry)
                }
            }
        } catch (error: Exception) {
            LogUtils.w("[LMedia] Embedded metadata cache is invalid; rebuilding it.", error)
            atomicFile.delete()
            emptyMap()
        }
    }

    private fun writeSafely(nextEntries: Collection<EmbeddedMetadataCacheEntry>) {
        val root = JSONObject()
            .put("schema", CACHE_SCHEMA)
            .put("entries", JSONArray().also { array ->
                nextEntries.forEach { array.put(it.toJson()) }
            })
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CACHE_BYTES) {
            LogUtils.w("[LMedia] Embedded metadata cache exceeded its size limit; skipping disk cache.")
            atomicFile.delete()
            return
        }

        atomicFile.baseFile.parentFile?.mkdirs()
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
            output = null
        } catch (error: Exception) {
            output?.let(atomicFile::failWrite)
            LogUtils.w("[LMedia] Failed to persist embedded metadata cache.", error)
        }
    }

    private companion object {
        const val CACHE_SCHEMA = "armusic-embedded-metadata-v1"
        const val MAX_ENTRIES = 4_096
        const val MAX_CACHE_BYTES = 4 * 1024 * 1024
        const val MAX_STRING_LENGTH = 32_768
    }

    private fun JSONObject.toCacheEntry(): EmbeddedMetadataCacheEntry {
        val key = EmbeddedMetadataCacheKey(
            mediaId = getLong("mediaId"),
            path = getBoundedString("path"),
            size = getLong("size"),
            dateModified = getLong("dateModified"),
            fileModifiedMs = getLong("fileModifiedMs"),
        )
        require(key.mediaId >= 0L && key.path.isNotBlank() && key.size >= 0L)
        return EmbeddedMetadataCacheEntry(
            key = key,
            metadata = getJSONObject("metadata").toMetadata(),
        )
    }

    private fun JSONObject.toMetadata(): Metadata = Metadata(
        title = getBoundedString("title"),
        album = getBoundedString("album"),
        artist = getBoundedString("artist"),
        albumArtist = getBoundedString("albumArtist"),
        composer = getBoundedString("composer"),
        lyricist = getBoundedString("lyricist"),
        comment = getBoundedString("comment"),
        genre = getBoundedString("genre"),
        track = getBoundedString("track"),
        disc = getBoundedString("disc"),
        date = getBoundedString("date"),
        work = getBoundedString("work"),
        sameSongGroup = getBoundedString("sameSongGroup"),
        duration = getLong("duration"),
        dateAdded = getLong("dateAdded"),
        dateModified = getLong("dateModified"),
    )

    private fun JSONObject.getBoundedString(name: String): String {
        return getString(name).also { require(it.length <= MAX_STRING_LENGTH) }
    }
}

internal data class EmbeddedMetadataCacheKey(
    val mediaId: Long,
    val path: String,
    val size: Long,
    val dateModified: Long,
    val fileModifiedMs: Long,
) {
    companion object {
        fun from(audio: Audio): EmbeddedMetadataCacheKey {
            val filePath = audio.data.orEmpty()
            return EmbeddedMetadataCacheKey(
                mediaId = audio.id,
                path = filePath.ifBlank { audio.id.mediaUri().toString() },
                size = audio.size.coerceAtLeast(0L),
                dateModified = audio.dateModified ?: -1L,
                fileModifiedMs = filePath.takeIf(String::isNotBlank)
                    ?.let { runCatching { File(it).lastModified() }.getOrDefault(0L) }
                    ?: 0L,
            )
        }
    }
}

internal data class EmbeddedMetadataCacheEntry(
    val key: EmbeddedMetadataCacheKey,
    val metadata: Metadata,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mediaId", key.mediaId)
        .put("path", key.path)
        .put("size", key.size)
        .put("dateModified", key.dateModified)
        .put("fileModifiedMs", key.fileModifiedMs)
        .put("metadata", metadata.toJson())
}

private fun Metadata.toJson(): JSONObject = JSONObject()
    .put("title", title)
    .put("album", album)
    .put("artist", artist)
    .put("albumArtist", albumArtist)
    .put("composer", composer)
    .put("lyricist", lyricist)
    .put("comment", comment)
    .put("genre", genre)
    .put("track", track)
    .put("disc", disc)
    .put("date", date)
    .put("work", work)
    .put("sameSongGroup", sameSongGroup)
    .put("duration", duration)
    .put("dateAdded", dateAdded)
    .put("dateModified", dateModified)
