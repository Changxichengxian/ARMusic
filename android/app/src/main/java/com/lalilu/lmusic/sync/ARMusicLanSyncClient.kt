package com.lalilu.lmusic.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
