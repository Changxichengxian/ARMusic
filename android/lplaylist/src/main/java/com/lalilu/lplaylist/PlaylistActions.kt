package com.lalilu.lplaylist

import androidx.compose.ui.graphics.Color
import com.lalilu.RemixIcon
import com.lalilu.component.base.screen.ScreenAction
import com.lalilu.component.navigation.AppRouter
import com.lalilu.lmedia.entity.LSong
import com.lalilu.remixicon.Media
import com.lalilu.remixicon.media.playListAddLine
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [ScreenAction::class])
@Named("add_to_playlist_action")
fun provideAddToPlaylistAction(
    selectedItems: () -> Collection<LSong>
): ScreenAction.Static = ScreenAction.Static(
    title = { "添加到歌单" },
    icon = { RemixIcon.Media.playListAddLine },
    color = { Color(0xFF24A800) },
    onAction = {
        val items = selectedItems()

        AppRouter.route("/playlist/add")
            .with("mediaIds", items.map { it.id })
            .jump()
    }
)
