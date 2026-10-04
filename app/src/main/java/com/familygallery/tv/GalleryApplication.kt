package com.familygallery.tv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.familygallery.tv.data.GalleryRepository
import com.familygallery.tv.data.GallerySync
import com.familygallery.tv.smb.SmbClient
import com.familygallery.tv.smb.SmbFetcher
import com.familygallery.tv.smb.SmbImage
import com.familygallery.tv.smb.SmbKeyer
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath

/**
 * App entry point. Owns the lightweight object graph (manual DI — no framework needed for
 * an app this size) and configures the Coil image loader to fetch over SMB with caching
 * tuned for weak devices.
 */
class GalleryApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(SmbKeyer())
                add(SmbFetcher.Factory(container.smbClient))
            }
            // Keep memory modest on low-RAM boxes; disk cache does the heavy lifting.
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("smb_images").absolutePath.toPath())
                    // Adaptive: a cheap TV box may not have 512 MiB to spare. Use a slice of
                    // whatever free space exists, clamped to a sane range; evictions just
                    // re-fetch the small pre-baked derivatives from the NAS.
                    .maxSizeBytes(adaptiveDiskCacheBytes(cacheDir))
                    .build()
            }
            // WebP decoding is CPU-heavy enough to starve Compose on quad-core Android TV
            // boxes when a new six-item row appears. Keep two cores available to rendering and
            // input; queued thumbnails still arrive progressively from the disk/SMB caches.
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
            // Crossfading every tile keeps two bitmaps/layers alive during rapid D-pad scroll
            // and is visibly janky on low-end TV GPUs. Cached derivatives appear immediately.
            .crossfade(false)
            .build()
}

/**
 * Disk cache size adapted to free space: 10% of what's free, clamped to [64 MiB, 512 MiB].
 * Extracted for readability and so the policy is easy to reason about.
 */
private fun adaptiveDiskCacheBytes(cacheDir: java.io.File): Long {
    val free = cacheDir.usableSpace.takeIf { it > 0 } ?: MIN_DISK_CACHE
    return (free / 10).coerceIn(MIN_DISK_CACHE, MAX_DISK_CACHE)
}

private const val MIN_DISK_CACHE = 64L * 1024 * 1024
private const val MAX_DISK_CACHE = 512L * 1024 * 1024

/** Manual DI container: singletons wired by hand. */
class AppContainer(context: Context) {
    val smbClient: SmbClient = SmbClient()
    private val sync = GallerySync(context, smbClient)
    val repository: GalleryRepository = GalleryRepository(context, smbClient, sync)
}
