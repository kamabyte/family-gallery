package com.familygallery.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GalleryNavStateTest {

    @Test fun selectTabChangesSelection() {
        val s = GalleryNav.selectTab(GalleryNavState(), 1)
        assertEquals(1, s.selectedTab)
    }

    @Test fun selectSameTabReturnsIdenticalState() {
        val base = GalleryNavState(selectedTab = 1)
        // No allocation / no change when re-selecting the current tab.
        assertSame(base, GalleryNav.selectTab(base, 1))
    }
}
