package com.lalilu.lmusic.compose.component.playing

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.ToastUtils
import com.google.accompanist.flowlayout.FlowRow
import com.lalilu.component.IconTextButton
import com.lalilu.lmusic.utils.ARMusicFontManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingFontLibrary(
    title: String = "自定义字体",
    subTitle: String = "导入 TTF 字体后可直接选择",
    currentPath: () -> String,
    onPathSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    val manager = remember { ARMusicFontManager(application) }
    var version by remember { mutableStateOf(0) }
    val fonts = remember(version) { manager.listFonts() }
    val current = currentPath()
    val textColor = contentColorFor(backgroundColor = MaterialTheme.colors.background)

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) { manager.importFont(uri) }
            }.getOrElse {
                ToastUtils.showLong("导入字体失败：${it.message ?: "未知错误"}")
                return@launch
            }
            version += 1
            onPathSelected(file.absolutePath)
            ToastUtils.showShort("已导入字体：${file.displayName()}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(text = title, color = textColor, fontSize = 14.sp)
                Text(
                    text = current.takeIf { it.isNotBlank() }?.let { File(it).displayName() }
                        ?: subTitle,
                    fontSize = 12.sp,
                    color = textColor.copy(0.5f)
                )
            }
        }

        FlowRow(
            mainAxisSpacing = 10.dp,
            crossAxisSpacing = 8.dp
        ) {
            IconTextButton(
                text = "导入字体",
                color = Color(0xFF006E7C),
                onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "application/x-font-ttf",
                            "application/octet-stream",
                            "*/*",
                        )
                    )
                }
            )
            if (current.isNotBlank()) {
                IconTextButton(
                    text = "使用默认",
                    color = Color(0xFFC13D1A),
                    onClick = { onPathSelected("") }
                )
            }
            fonts.forEach { font ->
                FontPreviewButton(
                    font = font,
                    selected = font.absolutePath == current,
                    textColor = textColor,
                    onClick = { onPathSelected(font.absolutePath) },
                )
            }
        }
    }
}

@Composable
private fun FontPreviewButton(
    font: File,
    selected: Boolean,
    textColor: Color,
    onClick: () -> Unit,
) {
    val previewFamily = remember(font.absolutePath, font.lastModified()) {
        runCatching { FontFamily(Font(file = font)) }.getOrNull()
    }
    val borderColor = if (selected) Color(0xFF3EA22C) else Color(0x336D5B00)
    val backgroundColor = if (selected) Color(0x1F3EA22C) else Color(0x0F6D5B00)

    Surface(
        modifier = Modifier
            .widthIn(min = 132.dp, max = 220.dp)
            .heightIn(min = 58.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = FONT_PREVIEW_TEXT,
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = previewFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = font.displayName(),
                color = textColor.copy(alpha = 0.55f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun File.displayName(): String = name.substringBeforeLast('.', name)

private const val FONT_PREVIEW_TEXT = "ARMUSIC"
