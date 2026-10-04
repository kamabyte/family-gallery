package com.familygallery.tv.ui.albums

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.ui.components.PhotoBrowser
import kotlinx.coroutines.flow.Flow

/**
 * Photos inside one album (or one month), as a focusable grid + viewer. A breadcrumb header (path
 * trail + a passive Back hint) sits on top; going up a level is the remote Back key's job, handled
 * by [PhotoBrowser]'s BackHandler. Forwards the rail focus contract to [PhotoBrowser] so D-pad Left
 * from the first column reveals the rail and Right restores focus onto the last viewed cell.
 *
 * @param showMonthOverlay floats the current month label over the grid (same as the timeline).
 *   Enabled for albums that span multiple months — places, cameras, videos — so a long scroll
 *   still reads as dated; left off for a single-month view, where it would just echo the crumb.
 */
@Composable
fun AlbumDetailScreen(
    crumbs: List<String>,
    pagingFlow: Flow<PagingData<PhotoEntity>>,
    onBack: () -> Unit,
    backEnabled: Boolean,
    onRevealRail: () -> Unit,
    contentFocusRequester: FocusRequester,
    contentFocusRequestToken: Int,
    modifier: Modifier = Modifier,
    showMonthOverlay: Boolean = false,
) {
    val items = pagingFlow.collectAsLazyPagingItems()

    Column(modifier.fillMaxSize()) {
        // On touch the crumb's back arrow becomes a real tap target for the same [onBack] the
        // system Back button triggers; on TV it stays a passive hint (see AlbumsBreadcrumb).
        AlbumsBreadcrumb(crumbs = crumbs, onBack = onBack)
        PhotoBrowser(
            items = items,
            showMonthOverlay = showMonthOverlay,
            autoFocusFirst = true,
            onBack = onBack,
            backEnabled = backEnabled,
            onRevealRail = onRevealRail,
            contentFocusRequester = contentFocusRequester,
            contentFocusRequestToken = contentFocusRequestToken,
        )
    }
}
