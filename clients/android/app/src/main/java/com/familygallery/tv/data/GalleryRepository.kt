package com.familygallery.tv.data

import android.content.Context
import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.familygallery.tv.smb.SmbClient
import com.familygallery.tv.smb.SmbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap

/**
 * Single entry point for catalog data. [initialize] performs the SMB → local-DB sync and
 * opens the catalog; the Paging flows are only valid afterwards.
 */
class GalleryRepository(
    private val context: Context,
    private val smb: SmbClient,
    private val sync: GallerySync,
) {
    @Volatile private var catalog: CatalogDatabase? = null

    // Serializes initialize/retry so two coroutines can never race the catalog swap.
    private val initMutex = Mutex()

    /**
     * Every live paging source factory, so a catalog swap can invalidate them all and make
     * collectors reload against the new database.
     *
     * A [PagingSource] here holds no database handle — it calls [db] on each load — so after a
     * swap it *would* silently keep serving its already-loaded window from the old catalog while
     * fetching new pages from the new one. Invalidating forces a clean refresh instead.
     *
     * Weakly held: a drill-down creates a pager per album visited, and those become garbage as
     * soon as the level is popped and its `cachedIn` flow stops being collected. A strong list
     * would retain one factory per album viewed for the life of the process.
     */
    private val pagingFactories: MutableSet<InvalidatingPagingSourceFactory<Int, *>> =
        Collections.newSetFromMap(WeakHashMap())

    private fun db(): CatalogDatabase =
        requireNotNull(catalog) { "Repository not initialized" }

    /** Sync the catalog and open it. Returns the number of items, or a failure. */
    suspend fun initialize(): Result<Int> = withContext(Dispatchers.IO) {
        initMutex.withLock {
            runCatching {
                // Close any open reader BEFORE the sync so it can atomically replace the
                // catalog file — we must never overwrite an open SQLite database. On a kept
                // (unchanged/invalid-remote) catalog we simply reopen the same file.
                catalog?.close()
                catalog = null
                val dbFile = sync.ensureLocalDb()
                val opened = CatalogDatabase.open(dbFile)
                catalog = opened
                // Anything already on screen is now reading a closed database. Drop those
                // windows so Paging reloads from the catalog we just opened. Safe on first run
                // too: there is simply nothing registered yet.
                invalidatePaging()
                opened.photoCount()
            }
        }
    }

    /** Drops every live paging window so collectors reload from the currently open catalog. */
    private fun invalidatePaging() {
        val live = synchronized(pagingFactories) { pagingFactories.toList() }
        live.forEach { it.invalidate() }
    }

    fun timeline(): Flow<PagingData<PhotoEntity>> =
        pager(
            count = { db().photoCount() },
            page = { limit, offset -> db().timeline(limit, offset) },
        )

    /** Albums grouped into typed sections (place, camera, year) for the Albums tab. */
    suspend fun albumSections(): List<AlbumSection> = withContext(Dispatchers.IO) {
        db().albumsWithCovers()
            .groupBy { it.type }
            .toList()
            .sortedBy { (type, _) -> TYPE_ORDER.indexOf(type).let { if (it < 0) 99 else it } }
            .map { (type, albums) -> AlbumSection(type, albums) }
    }

    fun albumPhotos(albumId: Long): Flow<PagingData<PhotoEntity>> =
        pager(
            count = { db().albumPhotoCount(albumId) },
            page = { limit, offset -> db().albumPhotos(albumId, limit, offset) },
        )

    /**
     * The dynamic smart albums (Videos, On this day) for the Albums root, each only present when
     * non-empty. [monthDay] is today's "MM-dd" key for On this day; passed in so it stays fixed
     * for the lifetime of a loaded catalog and testable in isolation.
     */
    suspend fun smartAlbums(monthDay: String): List<SmartAlbum> = withContext(Dispatchers.IO) {
        buildList {
            db().videoCount().takeIf { it > 0 }?.let { count ->
                add(SmartAlbum(SmartAlbumKind.VIDEOS, count, db().videoCover()))
            }
            db().onThisDayCount(monthDay).takeIf { it > 0 }?.let { count ->
                add(SmartAlbum(SmartAlbumKind.ON_THIS_DAY, count, db().onThisDayCover(monthDay)))
            }
        }
    }

    fun videoPhotos(): Flow<PagingData<PhotoEntity>> =
        pager(
            count = { db().videoCount() },
            page = { limit, offset -> db().videos(limit, offset) },
        )

    fun onThisDayPhotos(monthDay: String): Flow<PagingData<PhotoEntity>> =
        pager(
            count = { db().onThisDayCount(monthDay) },
            page = { limit, offset -> db().onThisDayPhotos(monthDay, limit, offset) },
        )

    /** Non-empty seasons (winter→autumn) across all years, for the Seasons drill-down. */
    suspend fun seasons(): List<SeasonSummary> =
        withContext(Dispatchers.IO) { db().seasonSummaries() }

    fun seasonPhotos(season: Int): Flow<PagingData<PhotoEntity>> =
        pager(
            count = { db().seasonCount(season) },
            page = { limit, offset -> db().seasonPhotos(season, limit, offset) },
        )

    /** Months that have photos in [year] (for the Years → year → months drill-down). */
    suspend fun monthsInYear(year: Int): List<MonthSummary> =
        withContext(Dispatchers.IO) { db().monthsInYear(year) }

    fun monthPhotos(year: Int, month: Int): Flow<PagingData<PhotoEntity>> =
        pager(
            count = { db().monthPhotoCount(year, month) },
            page = { limit, offset -> db().monthPhotos(year, month, limit, offset) },
        )

    suspend fun photo(id: Long): PhotoEntity? =
        withContext(Dispatchers.IO) { db().photoById(id) }

    /**
     * Derivative-generation string of the currently-open catalog; used to key the image cache.
     * Stable across ordinary catalog updates so unchanged thumbnails stay cache hits.
     */
    fun currentCacheVersion(): String = sync.currentCacheVersion()

    /** Maps a catalog path (thumb/preview) to a Coil-loadable SMB model. */
    fun image(relativePath: String): SmbImage = SmbImage(relativePath, currentCacheVersion())

    private fun <T : Any> pager(
        count: suspend () -> Int,
        page: suspend (limit: Int, offset: Int) -> List<T>,
    ): Flow<PagingData<T>> {
        val factory = InvalidatingPagingSourceFactory { OffsetPagingSource(count, page) }
        synchronized(pagingFactories) { pagingFactories.add(factory) }
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE,
                // Placeholders give accurate totals + stable absolute positions, which the
                // viewer relies on for "position / total" and cross-boundary navigation.
                enablePlaceholders = true,
                initialLoadSize = PAGE_SIZE * 2,
                // Bound resident pages so browsing 20k items can't retain them all.
                maxSize = MAX_ITEMS_IN_MEMORY,
                // Fast D-pad scrolling jumps to the anchor instead of loading every page.
                jumpThreshold = PAGE_SIZE * 3,
            ),
            pagingSourceFactory = factory,
        ).flow
    }

    private companion object {
        const val PAGE_SIZE = 60
        // >= pageSize + 2*prefetchDistance (60 + 120); 8 pages keeps memory modest.
        const val MAX_ITEMS_IN_MEMORY = PAGE_SIZE * 8
        val TYPE_ORDER = listOf("trip", "place", "camera", "year")
    }
}
