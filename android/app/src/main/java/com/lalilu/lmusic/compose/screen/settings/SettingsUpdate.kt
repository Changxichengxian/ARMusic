package com.lalilu.lmusic.compose.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.BuildConfig
import com.lalilu.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal const val ARMUSIC_GITHUB_URL = "https://github.com/Changxichengxian/ARMusic"

private const val ARMUSIC_GITHUB_LATEST_RELEASE_URL =
    "https://github.com/Changxichengxian/ARMusic/releases/latest"
private const val ARMUSIC_GITHUB_LATEST_RELEASE_API =
    "https://api.github.com/repos/Changxichengxian/ARMusic/releases/latest"
private const val ARMUSIC_GITHUB_TAGS_API =
    "https://api.github.com/repos/Changxichengxian/ARMusic/tags"

@Composable
internal fun AboutUpdateCategory(
    icon: Painter,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val title = stringResource(id = R.string.settings_about_update)
            val color = contentColorFor(MaterialTheme.colors.background).copy(0.7f)
            Icon(
                modifier = Modifier.size(24.dp),
                painter = icon,
                contentDescription = title,
                tint = color,
            )
            Text(
                text = title,
                fontSize = 14.sp,
                color = color,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                color = color.copy(alpha = 0.58f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        content()
        Spacer(modifier = Modifier.height(30.dp))
    }
}

internal data class GithubVersionInfo(
    val version: String,
    val url: String,
)

internal fun fetchLatestGithubVersion(client: OkHttpClient): GithubVersionInfo {
    fetchLatestReleaseRedirect(client)?.let { return it }
    fetchLatestRelease(client)?.let { return it }
    fetchLatestTag(client)?.let { return it }
    error("没有找到可用的 GitHub 版本信息")
}

private fun fetchLatestReleaseRedirect(client: OkHttpClient): GithubVersionInfo? {
    val request = Request.Builder()
        .url(ARMUSIC_GITHUB_LATEST_RELEASE_URL)
        .header("User-Agent", "ARMusic/${BuildConfig.VERSION_NAME}")
        .build()

    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null

        val finalUrl = response.request.url.toString()
        val version = finalUrl
            .substringAfter("/releases/tag/", missingDelimiterValue = "")
            .substringBefore("?")
            .takeIf(String::isNotBlank)
            ?: return null

        GithubVersionInfo(
            version = version,
            url = finalUrl
        )
    }
}

private fun fetchLatestRelease(client: OkHttpClient): GithubVersionInfo? {
    val request = Request.Builder()
        .url(ARMUSIC_GITHUB_LATEST_RELEASE_API)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "ARMusic/${BuildConfig.VERSION_NAME}")
        .build()

    return client.newCall(request).execute().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) error("GitHub 返回 ${response.code}")

        val json = JSONObject(response.body?.string().orEmpty())
        val version = json.optString("tag_name").ifBlank { json.optString("name") }
        if (version.isBlank()) return null

        GithubVersionInfo(
            version = version,
            url = json.optString("html_url").ifBlank { ARMUSIC_GITHUB_URL }
        )
    }
}

private fun fetchLatestTag(client: OkHttpClient): GithubVersionInfo? {
    val request = Request.Builder()
        .url(ARMUSIC_GITHUB_TAGS_API)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "ARMusic/${BuildConfig.VERSION_NAME}")
        .build()

    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("GitHub 返回 ${response.code}")

        val tags = JSONArray(response.body?.string().orEmpty())
        if (tags.length() == 0) return null

        val tag = tags.getJSONObject(0)
        val version = tag.optString("name")
        if (version.isBlank()) return null

        GithubVersionInfo(
            version = version,
            url = "$ARMUSIC_GITHUB_URL/releases/tag/$version"
        )
    }
}

internal fun compareVersionName(left: String, right: String): Int {
    val leftParts = left.versionParts()
    val rightParts = right.versionParts()
    val size = maxOf(leftParts.size, rightParts.size)

    repeat(size) { index ->
        val leftValue = leftParts.getOrElse(index) { 0 }
        val rightValue = rightParts.getOrElse(index) { 0 }
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }

    return 0
}

private fun String.versionParts(): List<Int> {
    return trim()
        .trimStart('v', 'V')
        .substringBefore('-')
        .split('.', '_')
        .mapNotNull { part -> part.filter(Char::isDigit).toIntOrNull() }
        .ifEmpty { listOf(0) }
}

internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        ToastUtils.showShort(context.getString(R.string.settings_no_browser))
    }
}
