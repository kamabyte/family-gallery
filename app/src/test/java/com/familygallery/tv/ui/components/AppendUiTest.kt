package com.familygallery.tv.ui.components

import androidx.paging.LoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reachability rule behind finding 6: an append error must NOT leave the Retry thousands
 * of placeholders away. When append has errored the placeholders go non-focusable so D-pad
 * Down reaches the (now shown) error banner; otherwise they stay focusable to page in content.
 */
class AppendUiTest {

    private val loading = LoadState.Loading
    private val error = LoadState.Error(RuntimeException("boom"))
    private val idle = LoadState.NotLoading(endOfPaginationReached = false)
    private val end = LoadState.NotLoading(endOfPaginationReached = true)

    @Test fun errorShowsErrorBanner() {
        assertEquals(AppendUi.ERROR, appendUiFor(error))
    }

    @Test fun loadingShowsLoadingChip() {
        assertEquals(AppendUi.LOADING, appendUiFor(loading))
    }

    @Test fun idleAndEndShowNothing() {
        assertEquals(AppendUi.NONE, appendUiFor(idle))
        assertEquals(AppendUi.NONE, appendUiFor(end))
    }

    @Test fun placeholdersNonFocusableOnlyWhenAppendErrored() {
        assertFalse(placeholdersFocusableFor(error))
        assertTrue(placeholdersFocusableFor(loading))
        assertTrue(placeholdersFocusableFor(idle))
        assertTrue(placeholdersFocusableFor(end))
    }
}

/**
 * The append-error focus handoff (finding 4): move focus INTO Retry when the failed boundary
 * placeholder held it, and OUT to a loaded cell before Retry disappears — never disturbing
 * focus that's elsewhere.
 */
class AppendFocusTest {

    @Test fun enteringErrorFromPlaceholderMovesToRetry() {
        val t = AppendFocus.onPhaseChange(
            prev = AppendUi.LOADING, next = AppendUi.ERROR,
            owner = BrowserFocusOwner.Placeholder,
        )
        assertEquals(AppendFocusTarget.ToRetry, t)
    }

    @Test fun enteringErrorWithoutPlaceholderFocusDoesNothing() {
        // Focus was on a loaded cell (not the dead boundary) → don't yank it to Retry.
        val t = AppendFocus.onPhaseChange(
            prev = AppendUi.LOADING, next = AppendUi.ERROR,
            owner = BrowserFocusOwner.Grid(42),
        )
        assertEquals(AppendFocusTarget.None, t)
    }

    @Test fun pressingRetryWithRetryFocusReturnsToLastLoadedCell() {
        val t = AppendFocus.onRetryPressed(BrowserFocusOwner.Retry, lastFocusedIndex = 42)
        assertEquals(AppendFocusTarget.ToGrid(42), t)
    }

    @Test fun pressingRetryUsesCurrentLastLoadedCell() {
        val t = AppendFocus.onRetryPressed(BrowserFocusOwner.Retry, lastFocusedIndex = 7)
        assertEquals(AppendFocusTarget.ToGrid(7), t)
    }

    @Test fun pressingRetryWithoutRetryFocusDoesNotSteal() {
        val t = AppendFocus.onRetryPressed(BrowserFocusOwner.Grid(3), lastFocusedIndex = 42)
        assertEquals(AppendFocusTarget.None, t)
    }

    @Test fun errorAfterFocusLeftBrowserDoesNotSteal() {
        val t = AppendFocus.onPhaseChange(
            prev = AppendUi.LOADING, next = AppendUi.ERROR,
            owner = BrowserFocusOwner.None,
        )
        assertEquals(AppendFocusTarget.None, t)
    }

    @Test fun leavingErrorDoesNotRequestAfterRetryNodeRemoval() {
        val t = AppendFocus.onPhaseChange(
            prev = AppendUi.ERROR, next = AppendUi.LOADING,
            owner = BrowserFocusOwner.Retry,
        )
        assertEquals(AppendFocusTarget.None, t)
    }

    @Test fun noTransitionNoAction() {
        val t = AppendFocus.onPhaseChange(
            prev = AppendUi.NONE, next = AppendUi.LOADING,
            owner = BrowserFocusOwner.Placeholder,
        )
        assertEquals(AppendFocusTarget.None, t)
    }
}
