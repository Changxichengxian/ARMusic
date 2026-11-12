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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lalilu.common.SystemUiUtil
import com.lalilu.lmusic.Config.REQUIRE_PERMISSIONS
import com.lalilu.lmusic.compose.App
import com.lalilu.lmusic.helper.LastTouchTimeHelper
import com.lalilu.lmusic.utils.dynamicUpdateStatusBarColor
import com.lalilu.lmusic.utils.setToMaxFreshRate

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ARMusicLanguage.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        val shouldRequestPermission = !hasAudioPermission()
        val preparePlayback = !shouldRequestPermission && !ARMusicDeferredStartup.firstFrameReady
        var keepSplash = preparePlayback
        splash.setKeepOnScreenCondition { keepSplash }

        if (shouldRequestPermission) {
            ActivityCompat.requestPermissions(this, arrayOf(REQUIRE_PERMISSIONS), 1001)
        }

        AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM)

        // 注册返回键事件回调
        onBackPressedDispatcher.addCallback { this@MainActivity.moveTaskToBack(false) }

        SystemUiUtil.immerseNavigationBar(this)
        SystemUiUtil.immersiveCutout(window)

        if (!shouldRequestPermission) {
            ARMusicDeferredStartup.start(application)
        }

        setContent {
            App.Content(activity = this, preparePlayback = preparePlayback) {
                ARMusicDeferredStartup.firstFrameReady = true
                keepSplash = false
            }
        }
        setToMaxFreshRate()
        dynamicUpdateStatusBarColor()

        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            ARMusicDeferredStartup.start(application)
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

}
