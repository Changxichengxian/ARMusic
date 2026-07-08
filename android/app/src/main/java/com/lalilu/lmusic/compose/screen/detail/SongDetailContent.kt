package com.lalilu.lmusic.compose.screen.detail

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lalilu.common.base.SourceType
import com.lalilu.component.base.LocalSmartBarPadding
import com.lalilu.component.extension.clipFade
import com.lalilu.lmedia.entity.FileInfo
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmedia.entity.Metadata
import com.lalilu.lmedia.repository.SongWorkStore
import com.lalilu.lmusic.compose.screen.detail.component.SongActionsCard
import com.lalilu.lmusic.compose.screen.detail.component.SongAlbumInfoCard
import com.lalilu.lmusic.compose.screen.detail.component.SongArtistsRow
import com.lalilu.lmusic.compose.screen.detail.component.SongInformationCard
import org.koin.compose.koinInject


@Composable
fun SongDetailContent(
    modifier: Modifier = Modifier,
    song: () -> LSong? = { lSong },
    songWorkStore: SongWorkStore = koinInject(),
) {
    val bottomPadding = LocalSmartBarPadding.current.value
    val navigationBar = WindowInsets.navigationBars.asPaddingValues()

    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = navigationBar.calculateBottomPadding()
                    + bottomPadding.calculateBottomPadding()
                    + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "MAIN_COVER") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                AsyncImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clipFade(
                            lengthDp = 300.dp,
                            alignmentY = Alignment.Bottom
                        ),
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song())
                        .size(1024)
                        .crossfade(true)
                        .build(),
                    contentScale = ContentScale.FillWidth,
                    contentDescription = ""
                )

                song()?.let { song ->
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp),
                            text = song.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            color = MaterialTheme.colors.onBackground,
                        )

                        SongArtistsRow(
                            modifier = Modifier.fillMaxWidth(),
                            artists = song.artists
                        )
                    }
                }
            }
        }

        song()?.let { song ->
            item(key = "WORK") {
                val workVersion by songWorkStore.changes.collectAsState()
                val work = remember(song.id, workVersion) {
                    songWorkStore.workForSong(song)
                }
                work.let {
                    SongAlbumInfoCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        album = it
                    )
                }
            }

            item(key = "ACTIONS") {
                SongActionsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    song = song
                )
            }

            item(key = "INFOS") {
                SongInformationCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    song = song
                )
            }
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SongDetailContentPreview() {
    SongDetailContent()
}

private val lSong = LSong(
    id = "inceptos",
    metadata = Metadata(
        title = "maluisset",
        album = "honestatis",
        artist = "persius",
        albumArtist = "simul",
        composer = "eum",
        lyricist = "eos",
        comment = "morbi",
        genre = "dolore",
        track = "oratio",
        disc = "sapien",
        date = "iudicabit",
        duration = 5920,
        dateAdded = 2540,
        dateModified = 3267
    ), fileInfo = FileInfo(
        mimeType = "molestiae",
        directoryPath = "amet",
        pathStr = null,
        fileName = null,
        size = 5613
    ), uri = Uri.EMPTY,
    sourceType = SourceType.Local, albumId = null
)
