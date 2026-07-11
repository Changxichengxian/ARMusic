package com.lalilu.lmusic.migration

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.BuildConfig
import com.lalilu.lmedia.LMedia
import com.lalilu.lmusic.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent

class ARMusicWorkMappingImportActivity : Activity() {
    private val workMappingManager: ARMusicWorkMappingManager by lazy {
        KoinJavaComponent.get(ARMusicWorkMappingManager::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null || uri.scheme !in ALLOWED_URI_SCHEMES) {
            ToastUtils.showShort(MSG_NO_MAPPING_FILE)
            openMainAndFinish()
            return
        }

        showImportConfirmation(
            uri = uri,
            title = "导入作品标签表？",
            impact = "会匹配表格中的歌曲，并把作品标签写进对应音乐文件；被匹配文件的元数据会发生变化。",
            onConfirm = {
                LMedia.whenReady {
                    CoroutineScope(Dispatchers.Main).launch {
                        importMapping(uri)
                    }
                }
            },
            onCancel = ::openMainAndFinish,
        )
    }

    private suspend fun importMapping(uri: Uri) {
        val result = workMappingManager.importFromUri(uri)
        Log.i(LOG_TAG, "Work mapping import: ${result.message}")
        ToastUtils.showLong(result.message)
        openMainAndFinish()
    }

    private fun openMainAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        val ACTION_IMPORT_WORK_MAPPING = "${BuildConfig.APPLICATION_ID}.IMPORT_WORK_MAPPING"
        private val ALLOWED_URI_SCHEMES = setOf("content", "file")
        private const val LOG_TAG = "ARMusicWorkImport"
        private const val MSG_NO_MAPPING_FILE = "\u6ca1\u6709\u6536\u5230\u4f5c\u54c1\u6620\u5c04\u6587\u4ef6"
    }
}
