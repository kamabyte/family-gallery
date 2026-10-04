package com.familygallery.tv.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.ui.components.PhotoBrowser
import kotlinx.coroutines.flow.Flow

/**
 * The home timeline: all photos + videos, newest-first, in a focusable grid with a floating
 * month label. As a root tab it does not intercept Back — Back follows native Android TV root
 * behavior. Back or D-pad Left from the first grid column opens the navigation rail.
 *
 * @param autoFocusFirst focus the first cell once (only at initial app launch, not on every
 *   tab reselection); [onAutoFocusConsumed] fires once that has happened.
 */
@Composable
fun TimelineScreen(
    pagingFlow: Flow<PagingData<PhotoEntity>>,
    autoFocusFirst: Boolean,
    onAutoFocusConsumed: () -> Unit,
    onRevealRail: () -> Unit,
    contentFocusRequester: FocusRequester,
    contentFocusRequestToken: Int,
    modifier: Modifier = Modifier,
) {
    val items = pagingFlow.collectAsLazyPagingItems()
    PhotoBrowser(
        items = items,
        modifier = modifier,
        showMonthOverlay = true,
        autoFocusFirst = autoFocusFirst,
        onAutoFocusConsumed = onAutoFocusConsumed,
        onBack = null,
        onRevealRail = onRevealRail,
        contentFocusRequester = contentFocusRequester,
        contentFocusRequestToken = contentFocusRequestToken,
    )
}
