package com.lalilu.lmusic

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.lalilu.common.SystemUiUtil
import com.lalilu.lmusic.Config.REQUIRE_PERMISSIONS
import com.lalilu.lmusic.compose.App
import com.lalilu.lmusic.datastore.SettingsSp
import com.lalilu.lmusic.helper.LastTouchTimeHelper
import com.lalilu.lmusic.utils.dynamicUpdateStatusBarColor
import com.lalilu.lmusic.utils.setToMaxFreshRate
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settingsSp: SettingsSp by inject()
    private var deferredStartupScheduled = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ARMusicLanguage.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shouldRequestPermission = !hasAudioPermission()
        if (shouldRequestPermission) {
            ActivityCompat.requestPermissions(this, arrayOf(REQUIRE_PERMISSIONS), 1001)
        }

        AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM)

        // 注册返回键事件回调
        onBackPressedDispatcher.addCallback { this@MainActivity.moveTaskToBack(false) }

        SystemUiUtil.immerseNavigationBar(this)
        SystemUiUtil.immersiveCutout(window)

        setContent { App.Content(activity = this) }
        setToMaxFreshRate()
        dynamicUpdateStatusBarColor()

        volumeControlStream = AudioManager.STREAM_MUSIC

        if (!shouldRequestPermission) {
            scheduleDeferredStartup()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            scheduleDeferredStartup()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        LastTouchTimeHelper.onDispatchTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun hasAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            REQUIRE_PERMISSIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun scheduleDeferredStartup() {
        if (deferredStartupScheduled) return
        deferredStartupScheduled = true

        window.decorView.post {
            lifecycleScope.launch {
                ARMusicDeferredStartup.start(application)
            }
        }
    }
}
