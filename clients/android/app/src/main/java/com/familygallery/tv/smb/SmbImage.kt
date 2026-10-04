package com.familygallery.tv.smb

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.familygallery.tv.GalleryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.source

/**
 * Coil model for an image that lives on the SMB share, identified by its path relative to
 * the share root (e.g. `.gallery/thumbs/ab/<hash>_t.webp`). Feed one of these to
 * `AsyncImage(model = ...)`.
 *
 * [cacheVersion] is the active derivative generation captured **at construction**.
 * Because it's carried immutably on the model rather than read from a global at fetch time,
 * a catalog swap that happens mid-flight can never produce inconsistent memory/disk keys for
 * one request. Callers get it from the current catalog via `LocalCatalogCacheVersion`.
 */
data class SmbImage(val relativePath: String, val cacheVersion: String = "")

/**
 * Stable Coil cache identity for SMB-backed derivatives. Pure and unit-tested.
 *
 * The relative path alone is NOT a safe cache key: derivative filenames are addressed by the
 * *source* file's content hash, so re-encoding at different dimensions or with a newer encoder
 * (e.g. an indexer `--rebuild`) produces different bytes under the *same* path. And pointing
 * the app at a different NAS/share/base-path can reuse identical relative paths for entirely
 * different images. So the key folds in the library identity (host/share/base) AND the active
 * derivative generation, which advances only when bytes under existing paths may change.
 */
object SmbCacheKey {
    fun libraryId(host: String, share: String, basePath: String): String =
        Integer.toHexString("$host|$share|$basePath".hashCode())

    fun key(
        host: String,
        share: String,
        basePath: String,
        cacheVersion: String,
        relativePath: String,
    ): String = "smb:r$cacheVersion:${libraryId(host, share, basePath)}:$relativePath"
}

/** Cache key that changes when the NAS/share/base-path or derivative generation changes. */
class SmbKeyer : Keyer<SmbImage> {
    override fun key(data: SmbImage, options: Options): String = SmbCacheKey.key(
        host = GalleryConfig.HOST,
        share = GalleryConfig.SHARE,
        basePath = GalleryConfig.BASE_PATH,
        cacheVersion = data.cacheVersion,
        relativePath = data.relativePath,
    )
}

/**
 * Reads the image bytes over SMB and hands them to Coil. Coil's disk + memory caches sit in
 * front of this, so it only runs on a cache miss. Bytes are streamed straight into a single
 * okio buffer (no intermediate ByteArray), and a missing-file error surfaces as-is rather
 * than tearing down the shared SMB connection (see [SmbClient]).
 */
class SmbFetcher(
    private val data: SmbImage,
    private val options: Options,
    private val smb: SmbClient,
    private val permits: Semaphore,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val buffer = permits.withPermit {
            smb.read(data.relativePath) { input ->
                Buffer().apply { input.source().use { writeAll(it) } }
            }
        }
        SourceFetchResult(
            source = ImageSource(source = buffer, fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val smb: SmbClient) : Fetcher.Factory<SmbImage> {
        // Keep network parsing from occupying every core just as a new row is measured. Three
        // concurrent reads keep thumbnails arriving promptly while leaving one Mi Box core for
        // the main/render threads; WebP decoding is limited separately by the ImageLoader.
        private val permits = Semaphore(3)

        override fun create(data: SmbImage, options: Options, imageLoader: ImageLoader): Fetcher =
            SmbFetcher(data, options, smb, permits)
    }
}
