package com.lalilu.lmusic

import android.app.Application
import com.lalilu.lmedia.LMedia
import com.lalilu.lmedia.extension.KanjiToHiraTransformer
import com.lalilu.lmusic.migration.ARMusicMemoSeedImporter
import com.lalilu.lmusic.migration.ARMusicPlayCountSeedImporter
import com.lalilu.lmusic.utils.ARMusicFontManager
import com.lalilu.lplayer.MPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import java.util.concurrent.atomic.AtomicBoolean

object ARMusicDeferredStartup {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(application: Application) {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            withContext(Dispatchers.IO) {
                ARMusicFontManager(application).seedBundledFonts()
            }

            runCatching { KoinJavaComponent.get<ARMusicMemoSeedImporter>(ARMusicMemoSeedImporter::class.java) }
            runCatching { KoinJavaComponent.get<ARMusicPlayCountSeedImporter>(ARMusicPlayCountSeedImporter::class.java) }

            LMedia.whenReady { MPlayer.init() }
            LMedia.init(application)
            KanjiToHiraTransformer.init(application)
        }
    }
}
