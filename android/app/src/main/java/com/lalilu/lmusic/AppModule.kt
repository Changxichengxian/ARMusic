package com.lalilu.lmusic

import StatusBarLyric.API.StatusBarLyric
import android.app.Application
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.ViewModelStoreOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.transitionFactory
import com.lalilu.BuildConfig
import com.funny.data_saver.core.DataSaverInterface
import com.funny.data_saver.core.DataSaverPreferences
import com.lalilu.R
import com.lalilu.lhistory.HistoryStatIdResolver
import com.lalilu.lmusic.lyric.ARMusicStatusLyricController
import com.lalilu.lmusic.Config.ITUNES_BASEURL
import com.lalilu.lmusic.Config.LRCSHARE_BASEURL
import com.lalilu.lmusic.Config.LRCLIB_BASEURL
import com.lalilu.lmusic.Config.NETEASE_BASEURL
import com.lalilu.lmusic.api.itunes.ItunesSearchApi
import com.lalilu.lmusic.api.lrcshare.LrcShareApi
import com.lalilu.lmusic.api.lrclib.LrclibApi
import com.lalilu.lmusic.api.netease.NeteaseMusicApi
import com.lalilu.lmusic.api.tag.OnlineTagSearchService
import com.lalilu.lmusic.agent.ARMusicAgentBundleImporter
import com.lalilu.lmusic.agent.ARMusicAgentFiles
import com.lalilu.lmusic.agent.ARMusicAgentLibraryExporter
import com.lalilu.lmusic.agent.ARMusicAgentManager
import com.lalilu.lmusic.datastore.SettingsSp
import com.lalilu.lmusic.datastore.TempSp
import com.lalilu.lmusic.migration.ARMusicBackupCodec
import com.lalilu.lmusic.migration.ARMusicHistoryMigrator
import com.lalilu.lmusic.migration.ARMusicMemoSeedImporter
import com.lalilu.lmusic.migration.ARMusicPlayCountSeedImporter
import com.lalilu.lmusic.migration.ARMusicPreferenceMigrator
import com.lalilu.lmusic.migration.ARMusicWorkMappingManager
import com.lalilu.lmusic.migration.LMusicMigrationManager
import com.lalilu.lmusic.sync.ARMusicAndroidManifestBuilder
import com.lalilu.lmusic.sync.ARMusicLanDiscoveryClient
import com.lalilu.lmusic.sync.ARMusicLanSyncClient
import com.lalilu.lmusic.sync.ARMusicTrackDownloader
import com.lalilu.lmusic.sync.ARMusicTrackUploader
import com.lalilu.lmusic.tag.ARMusicHistoryStatIdResolver
import com.lalilu.lmusic.tag.SongGroupStore
import com.lalilu.lmusic.utils.EQHelper
import com.lalilu.lmusic.utils.coil.CrossfadeTransitionFactory
import com.lalilu.lmusic.utils.coil.fetcher.IndexedMediaItemCoverFetcher
import com.lalilu.lmusic.utils.coil.fetcher.LAlbumFetcher
import com.lalilu.lmusic.utils.coil.fetcher.LSongFetcher
import com.lalilu.lmusic.utils.coil.fetcher.MediaItemFetcher
import com.lalilu.lmusic.utils.coil.keyer.IndexedMediaItemCoverKeyer
import com.lalilu.lmusic.utils.coil.keyer.LAlbumCoverKeyer
import com.lalilu.lmusic.utils.coil.keyer.LSongCoverKeyer
import com.lalilu.lmusic.utils.coil.keyer.MediaItemKeyer
import com.lalilu.lmusic.utils.extension.toBitmap
import com.lalilu.lmusic.viewmodel.SearchLyricViewModel
import com.lalilu.lplayer.service.StatusLyricController
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@Module
@ComponentScan("com.lalilu.lmusic")
object MainModule

@Single
fun provideDataSaverInterface(
    application: Application
): DataSaverInterface {
    val sp = application.getSharedPreferences("settings", Application.MODE_PRIVATE)
    return DataSaverPreferences(sp)
}

@Single
fun provideJson(): Json {
    return Json {
        ignoreUnknownKeys = true
    }
}

@Single
fun provideImageLoaderFactory(
    context: Application,
    client: OkHttpClient,
): SingletonImageLoader.Factory {
    return SingletonImageLoader.Factory {
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(client))
                add(LSongCoverKeyer())
                add(LAlbumCoverKeyer())
                add(MediaItemKeyer())
                add(IndexedMediaItemCoverKeyer())
                add(LSongFetcher.SongFactory())
                add(LAlbumFetcher.AlbumFactory())
                add(IndexedMediaItemCoverFetcher.Factory())
                add(MediaItemFetcher.MediaItemFetcherFactory())
            }
            .transitionFactory(CrossfadeTransitionFactory())
//            .logger(DebugLogger())
            .build()
    }
}

val AppModule = module {
    single<ViewModelStoreOwner> { androidApplication() as ViewModelStoreOwner }
    single { SettingsSp(androidApplication()) }
    single { TempSp(androidApplication()) }
    single { SongGroupStore(androidApplication(), get()) }
    single<HistoryStatIdResolver> { ARMusicHistoryStatIdResolver(get()) }
    single { ARMusicPreferenceMigrator(androidApplication()) }
    single { ARMusicHistoryMigrator(get()) }
    single { ARMusicBackupCodec(androidApplication(), get(), get()) }
    single { LMusicMigrationManager(androidApplication(), get(), get(), get()) }
    single { ARMusicWorkMappingManager(androidApplication(), get()) }
    single { ARMusicAgentFiles(androidApplication()) }
    single { ARMusicAgentLibraryExporter(get(), get(), get()) }
    single { ARMusicAgentBundleImporter(get(), get(), get(), get()) }
    single { ARMusicAgentManager(get(), get(), get(), get()) }
    single { ARMusicMemoSeedImporter(androidApplication(), get()) }
    single { ARMusicPlayCountSeedImporter(androidApplication(), get(), get()) }
    single { EQHelper(androidApplication()) }
    single<StatusLyricController> { ARMusicStatusLyricController(get(), get()) }
    single {
        StatusBarLyric(
            androidContext(),
            ContextCompat.getDrawable(androidContext(), R.mipmap.ic_launcher)?.toBitmap()
                ?.toDrawable(androidContext().resources),
            BuildConfig.APPLICATION_ID,
            false
        )
    }
}

val ViewModelModule = module {
    viewModelOf(::SearchLyricViewModel)
}

val ApiModule = module {
    single { GsonConverterFactory.create() }
    single { OkHttpClient.Builder().build() }
    single { ARMusicLanDiscoveryClient(get()) }
    single { ARMusicLanSyncClient(get(), get()) }
    single { ARMusicAndroidManifestBuilder(androidApplication()) }
    single { ARMusicTrackDownloader(androidApplication(), get()) }
    single { ARMusicTrackUploader(androidApplication(), get(), get()) }
    single { OnlineTagSearchService(get(), get(), get(), get()) }
    single {
        Retrofit.Builder()
            .client(get())
            .addConverterFactory(get<GsonConverterFactory>())
            .baseUrl(LRCSHARE_BASEURL)
            .build()
            .create(LrcShareApi::class.java)
    }
    single {
        Retrofit.Builder()
            .client(get())
            .addConverterFactory(get<GsonConverterFactory>())
            .baseUrl(ITUNES_BASEURL)
            .build()
            .create(ItunesSearchApi::class.java)
    }
    single {
        Retrofit.Builder()
            .client(get())
            .addConverterFactory(get<GsonConverterFactory>())
            .baseUrl(LRCLIB_BASEURL)
            .build()
            .create(LrclibApi::class.java)
    }
    single {
        Retrofit.Builder()
            .client(get())
            .addConverterFactory(get<GsonConverterFactory>())
            .baseUrl(NETEASE_BASEURL)
            .build()
            .create(NeteaseMusicApi::class.java)
    }
}
