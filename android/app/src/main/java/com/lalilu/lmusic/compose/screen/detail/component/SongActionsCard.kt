package com.lalilu.lmusic.compose.screen.detail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lalilu.R
import com.lalilu.component.IconTextButton
import com.lalilu.component.navigation.AppRouter
import com.lalilu.component.navigation.NavIntent
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.compose.screen.tag.TagEditorScreen

@Composable
fun SongActionsCard(
    modifier: Modifier = Modifier,
    song: LSong,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTextButton(
                text = stringResource(id = R.string.song_action_tag_edit),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF3EA22C),
                onClick = {
                    AppRouter.intent(NavIntent.Push(TagEditorScreen(mediaId = song.id)))
                }
            )
        }
    }
}
