package com.fethica.swiftradio

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.fethica.swiftradio.data.ArtworkService
import com.fethica.swiftradio.data.StationsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class SwiftRadioApplication : Application(), SingletonImageLoader.Factory {

    private val applicationJob = SupervisorJob()
    val applicationScope = CoroutineScope(applicationJob + Dispatchers.Main)

    val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val stationsRepository: StationsRepository by lazy {
        StationsRepository(this, sharedOkHttpClient)
    }

    val artworkService: ArtworkService by lazy {
        ArtworkService(sharedOkHttpClient)
    }

    val squiggleService: com.fethica.swiftradio.data.SquiggleService by lazy {
        com.fethica.swiftradio.data.SquiggleService(sharedOkHttpClient, applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // AssetManagerFetcher is usually added automatically on Android, 
                // but we'll ensure it's there alongside our custom OkHttp fetcher.
                add(OkHttpNetworkFetcherFactory(callFactory = { sharedOkHttpClient }))
            }
            .build()
    }
}
