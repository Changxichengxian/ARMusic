package com.lalilu.lmusic.migration

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.provider.OpenableColumns

internal fun Activity.showImportConfirmation(
    uri: Uri,
    title: String,
    impact: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val source = describeImportSource(uri)
    var handled = false
    fun cancelOnce() {
        if (handled) return
        handled = true
        onCancel()
    }

    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage("来源：$source\n\n影响：$impact\n\n只有确认后才会开始写入。")
        .setPositiveButton("确认导入") { _, _ ->
            if (!handled) {
                handled = true
                onConfirm()
            }
        }
        .setNegativeButton("取消") { _, _ -> cancelOnce() }
        .setOnCancelListener { cancelOnce() }
        .show()
}

private fun Activity.describeImportSource(uri: Uri): String {
    val displayName = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.takeIf(String::isNotBlank)
        ?: "未命名文件"
    val provider = uri.authority?.takeIf(String::isNotBlank) ?: uri.scheme.orEmpty()
    return if (provider.isBlank()) displayName else "$displayName（$provider）"
}
