package com.streamverse.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StreamVerseApp : Application(), ImageLoaderFactory {

    /**
     * Tuned Coil loader for a logo-grid-heavy app: a generous memory + 100 MB disk cache so
     * channel logos load instantly on revisits and while scrolling, a crossfade for polish, and
     * respectCacheHeaders=false because most logo CDNs send no cache headers (otherwise Coil would
     * refuse to cache them and re-download every time).
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
}
