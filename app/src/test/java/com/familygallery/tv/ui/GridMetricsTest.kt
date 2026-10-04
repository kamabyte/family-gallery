package com.familygallery.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The column math is what makes one grid work on a 960dp TV and a 393dp phone, so it is pinned
 * here rather than left to be discovered on a device.
 */
class GridMetricsTest {

    // --- TV: fixed, width-independent ------------------------------------------------

    @Test
    fun `tv photo columns are fixed regardless of width`() {
        // The D-pad row-stepping in PhotoBrowser assumes a constant column count; a TV that
        // reported an unusual width must not change it.
        for (width in listOf(720, 960, 1280, 1920)) {
            assertEquals(
                GridMetrics.TV_PHOTO_COLUMNS,
                GridMetrics.photoColumns(width, FormFactor.Tv),
            )
        }
    }

    @Test
    fun `tv tile columns are fixed regardless of width`() {
        for (width in listOf(720, 960, 1280)) {
            assertEquals(
                GridMetrics.TV_TILE_COLUMNS,
                GridMetrics.tileColumns(width, FormFactor.Tv),
            )
        }
    }

    // --- Phones: derived from width ---------------------------------------------------

    @Test
    fun `common phone portrait widths give three photo columns`() {
        // 360dp: baseline Android phone. 393dp: Xiaomi Mi 8, our oldest target. 412dp: Pixel.
        for (width in listOf(360, 393, 412)) {
            assertEquals(3, GridMetrics.photoColumns(width, FormFactor.Mobile))
        }
    }

    @Test
    fun `phone landscape is wider so it gains columns`() {
        val portrait = GridMetrics.photoColumns(393, FormFactor.Mobile)
        val landscape = GridMetrics.photoColumns(786, FormFactor.Mobile)
        assertTrue("landscape must show more columns than portrait", landscape > portrait)
        assertEquals(6, landscape)
    }

    @Test
    fun `tablet widths give more columns without exceeding the cap`() {
        assertEquals(5, GridMetrics.photoColumns(600, FormFactor.Mobile))
        assertEquals(8, GridMetrics.photoColumns(1600, FormFactor.Mobile))
    }

    @Test
    fun `very narrow screens still get the minimum of three columns`() {
        // A 320dp phone divides to 2; the floor keeps it reading as a gallery, not a list.
        assertEquals(3, GridMetrics.photoColumns(320, FormFactor.Mobile))
        assertEquals(3, GridMetrics.photoColumns(0, FormFactor.Mobile))
    }

    @Test
    fun `photo columns never leave the clamped range for any plausible width`() {
        for (width in 200..2000) {
            val columns = GridMetrics.photoColumns(width, FormFactor.Mobile)
            assertTrue("columns=$columns out of range at width=$width", columns in 3..8)
        }
    }

    @Test
    fun `cover tiles get fewer columns than photos at the same width`() {
        // Tiles carry a title and a count line, so they need to stay wider than a bare thumbnail.
        for (width in listOf(360, 393, 786, 1024)) {
            assertTrue(
                "tiles must not out-number photos at width=$width",
                GridMetrics.tileColumns(width, FormFactor.Mobile) <
                    GridMetrics.photoColumns(width, FormFactor.Mobile),
            )
        }
    }

    @Test
    fun `phone tile columns stay within their clamped range`() {
        assertEquals(2, GridMetrics.tileColumns(393, FormFactor.Mobile))
        assertEquals(4, GridMetrics.tileColumns(786, FormFactor.Mobile))
        assertEquals(2, GridMetrics.tileColumns(100, FormFactor.Mobile))
        assertEquals(5, GridMetrics.tileColumns(2000, FormFactor.Mobile))
    }

    // --- Form factor flags ------------------------------------------------------------

    @Test
    fun `form factor flags are mutually exclusive`() {
        assertTrue(FormFactor.Tv.isTv)
        assertTrue(!FormFactor.Tv.isTouch)
        assertTrue(FormFactor.Mobile.isTouch)
        assertTrue(!FormFactor.Mobile.isTv)
    }
}
