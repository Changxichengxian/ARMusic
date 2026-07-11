package com.lalilu.lmusic.migration

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.lmedia.LMedia
import com.lalilu.lmusic.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent

/** DUMP-protected automation endpoint. Ordinary document URIs use the confirmed UI activity. */
class ARMusicWorkMappingPathImportActivity : Activity() {
    private val workMappingManager: ARMusicWorkMappingManager by lazy {
        KoinJavaComponent.get(ARMusicWorkMappingManager::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent?.getStringExtra(EXTRA_PATH).orEmpty()
        if (path.isBlank()) {
            ToastUtils.showShort("没有收到作品映射文件")
            finish()
            return
        }

        LMedia.whenReady {
            CoroutineScope(Dispatchers.Main).launch {
                val result = workMappingManager.importFromFilePath(path)
                Log.i(LOG_TAG, "Protected work mapping import: ${result.message}")
                ToastUtils.showLong(result.message)
                openMainAndFinish()
            }
        }
    }

    private fun openMainAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val LOG_TAG = "ARMusicWorkPathImport"
    }
}
