package com.familygallery.tv.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.Saver
import com.familygallery.tv.R
import com.familygallery.tv.ui.GalleryNavState

/**
 * The app's two top-level destinations. Shared by both shells so the TV navigation rail and the
 * phone bottom bar can never drift apart, and so a saved tab index means the same thing on either
 * form factor.
 */
enum class GallerySection(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Timeline(R.string.tab_timeline, R.drawable.ic_nav_photos),
    Albums(R.string.tab_albums, R.drawable.ic_nav_albums),
}

/** Survives Activity recreation + process death so the selected tab is restored. */
val NavStateSaver: Saver<GalleryNavState, Int> = Saver(
    save = { it.selectedTab },
    restore = { GalleryNavState(selectedTab = it) },
)

/**
 * How far off-screen an inactive (kept-alive) section is translated.
 *
 * Both sections stay composed so switching tabs is instant — no recompose, no Paging re-collect,
 * thumbnails already cached, and the Albums drill-down keeps its back-stack. The inactive one
 * keeps its full size but is translated fully out of the window: it stays *placed* (so a
 * focus-driven bringIntoView never crashes, unlike a zero-size layout) yet is invisible and
 * unreachable by D-pad.
 */
internal const val OFFSCREEN_TAB_X_DP = -4000
