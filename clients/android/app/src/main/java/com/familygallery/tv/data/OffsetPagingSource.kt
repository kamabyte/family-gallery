package com.familygallery.tv.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Count-aware LIMIT/OFFSET paging over the static catalog, with placeholders.
 *
 * Because the catalog is immutable during a session (it's only swapped on the next SMB
 * re-sync), a total row count is cheap and stable, so we can enable placeholders: the
 * loaded window carries accurate [LoadResult.Page.itemsBefore]/[itemsAfter], which keeps
 * every item at its true **absolute** position and makes `LazyPagingItems.itemCount` equal
 * the real library total (what the viewer shows as `position / total`).
 *
 * The offset/limit/prev/next key math is the proven Room `LimitOffsetPagingSource` scheme,
 * factored into [OffsetKeyMath] so it can be unit-tested without a database.
 */
class OffsetPagingSource<T : Any>(
    private val countItems: suspend () -> Int,
    private val loadPage: suspend (limit: Int, offset: Int) -> List<T>,
) : PagingSource<Int, T>() {

    // Fast D-pad scrolling across thousands of rows should jump to the anchor and reload,
    // not page through every row in between.
    override val jumpingSupported: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> =
        withContext(Dispatchers.IO) {
            try {
                val total = countItems()
                val key = params.key ?: 0
                val offset = OffsetKeyMath.offset(params.toKind(), key, params.loadSize, total)
                val limit = OffsetKeyMath.limit(params.toKind(), key, params.loadSize)
                val items = if (limit <= 0 || offset >= total) emptyList() else loadPage(limit, offset)
                LoadResult.Page(
                    data = items,
                    prevKey = OffsetKeyMath.prevKey(offset),
                    nextKey = OffsetKeyMath.nextKey(offset, items.size, limit, total),
                    itemsBefore = offset,
                    itemsAfter = (total - (offset + items.size)).coerceAtLeast(0),
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? =
        OffsetKeyMath.refreshKey(state.anchorPosition, state.config.initialLoadSize)

    private fun LoadParams<Int>.toKind(): OffsetKeyMath.Kind = when (this) {
        is LoadParams.Refresh -> OffsetKeyMath.Kind.REFRESH
        is LoadParams.Prepend -> OffsetKeyMath.Kind.PREPEND
        is LoadParams.Append -> OffsetKeyMath.Kind.APPEND
    }
}

/**
 * Pure offset/key arithmetic for LIMIT/OFFSET paging with placeholders. Kept free of any
 * Paging/DB types so the tricky boundary behavior (prepend clipping near 0, next/prev keys,
 * refresh anchoring) is unit-testable.
 */
object OffsetKeyMath {
    enum class Kind { REFRESH, PREPEND, APPEND }

    /** Rows to load for this request; a prepend near the start is clipped to what remains. */
    fun limit(kind: Kind, key: Int, loadSize: Int): Int =
        if (kind == Kind.PREPEND && key < loadSize) key else loadSize

    /** Starting row offset for this request. */
    fun offset(kind: Kind, key: Int, loadSize: Int, itemCount: Int): Int = when (kind) {
        Kind.PREPEND -> if (key < loadSize) 0 else key - loadSize
        Kind.APPEND -> key
        Kind.REFRESH -> if (key >= itemCount) (itemCount - loadSize).coerceAtLeast(0) else key
    }.coerceAtLeast(0)

    /** Key for a further prepend: the start of what we just loaded (null at the top). */
    fun prevKey(offset: Int): Int? = if (offset <= 0) null else offset

    /** Key for a further append: the row just past what we loaded (null at the end). */
    fun nextKey(offset: Int, loaded: Int, limit: Int, itemCount: Int): Int? {
        val nextPos = offset + limit
        return if (loaded == 0 || loaded < limit || nextPos >= itemCount) null else nextPos
    }

    /** On refresh, re-anchor a window centered on the last-visible position. */
    fun refreshKey(anchorPosition: Int?, initialLoadSize: Int): Int? =
        anchorPosition?.let { (it - initialLoadSize / 2).coerceAtLeast(0) }
}
