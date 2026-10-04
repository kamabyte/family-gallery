package com.familygallery.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familygallery.tv.ui.GalleryNav
import com.familygallery.tv.ui.GalleryNavState
import com.familygallery.tv.ui.MainViewModel
import com.familygallery.tv.ui.albums.AlbumsScreen
import com.familygallery.tv.ui.components.GalleryNavigationItem
import com.familygallery.tv.ui.components.GalleryNavigationRail
import com.familygallery.tv.ui.timeline.TimelineScreen

/**
 * The 10-foot shell: a hidden navigation rail that D-pad Left / Back reveals over the content.
 *
 * Every piece of the focus choreography here exists because a remote has no pointer — focus *is*
 * the cursor, so it must never be lost, and it must land somewhere predictable after every
 * transition. See [MobileShell] for the touch equivalent, which needs none of it.
 */
@Composable
internal fun TvShell(viewModel: MainViewModel) {
    val sections = GallerySection.entries
    val navigationItems = remember {
        sections.map { GalleryNavigationItem(it.labelRes, it.iconRes) }
    }
    var nav by rememberSaveable(stateSaver = NavStateSaver) { mutableStateOf(GalleryNavState()) }
    // Content takes focus once at cold start. Tab changes are click-driven and leave focus on the
    // rail until Right is pressed, which makes browsing the menu deterministic.
    var requestInitialContentFocus by rememberSaveable { mutableStateOf(true) }
    val albumSections by viewModel.albumSections.collectAsStateWithLifecycle()
    val smartAlbums by viewModel.smartAlbums.collectAsStateWithLifecycle()
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()

    // Requesters are permanent per destination. Reattaching one requester to another tab during
    // recomposition loses focus on Android 9, so each rail item and each screen owns its own.
    val navigationFocusRequesters = remember { sections.map { FocusRequester() } }
    val contentFocusRequesters = remember { sections.map { FocusRequester() } }
    // A monotonically increasing request survives the rail's immediate recomposition. Each
    // destination confirms focus after its lazy content is attached, rather than relying on a
    // single requestFocus() that can race Paging after a tab switch.
    val contentFocusRequestTokens = remember { mutableStateListOf(0, 0) }
    var navigationHasFocus by remember { mutableStateOf(false) }
    // The rail is only *shown* when it was focused deliberately (Back / D-pad Left), never when
    // focus merely lands on it transiently while a screen swaps content — that transient is what
    // made the rail flash open on every album navigation.
    var railFocusIntended by remember { mutableStateOf(false) }

    // Deliberately move focus onto the rail (and mark it, so it becomes visible).
    fun revealRail() {
        railFocusIntended = true
        runCatching { navigationFocusRequesters[nav.selectedTab].requestFocus() }
    }

    // Move focus into a tab's content (which hides the rail). Clearing the intent flag hides the
    // rail immediately; the token retries after layout if the request races composition.
    fun enterContent(index: Int) {
        railFocusIntended = false
        runCatching { contentFocusRequesters[index].requestFocus() }
        contentFocusRequestTokens[index] = contentFocusRequestTokens[index] + 1
    }

    // TV convention: Back from a root screen reveals the app navigation. A second Back while the
    // rail owns focus is not intercepted and exits normally. Album-level Back is handled inside
    // the Albums feature.
    BackHandler(enabled = !navigationHasFocus) { revealRail() }

    Box(Modifier.fillMaxSize()) {
        SectionHost(selectedIndex = nav.selectedTab) { section, active ->
            when (section) {
                GallerySection.Timeline -> TimelineScreen(
                    pagingFlow = viewModel.timelinePhotos,
                    autoFocusFirst = requestInitialContentFocus && active,
                    onAutoFocusConsumed = { requestInitialContentFocus = false },
                    onRevealRail = { revealRail() },
                    contentFocusRequester =
                        contentFocusRequesters[GallerySection.Timeline.ordinal],
                    contentFocusRequestToken =
                        contentFocusRequestTokens[GallerySection.Timeline.ordinal],
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
                    onRevealRail = { revealRail() },
                    contentFocusRequester =
                        contentFocusRequesters[GallerySection.Albums.ordinal],
                    contentFocusRequestToken =
                        contentFocusRequestTokens[GallerySection.Albums.ordinal],
                    // Only the visible section may consume Back.
                    backEnabled = active && !navigationHasFocus,
                    autoFocusFirst = requestInitialContentFocus && active,
                    onAutoFocusConsumed = { requestInitialContentFocus = false },
                )
            }
        }

        // No Back interception while the rail owns focus: a second Back exits to the Android
        // home screen (system default) rather than cycling focus back into content. This keeps
        // Back a reliable way out of the app instead of ping-ponging rail ↔ content forever.

        GalleryNavigationRail(
            items = navigationItems,
            selectedIndex = nav.selectedTab,
            expanded = navigationHasFocus && railFocusIntended,
            itemFocusRequesters = navigationFocusRequesters,
            onEnterContent = { enterContent(nav.selectedTab) },
            onFocusChanged = {
                navigationHasFocus = it
                // A transient focus loss during a content swap clears the intent; a deliberate
                // reveal sets it again just before requesting focus.
                if (!it) railFocusIntended = false
            },
            // OK/click switches the tab and enters its content immediately — kept cheap by the
            // keep-alive above, so the rail's close animation stays smooth.
            onSelect = { index ->
                nav = GalleryNav.selectTab(nav, index)
                enterContent(index)
            },
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}
