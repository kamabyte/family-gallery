package com.familygallery.tv.ui

/**
 * Pure navigation state for the top-level shell. Album drill-down now lives inside the Albums
 * feature (its own back-stack), so the shell only tracks which root tab is selected — kept as a
 * tiny state machine so tab selection stays trivially testable.
 */
data class GalleryNavState(
    val selectedTab: Int = 0,
)

object GalleryNav {
    fun selectTab(state: GalleryNavState, index: Int): GalleryNavState =
        if (index == state.selectedTab) state else state.copy(selectedTab = index)
}
