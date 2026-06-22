package com.lalilu.lmedia.scanner

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.DocumentsContract
import java.net.URLDecoder

class PathExclusionMatcher(
    excludedPaths: List<String>
) {
    private val roots = excludedPaths
        .mapNotNull { normalizePath(it) }
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()

    fun isExcluded(path: String?): Boolean {
        val normalized = normalizePath(path)?.trimEnd('/') ?: return false
        return roots.any { root ->
            normalized.equals(root, ignoreCase = true) ||
                normalized.startsWith("$root/", ignoreCase = true)
        }
    }

    fun isEmpty(): Boolean = roots.isEmpty()

    companion object {
        fun displayPath(path: String): String {
            return normalizePath(path) ?: path
        }

        @SuppressLint("NewApi")
        fun normalizePath(path: String?): String? {
            val trimmed = path
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            if (uri != null && uri.scheme != null) {
                when (uri.scheme) {
                    "file" -> return uri.path?.decode()?.normalizeSlashes()
                    "content" -> {
                        externalStoragePath(uri)?.let { return it.normalizeSlashes() }
                        return trimmed.decode().normalizeSlashes()
                    }
                }
            }

            return trimmed.decode().normalizeSlashes()
        }

        private fun externalStoragePath(uri: Uri): String? {
            if (uri.authority != "com.android.externalstorage.documents") return null

            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val id = documentId ?: treeId ?: return null
            val parts = id.split(":", limit = 2)
            val volume = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            val relativePath = parts.getOrNull(1).orEmpty().trim('/')

            val basePath = if (volume.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0"
            } else {
                "/storage/$volume"
            }

            return if (relativePath.isBlank()) basePath else "$basePath/$relativePath"
        }

        private fun String.normalizeSlashes(): String =
            replace(Regex("/{2,}"), "/")

        private fun String.decode(): String =
            runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    }
}
