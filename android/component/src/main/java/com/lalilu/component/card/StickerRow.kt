package com.lalilu.component.card

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lalilu.common.base.Sticker
import com.lalilu.component.R
import com.lalilu.component.extension.mimeTypeToIcon
import com.lalilu.component.extension.toColorFilter

@Composable
fun StickerRow(
    hasLyric: () -> Boolean = { false },
    isHires: () -> Boolean = { false },
    extSticker: Sticker.ExtSticker? = null
) {
    if (hasLyric()) {
        HasLyricIcon(
            hasLyric = { true },
            fixedHeight = { true }
        )
    }

    if (isHires()) {
        Image(
            painter = painterResource(id = R.drawable.ic_ape_line),
            contentDescription = "Hires Icon",
            colorFilter = Color(0xFFFFC107).copy(0.9f).toColorFilter(),
            modifier = Modifier
                .size(20.dp)
                .aspectRatio(1f)
        )
    }

    extSticker?.let {
        Image(
            painter = painterResource(id = mimeTypeToIcon(mimeType = it.name)),
            contentDescription = "MediaType Icon",
            colorFilter = MaterialTheme.colors.onBackground.copy(0.9f).toColorFilter(),
            modifier = Modifier
                .size(20.dp)
                .aspectRatio(1f)
        )
    }
}
