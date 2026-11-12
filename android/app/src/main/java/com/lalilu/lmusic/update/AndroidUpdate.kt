package com.lalilu.lmusic.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.lalilu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal const val ARMUSIC_GITHUB_URL = "https://github.com/Changxichengxian/ARMusic"
internal const val ARMUSIC_RELEASES_URL = "$ARMUSIC_GITHUB_URL/releases"
internal const val ANDROID_RELEASE_TAG_PREFIX = "android-v"

class UpdateFileProvider : FileProvider()

internal object AndroidUpdate {
    private const val MANIFEST = "armusic-android-update.json"
    private const val MANIFEST_LIMIT = 128L * 1024
    private const val RELEASES_API = "https://api.github.com/repos/Changxichengxian/ARMusic/releases"
    private const val RELEASES_LIMIT = 2L * 1024 * 1024
    private const val APK_LIMIT = 256L * 1024 * 1024
    private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    sealed interface Check {
        data object NoRelease : Check
        data object Current : Check
        data class NoPatch(val version: String) : Check
        data class Available(val update: DeltaUpdate) : Check
    }

    data class DeltaUpdate(
        val version: String,
        val versionCode: Long,
        val apkSize: Long,
        val apkHash: String,
        val patchAssetId: Long,
        val patchHash: String,
        val patchSize: Long,
    )

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        val release = findAndroidRelease() ?: return@withContext Check.NoRelease
        val manifestId = requireNotNull(release.assets[MANIFEST])
        val manifest = JSONObject(readText(assetRequest(manifestId), MANIFEST_LIMIT))

        require(manifest.getInt("schema") == 1)
        require(manifest.getString("packageName") == context.packageName)
        val version = manifest.getString("versionName")
        require(release.tag == "$ANDROID_RELEASE_TAG_PREFIX$version")
        val versionCode = manifest.getLong("versionCode")
        if (versionCode <= BuildConfig.VERSION_CODE) return@withContext Check.Current

        val apkSize = manifest.getLong("apkSize").also { require(it in 1..APK_LIMIT) }
        val apkHash = manifest.getString("apkSha256").also { require(hashPattern.matches(it)) }
        val patches = manifest.getJSONObject("patches")
        if (patches.length() == 0 || !context.applicationInfo.splitSourceDirs.isNullOrEmpty()) {
            return@withContext Check.NoPatch(version)
        }
        val baseHash = File(context.applicationInfo.sourceDir).sha256()
        val patch = patches.optJSONObject(baseHash) ?: return@withContext Check.NoPatch(version)
        val patchAssetId = requireNotNull(release.assets["ARMusic-Android-$version-from-$baseHash.ard.gz"])
        val patchHash = patch.getString("sha256").also { require(hashPattern.matches(it)) }
        val patchSize = patch.getLong("size").also { require(it in 1 until apkSize) }
        Check.Available(DeltaUpdate(version, versionCode, apkSize, apkHash, patchAssetId, patchHash, patchSize))
    }

    suspend fun prepare(context: Context, update: DeltaUpdate): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cache = File(context.cacheDir, "app-updates")
            val install = File(cache, "install")
            check(install.isDirectory || install.mkdirs())
            val apk = File(install, "ARMusic-${update.version}.apk")
            if (apk.length() == update.apkSize && apk.sha256() == update.apkHash) {
                verifyPackage(context, apk, update)
                return@withLock apk
            }
            val patch = File(cache, "download.part")
            val target = File(cache, "update.part.apk")
            try {
                client.newCall(assetRequest(update.patchAssetId)).execute().use { response ->
                    check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
                    val body = requireNotNull(response.body)
                    val count = patch.outputStream().buffered().use {
                        copyBounded(body.byteStream(), it, update.patchSize)
                    }
                    check(count == update.patchSize && patch.sha256() == update.patchHash)
                }

                val base = File(context.applicationInfo.sourceDir)
                target.outputStream().buffered().use { ApkDelta.apply(base, patch, it, update.apkSize) }
                check(target.sha256() == update.apkHash)
                verifyPackage(context, target, update)
                ensureActive()
                check(target.renameTo(apk))
                apk
            } finally {
                patch.delete()
                target.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", apk)
        context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri(apk.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File, update: DeltaUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val candidate = requireNotNull(manager.getPackageArchiveInfo(apk.path, flags))
        check(candidate.packageName == context.packageName)
        check(PackageInfoCompat.getLongVersionCode(candidate) == update.versionCode)
        val installedSigners = installed.signers()
        check(installedSigners.isNotEmpty() && installedSigners == candidate.signers())
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers() =
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
            .orEmpty().toSet()

    private data class AndroidRelease(val tag: String, val assets: Map<String, Long>)

    private suspend fun findAndroidRelease(): AndroidRelease? {
        val releases = JSONArray(readText(request("$RELEASES_API?per_page=30"), RELEASES_LIMIT))
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            val tag = release.getString("tag_name")
            val version = tag.removePrefix(ANDROID_RELEASE_TAG_PREFIX)
            if (version == tag || !versionPattern.matches(version) ||
                release.optBoolean("draft") || release.optBoolean("prerelease")) continue

            val entries = release.getJSONArray("assets")
            val assets = buildMap {
                for (entryIndex in 0 until entries.length()) {
                    val asset = entries.getJSONObject(entryIndex)
                    if (asset.getString("state") == "uploaded") {
                        put(asset.getString("name"), asset.getLong("id"))
                    }
                }
            }
            if (MANIFEST in assets) return AndroidRelease(tag, assets)
        }
        return null
    }

    private fun assetRequest(id: Long): Request {
        require(id > 0)
        return request("$RELEASES_API/assets/$id")
            .newBuilder()
            .header("Accept", "application/octet-stream")
            .build()
    }

    private suspend fun readText(request: Request, limit: Long): String =
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
            val bytes = ByteArrayOutputStream()
            copyBounded(requireNotNull(response.body).byteStream(), bytes, limit)
            bytes.toString(Charsets.UTF_8.name())
        }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "ARMusic/${BuildConfig.VERSION_NAME}")
        .build()

    private suspend fun copyBounded(source: InputStream, target: OutputStream, limit: Long): Long {
        val bytes = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = source.read(bytes)
            if (count == -1) return total
            total += count
            require(total <= limit)
            target.write(bytes, 0, count)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
