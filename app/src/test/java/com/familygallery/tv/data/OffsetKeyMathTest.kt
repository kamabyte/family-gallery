package com.familygallery.tv.data

import com.familygallery.tv.data.OffsetKeyMath.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Boundary behavior of the LIMIT/OFFSET key math. These are the cases that, when wrong,
 * produce gaps, duplicate rows, or wrong totals — so they're worth pinning down without a DB.
 */
class OffsetKeyMathTest {

    private val pageSize = 60
    private val initialLoadSize = 120
    private val total = 1000

    @Test fun initialRefreshLoadsFromZero() {
        // key defaults to 0 on the first refresh.
        assertEquals(0, OffsetKeyMath.offset(Kind.REFRESH, 0, initialLoadSize, total))
        assertEquals(initialLoadSize, OffsetKeyMath.limit(Kind.REFRESH, 0, initialLoadSize))
    }

    @Test fun appendUsesKeyAsOffset() {
        assertEquals(120, OffsetKeyMath.offset(Kind.APPEND, 120, pageSize, total))
        assertEquals(pageSize, OffsetKeyMath.limit(Kind.APPEND, 120, pageSize))
    }

    @Test fun prependClipsAtStart() {
        // Prepend with key < loadSize loads [0, key) — no negative offset, clipped limit.
        assertEquals(0, OffsetKeyMath.offset(Kind.PREPEND, 40, pageSize, total))
        assertEquals(40, OffsetKeyMath.limit(Kind.PREPEND, 40, pageSize))
    }

    @Test fun prependFullPage() {
        assertEquals(60, OffsetKeyMath.offset(Kind.PREPEND, 120, pageSize, total))
        assertEquals(pageSize, OffsetKeyMath.limit(Kind.PREPEND, 120, pageSize))
    }

    @Test fun refreshBeyondEndClampsToLastWindow() {
        assertEquals(880, OffsetKeyMath.offset(Kind.REFRESH, 5000, initialLoadSize, total))
    }

    @Test fun prevKeyNullAtTop() {
        assertNull(OffsetKeyMath.prevKey(0))
        assertEquals(60, OffsetKeyMath.prevKey(60))
    }

    @Test fun nextKeyNullAtEnd() {
        // Loaded a full page ending exactly at total → no next.
        assertNull(OffsetKeyMath.nextKey(offset = 940, loaded = 60, limit = 60, itemCount = 1000))
        // Loaded a full page mid-list → next continues.
        assertEquals(180, OffsetKeyMath.nextKey(offset = 120, loaded = 60, limit = 60, itemCount = 1000))
    }

    @Test fun nextKeyNullOnShortPage() {
        assertNull(OffsetKeyMath.nextKey(offset = 120, loaded = 20, limit = 60, itemCount = 1000))
    }

    @Test fun refreshKeyAnchorsWindowAroundPosition() {
        assertEquals(440, OffsetKeyMath.refreshKey(anchorPosition = 500, initialLoadSize = 120))
        assertEquals(0, OffsetKeyMath.refreshKey(anchorPosition = 10, initialLoadSize = 120))
        assertNull(OffsetKeyMath.refreshKey(anchorPosition = null, initialLoadSize = 120))
    }

    @Test fun sequentialAppendPagesHaveNoGap() {
        // Simulate refresh then two appends; assert offsets tile contiguously.
        val o0 = OffsetKeyMath.offset(Kind.REFRESH, 0, initialLoadSize, total)
        val n0 = OffsetKeyMath.nextKey(o0, initialLoadSize, initialLoadSize, total)!!
        assertEquals(120, n0)
        val o1 = OffsetKeyMath.offset(Kind.APPEND, n0, pageSize, total)
        assertEquals(o0 + initialLoadSize, o1) // starts exactly where the first load ended
        val n1 = OffsetKeyMath.nextKey(o1, pageSize, pageSize, total)!!
        val o2 = OffsetKeyMath.offset(Kind.APPEND, n1, pageSize, total)
        assertEquals(o1 + pageSize, o2)
    }
}
