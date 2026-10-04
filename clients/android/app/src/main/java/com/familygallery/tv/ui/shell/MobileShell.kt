package com.familygallery.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familygallery.tv.ui.GalleryNav
import com.familygallery.tv.ui.GalleryNavState
import com.familygallery.tv.ui.MainViewModel
import com.familygallery.tv.ui.albums.AlbumsScreen
import com.familygallery.tv.ui.timeline.TimelineScreen

/**
 * Below this window height the bottom bar is replaced by a side rail. A phone in landscape is
 * about 393dp tall; Material's 80dp bottom bar would eat a fifth of that and leave room for only
 * two rows of photos, so the navigation moves to the side where the screen has width to spare.
 */
private const val COMPACT_HEIGHT_DP = 480

/**
 * The touch shell: bottom navigation (or a side rail in landscape), edge-to-edge content, and
 * pull-to-refresh.
 *
 * Deliberately *not* the TV shell with bigger touch targets. Three things are structurally
 * different:
 *
 *  * **Navigation is always visible.** The TV rail hides itself because a remote can summon it
 *    with a Left press and screen space is cheap at 10 feet. A phone has neither, so the two
 *    destinations stay on screen within thumb reach.
 *  * **Back means "up", not "show the menu".** On TV, Back at a root reveals the rail. Here it
 *    walks the hierarchy: out of an album, then to the Timeline tab, then out of the app.
 *  * **No focus choreography at all.** No focus requesters, no restore tokens, no auto-focus —
 *    the shared screens read `LocalFormFactor` and skip theirs too. The requesters below exist
 *    only to satisfy the shared screens' signatures; nothing ever fires them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileShell(viewModel: MainViewModel) {
    var nav by rememberSaveable(stateSaver = NavStateSaver) { mutableStateOf(GalleryNavState()) }
    // Incremented when the Albums tab is re-selected, which pops its drill-down back to the root
    // — the standard phone behaviour for tapping the tab you are already on.
    var albumsResetToken by rememberSaveable { mutableIntStateOf(0) }

    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val compactHeight = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP

    // Back from the Albums tab root returns to the Timeline rather than leaving the app; a
    // deeper Albums level is popped first by AlbumsScreen's own (nested, higher-priority)
    // handler. From the Timeline root nothing is intercepted, so Back exits as users expect.
    BackHandler(enabled = nav.selectedTab != GallerySection.Timeline.ordinal) {
        nav = GalleryNav.selectTab(nav, GallerySection.Timeline.ordinal)
    }

    val onSelect: (Int) -> Unit = { index ->
        if (index == nav.selectedTab) {
            if (index == GallerySection.Albums.ordinal) albumsResetToken++
        } else {
            nav = GalleryNav.selectTab(nav, index)
        }
    }

    if (compactHeight) {
        Row(Modifier.fillMaxSize()) {
            GalleryNavRail(selectedIndex = nav.selectedTab, onSelect = onSelect)
            Sections(
                viewModel = viewModel,
                selectedTab = nav.selectedTab,
                albumsResetToken = albumsResetToken,
                refreshing = refreshing,
                // The rail already consumed the start edge and the vertical bars; the content
                // still has to clear the status bar, the gesture area and a landscape cutout.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Bottom +
                                WindowInsetsSides.End,
                        ),
                    ),
            )
        }
    } else {
        Scaffold(
            // The gallery's gradient is painted once behind the whole window (see
            // galleryBackground); an opaque Scaffold would cover it.
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            // safeDrawing rather than systemBars so a display cutout is respected too.
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = { GalleryBottomBar(nav.selectedTab, onSelect) },
        ) { innerPadding ->
            Sections(
                viewModel = viewModel,
                selectedTab = nav.selectedTab,
                albumsResetToken = albumsResetToken,
                refreshing = refreshing,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sections(
    viewModel: MainViewModel,
    selectedTab: Int,
    albumsResetToken: Int,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val albumSections by viewModel.albumSections.collectAsStateWithLifecycle()
    val smartAlbums by viewModel.smartAlbums.collectAsStateWithLifecycle()
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()
    // Inert placeholders for the shared screens' TV focus contract. Never requested on touch.
    val unusedFocusRequesters = remember { GallerySection.entries.map { FocusRequester() } }

    // Pulling down from the top of any grid re-runs the catalog sync. It wraps the whole content
    // area rather than just the timeline so the gesture works wherever the user happens to be;
    // the off-screen inactive section cannot receive touches, so only the visible grid drives it.
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    ) {
        SectionHost(selectedIndex = selectedTab) { section, active ->
            when (section) {
                GallerySection.Timeline -> TimelineScreen(
                    pagingFlow = viewModel.timelinePhotos,
                    autoFocusFirst = false,
                    onAutoFocusConsumed = {},
                    onRevealRail = {},
                    contentFocusRequester = unusedFocusRequesters[0],
                    contentFocusRequestToken = 0,
                )
                GallerySection.Albums -> AlbumsScreen(
                    sections = albumSections,
                    smartAlbums = smartAlbums,
                    seasons = seasons,
                    seasonPhotos = viewModel::seasonPhotos,
                    monthsProvider = viewModel::monthsInYear,
                    albumPhotos = viewModel::albumPhotos,
                    monthPhotos = viewModel::monthPhotos,
                    videoPhotos = viewModel::videoPhotos,
                    onThisDayPhotos = viewModel::onThisDayPhotos,
                    onRevealRail = {},
                    contentFocusRequester = unusedFocusRequesters[1],
                    contentFocusRequestToken = 0,
                    // Only the visible section may consume Back — otherwise the hidden Albums
                    // stack would swallow Back presses meant for the Timeline.
                    backEnabled = active,
                    resetToRootToken = albumsResetToken,
                )
            }
        }
    }
}

/** Matched to the TV rail's surface so the two shells read as the same app. */
private val NavSurface = Color(0xFF101620)
private val NavIndicator = Color(0xFF9B92FF)
private val NavSelectedIcon = Color(0xFF171522)
private val NavSelectedText = Color(0xFFE3E0FF)
private val NavUnselected = Color.White.copy(alpha = 0.66f)

@Composable
private fun GalleryBottomBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = NavSurface, contentColor = Color.White) {
        GallerySection.entries.forEachIndexed { index, section ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                icon = { NavIcon(section) },
                label = { Text(stringResource(section.labelRes)) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NavSelectedIcon,
                    selectedTextColor = NavSelectedText,
                    indicatorColor = NavIndicator,
                    unselectedIconColor = NavUnselected,
                    unselectedTextColor = NavUnselected,
                ),
            )
        }
    }
}

/** Landscape navigation: costs ~80dp of width, which a landscape phone has, instead of 80dp of
 *  height, which it does not. */
@Composable
private fun GalleryNavRail(selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationRail(
        containerColor = NavSurface,
        contentColor = Color.White,
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Vertical + WindowInsetsSides.Start,
        ),
    ) {
        GallerySection.entries.forEachIndexed { index, section ->
            NavigationRailItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                icon = { NavIcon(section) },
                label = { Text(stringResource(section.labelRes)) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = NavSelectedIcon,
                    selectedTextColor = NavSelectedText,
                    indicatorColor = NavIndicator,
                    unselectedIconColor = NavUnselected,
                    unselectedTextColor = NavUnselected,
                ),
            )
        }
    }
}

@Composable
private fun NavIcon(section: GallerySection) {
    Icon(
        painter = painterResource(section.iconRes),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
    )
}
