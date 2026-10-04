package com.familygallery.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.familygallery.tv.ui.GridMetrics
import com.familygallery.tv.ui.LocalFormFactor

/**
 * A grid/list of static cards with the SAME contract the timeline photo grid uses, on both form
 * factors.
 *
 * On **TV** it implements the focus pattern that makes D-pad navigation reliable on low-end boxes
 * (unlike nested LazyRow carousels, which lose focus after scrolling):
 *
 *  * D-pad **Left** from the first column reveals the navigation rail;
 *  * returning from the rail (**Right**, signalled by an increment of [contentFocusRequestToken])
 *    re-focuses the last focused card — confirmed via [onFocusChanged], never assumed;
 *  * [autoFocus] focuses the last (or first) card once when the grid appears — used when the
 *    user drills into a new level so focus lands in the content, but left off for a screen
 *    that must keep focus on the rail until Right is pressed.
 *
 * On **touch** none of that runs. There is no rail to reveal, no focus to restore and no D-pad to
 * intercept; cards are plain tappable surfaces with a ripple, and the [focused] flag handed to
 * [card] is permanently false, so cards draw their resting state.
 *
 * Intended for SMALL, fully-loaded lists (album categories, album/month tiles — dozens of items),
 * so default in-grid movement is used rather than the paging grid's manual row-stepping.
 *
 * @param columns 1 renders a vertical list of full-width rows; N renders an N-column grid.
 * @param card draws one card; [focused] lets it show its own focus affordance (border/scale).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun <T> FocusableCardGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    columns: Int,
    onRevealRail: () -> Unit,
    contentFocusRequester: FocusRequester,
    contentFocusRequestToken: Int,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
    horizontalSpacing: Dp = 20.dp,
    verticalSpacing: Dp = 20.dp,
    autoFocus: Boolean = true,
    onAutoFocusConsumed: () -> Unit = {},
    card: @Composable (item: T, focused: Boolean) -> Unit,
) {
    val formFactor = LocalFormFactor.current
    val isTv = formFactor.isTv
    val registry = rememberItemFocusRegistry()
    val gridState = rememberLazyGridState()
    var lastFocusedIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var didAutoFocus by rememberSaveable { mutableStateOf(false) }
    var handledToken by rememberSaveable { mutableIntStateOf(0) }

    // Scroll the target into view, request focus, and confirm it is actually owned (a bare
    // requestFocus() returns Unit and can't tell us) — bounded so it never loops forever.
    suspend fun restoreFocus(target: Int): Boolean {
        if (items.isEmpty()) return false
        // A kept-alive tab is composed at zero size until it is shown; requesting focus or
        // scrolling (bringIntoView) before this grid is actually placed throws. Wait for a real
        // viewport first, bounded so it never loops forever.
        var placeWait = 0
        while (gridState.layoutInfo.viewportSize.height == 0 && placeWait < MAX_FOCUS_FRAMES) {
            withFrameNanos { }
            placeWait++
        }
        if (gridState.layoutInfo.viewportSize.height == 0) return false
        val idx = target.coerceIn(0, items.size - 1)
        // Clear any stale owner (it's only set on focus-gain, never on focus-leaving to the
        // breadcrumb) so the loop waits for a REAL confirmation instead of short-circuiting on the
        // previous value — otherwise a first-frame requestFocus that silently fails is never retried.
        focusedIndex = -1
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
            gridState.scrollToItem(idx)
        }
        var attempts = 0
        while (attempts < MAX_FOCUS_FRAMES) {
            if (gridState.layoutInfo.visibleItemsInfo.any { it.index == idx }) {
                runCatching { registry.of(idx.toLong()).requestFocus() }
            }
            withFrameNanos { }
            if (focusedIndex == idx) return true
            attempts++
        }
        return focusedIndex == idx
    }

    // The keyline scroll spec pins the focused card at a fixed fraction of the viewport, which is
    // what makes D-pad movement read as smooth. It is a focus behaviour, so on touch the platform
    // default (free scrolling, no auto-repositioning) is correct instead.
    GridFocusScroll(enabled = isTv) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            contentPadding = contentPadding,
            modifier = modifier
                .fillMaxSize()
                .then(
                    if (!isTv) {
                        Modifier
                    } else {
                        Modifier
                            .focusRestorer { registry.of(lastFocusedIndex.toLong()) }
                            .focusGroup()
                    },
                ),
        ) {
            itemsIndexed(items, key = { _, item -> itemKey(item) }) { index, item ->
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .then(
                            if (!isTv) {
                                Modifier
                            } else {
                                Modifier
                                    .then(
                                        if (index == lastFocusedIndex) {
                                            Modifier.focusRequester(contentFocusRequester)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .focusRequester(registry.of(index.toLong()))
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionLeft &&
                                            index % columns == 0
                                        ) {
                                            onRevealRail()
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        if (it.isFocused) {
                                            lastFocusedIndex = index
                                            focusedIndex = index
                                        }
                                    }
                            },
                        )
                        .clickable(role = Role.Button) { onClick(item) },
                ) {
                    card(item, focused)
                }
            }
        }
    }

    // Focus the last/first card once when the grid appears (only when the caller asks — a
    // freshly drilled-in level asks; the root arriving via a tab switch does not, so focus
    // stays on the rail until Right is pressed).
    LaunchedEffect(autoFocus, items.size, isTv) {
        if (isTv && !didAutoFocus && autoFocus && items.isNotEmpty()) {
            if (restoreFocus(lastFocusedIndex)) {
                didAutoFocus = true
                onAutoFocusConsumed()
            }
        }
    }

    // Right from the rail (or re-entering this level) re-focuses the remembered card. The token
    // stays pending until focus is confirmed, so an early race never leaves the grid focusless.
    LaunchedEffect(contentFocusRequestToken, items.size, isTv) {
        if (isTv && contentFocusRequestToken > handledToken && items.isNotEmpty()) {
            if (restoreFocus(lastFocusedIndex)) {
                handledToken = contentFocusRequestToken
            }
        }
    }
}

/** Applies the TV keyline scroll spec, or nothing at all when [enabled] is false. */
@Composable
private fun GridFocusScroll(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) {
        PositionFocusedItemInLazyLayout(parentFraction = 0.3f, alignFullyVisible = false, content = content)
    } else {
        content()
    }
}

/** Default padding/spacing for a card grid on the current form factor. */
@Composable
fun cardGridPadding(): PaddingValues {
    val formFactor = LocalFormFactor.current
    val horizontal = GridMetrics.screenPadding(formFactor)
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = 8.dp,
        bottom = if (formFactor.isTv) 32.dp else 24.dp,
    )
}

private const val MAX_FOCUS_FRAMES = 30
