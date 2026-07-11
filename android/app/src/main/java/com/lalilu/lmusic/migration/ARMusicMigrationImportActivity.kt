package com.lalilu.lmusic.migration

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.lmusic.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent

class ARMusicMigrationImportActivity : Activity() {
    private val migrationManager: LMusicMigrationManager by lazy {
        KoinJavaComponent.get(LMusicMigrationManager::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null || uri.scheme !in ALLOWED_URI_SCHEMES) {
            ToastUtils.showShort("没有收到迁移文件")
            openMainAndFinish()
            return
        }

        showImportConfirmation(
            uri = uri,
            title = "导入 ARMusic 备份？",
            impact = "会导入备份中的设置并合并听歌历史；同名设置可能改成备份里的值。",
            onConfirm = {
                CoroutineScope(Dispatchers.Main).launch {
                    val result = migrationManager.importFromUri(uri)
                    ToastUtils.showLong(result.message)
                    openMainAndFinish()
                }
            },
            onCancel = ::openMainAndFinish,
        )
    }

    private fun openMainAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private val ALLOWED_URI_SCHEMES = setOf("content", "file")
    }
}
