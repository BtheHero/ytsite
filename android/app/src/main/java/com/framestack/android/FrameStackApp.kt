package com.framestack.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.framestack.android.data.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe

class FrameStackApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DownloaderImpl.init(this)
        NewPipe.init(DownloaderImpl.getInstance())
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(DownloaderImpl.getInstance().client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024L * 1024L)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}
