package com.lalilu.lmusic.sync

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.io.OutputStream

class ARMusicLanSyncClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchHealth(baseUrl: String): Result<ARMusicSyncHealth> = fetchJson(
        baseUrl = baseUrl,
        pathSegments = listOf("health"),
    )

    suspend fun fetchManifest(baseUrl: String): Result<ARMusicSyncManifest> = fetchJson(
        baseUrl = baseUrl,
        pathSegments = listOf("manifest"),
    )

    suspend fun fetchHistory(baseUrl: String): Result<ARMusicHistoryPayload> = fetchJson(
        baseUrl = baseUrl,
        pathSegments = listOf("history"),
    )

    suspend fun mergeHistory(
        baseUrl: String,
        payload: ARMusicHistoryPayload,
    ): Result<ARMusicHistoryMergeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(buildUrl(baseUrl, "history", "merge"))
                .post(
                    RequestBody.create(
                        "application/json; charset=utf-8".toMediaType(),
                        json.encodeToString(payload),
                    )
                )
                .build()
            client.newCall(request).execute().useSuccess { response ->
                val body = response.body?.string() ?: error("电脑端没有返回听歌记录")
                json.decodeFromString<ARMusicHistoryMergeResponse>(body)
            }
        }
    }

    suspend fun downloadTrack(
        baseUrl: String,
        syncId: String,
        outputStream: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(buildUrl(baseUrl, "tracks", syncId))
                .get()
                .build()

            client.newCall(request).execute().useSuccess { response ->
                val body = response.body ?: error("电脑端没有返回歌曲内容")
                body.byteStream().use { input ->
                    input.copyTo(outputStream)
                }
            }
        }
    }

    suspend fun uploadTrack(
        baseUrl: String,
        track: ARMusicSyncTrack,
        inputStream: InputStream,
    ): Result<Unit> = sendTrack(baseUrl, track, inputStream, replace = false, expectedDesktopRevision = null)

    suspend fun replaceTrack(
        baseUrl: String,
        track: ARMusicSyncTrack,
        inputStream: InputStream,
        expectedDesktopRevision: String,
    ): Result<Unit> = sendTrack(
        baseUrl,
        track,
        inputStream,
        replace = true,
        expectedDesktopRevision = expectedDesktopRevision,
    )

    private suspend fun sendTrack(
        baseUrl: String,
        track: ARMusicSyncTrack,
        inputStream: InputStream,
        replace: Boolean,
        expectedDesktopRevision: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = Base64.encodeToString(
                json.encodeToString(track).toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            val requestBuilder = Request.Builder()
                .url(buildUrl(baseUrl, "tracks", track.syncId))
                .header("X-ARMusic-Track", metadata)
            if (replace) requestBuilder.header(
                "X-ARMusic-If-Match",
                expectedDesktopRevision ?: error("缺少电脑端预览版本，已拒绝覆盖"),
            )
            val body = inputStream.asRequestBody(track.sizeBytes)
            val request = if (replace) requestBuilder.put(body).build() else requestBuilder.post(body).build()

            client.newCall(request).execute().useSuccess {}
        }
    }

    private suspend inline fun <reified T> fetchJson(
        baseUrl: String,
        pathSegments: List<String>,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(buildUrl(baseUrl, *pathSegments.toTypedArray()))
                .get()
                .build()

            client.newCall(request).execute().useSuccess { response ->
                val body = response.body?.string() ?: error("电脑端没有返回内容")
                json.decodeFromString<T>(body)
            }
        }
    }

    private fun buildUrl(baseUrl: String, vararg pathSegments: String): HttpUrl {
        val normalizedBaseUrl = baseUrl.trim()
            .takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
            ?: error("同步地址不能为空")

        val httpUrl = normalizedBaseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: error("同步地址格式不正确")
        val builder = httpUrl.newBuilder()
        pathSegments.forEach { segment ->
            builder.addPathSegment(segment)
        }
        return builder.build()
    }

    private fun InputStream.asRequestBody(sizeBytes: Long): RequestBody {
        return object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = sizeBytes.takeIf { it > 0 } ?: -1L
            override fun writeTo(sink: BufferedSink) {
                source().use { source ->
                    sink.writeAll(source)
                }
            }
        }
    }

    private inline fun <T> Response.useSuccess(block: (Response) -> T): T {
        return try {
            if (!isSuccessful) {
                error("电脑端返回错误：HTTP $code")
            }
            block(this)
        } finally {
            close()
        }
    }
}
