package com.lalilu.lmusic.compose.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.lalilu.BuildConfig
import com.lalilu.R
import com.lalilu.lmusic.update.ANDROID_RELEASE_TAG_PREFIX
import com.lalilu.lmusic.update.ARMUSIC_RELEASES_URL
import com.lalilu.lmusic.update.AndroidUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

internal data class OnlineUpdateAction(val label: String, val onClick: () -> Unit)

@Composable
internal fun onlineUpdateAction(): OnlineUpdateAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var manualVersion by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<File?>(null) }

    fun reportFailure(error: Exception) {
        LogUtils.w("Android update failed", error)
        ToastUtils.showLong(context.getString(R.string.settings_update_failed))
    }

    fun launchInstaller(apk: File) {
        try {
            AndroidUpdate.install(context, apk)
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingInstall
        pendingInstall = null
        if (apk != null && Build.VERSION.SDK_INT >= 26) {
            if (context.packageManager.canRequestPackageInstalls()) {
                launchInstaller(apk)
            } else {
                ToastUtils.showLong(context.getString(R.string.settings_update_install_permission))
            }
        }
    }

    manualVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { manualVersion = null },
            title = { Text(stringResource(R.string.settings_update_found, version)) },
            text = { Text(stringResource(R.string.settings_update_no_delta)) },
            confirmButton = {
                TextButton(onClick = {
                    manualVersion = null
                    openUrl(context, "$ARMUSIC_RELEASES_URL/tag/$ANDROID_RELEASE_TAG_PREFIX$version")
                }) {
                    Text(stringResource(R.string.settings_update_view_release))
                }
            },
            dismissButton = {
                TextButton(onClick = { manualVersion = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    val label = stringResource(when {
        downloading -> R.string.settings_update_preparing
        busy -> R.string.settings_checking_update
        else -> R.string.settings_check_update
    })
    return OnlineUpdateAction(label) {
        if (busy || pendingInstall != null) return@OnlineUpdateAction
        busy = true
        scope.launch {
            try {
                when (val result = AndroidUpdate.check(context)) {
                    AndroidUpdate.Check.NoRelease -> ToastUtils.showShort(
                        context.getString(R.string.settings_update_no_release)
                    )
                    AndroidUpdate.Check.Current -> ToastUtils.showShort(
                        context.getString(R.string.settings_update_current, BuildConfig.VERSION_NAME)
                    )
                    is AndroidUpdate.Check.NoPatch -> manualVersion = result.version
                    is AndroidUpdate.Check.Available -> {
                        downloading = true
                        val apk = AndroidUpdate.prepare(context, result.update)
                        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                            pendingInstall = apk
                            ToastUtils.showLong(context.getString(R.string.settings_update_install_permission))
                            permissionLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } else {
                            launchInstaller(apk)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pendingInstall = null
                reportFailure(error)
            } finally {
                downloading = false
                busy = false
            }
        }
    }
}

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

internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        ToastUtils.showShort(context.getString(R.string.settings_no_browser))
    }
}
