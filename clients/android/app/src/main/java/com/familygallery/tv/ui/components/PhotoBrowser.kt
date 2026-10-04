package com.familygallery.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.familygallery.tv.R
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.share.ShareSelection
import com.familygallery.tv.ui.DateFormats
import com.familygallery.tv.ui.GridMetrics
import com.familygallery.tv.ui.LocalFormFactor
import com.familygallery.tv.ui.rememberPhotoColumns
import com.familygallery.tv.ui.share.ShareStatus
import com.familygallery.tv.ui.share.rememberShareController
import com.familygallery.tv.ui.viewer.PhotoViewer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Which append (load-more) affordance to show near the viewport. */
internal enum class AppendUi { NONE, LOADING, ERROR }

internal fun appendUiFor(append: LoadState): AppendUi = when (append) {
    is LoadState.Loading -> AppendUi.LOADING
    is LoadState.Error -> AppendUi.ERROR
    else -> AppendUi.NONE
}

/**
 * Placeholder cells are focusable so D-pad traversal can page in more content — EXCEPT while
 * appending has errored, when they can't fill; making them non-focusable then lets D-pad Down
 * fall through to the reachable Retry banner instead of dead-ending on a placeholder.
 */
internal fun placeholdersFocusableFor(append: LoadState): Boolean = append !is LoadState.Error

/** Where the append-error handoff should move focus on an append-phase transition. */
internal sealed interface AppendFocusTarget {
    data object ToRetry : AppendFocusTarget
    data class ToGrid(val index: Int) : AppendFocusTarget
    data object None : AppendFocusTarget
}

/** Actual focus owner observed from Compose focus callbacks. */
internal sealed interface BrowserFocusOwner {
    data class Grid(val index: Int) : BrowserFocusOwner
    data object Placeholder : BrowserFocusOwner
    data object Retry : BrowserFocusOwner
    data object None : BrowserFocusOwner
}

/**
 * Pure decision for the Retry focus handoff (finding 4), so the transitions are deterministic
 * and unit-testable:
 *  - entering ERROR while a placeholder owns focus → [ToRetry];
 *  - pressing Retry while Retry owns focus → [ToGrid] before loading is started;
 *  - otherwise [None] — focus elsewhere is never disturbed.
 */
internal object AppendFocus {
    fun onPhaseChange(
        prev: AppendUi,
        next: AppendUi,
        owner: BrowserFocusOwner,
    ): AppendFocusTarget = when {
        next == AppendUi.ERROR && prev != AppendUi.ERROR && owner == BrowserFocusOwner.Placeholder ->
            AppendFocusTarget.ToRetry
        else -> AppendFocusTarget.None
    }

    fun onRetryPressed(owner: BrowserFocusOwner, lastFocusedIndex: Int): AppendFocusTarget =
        if (owner == BrowserFocusOwner.Retry) AppendFocusTarget.ToGrid(lastFocusedIndex)
        else AppendFocusTarget.None
}

/**
 * A photo grid plus the fullscreen viewer, over one shared paging list. The viewer reads the same
 * loaded [items] as the grid, so opening at the selected photo is instant, and on close we scroll
 * back to the exact cell that was being viewed.
 *
 * The grid serves both form factors from one component, but the *interaction* differs sharply and
 * is switched on `LocalFormFactor`:
 *
 *  * **TV** — every cell is a focus target. D-pad Left from column zero reveals the rail, Up/Down
 *    step exactly one row (bypassing Compose's expensive beyond-bounds spatial search), focus is
 *    restored onto the remembered cell when returning from the rail or the viewer, and the
 *    append-error banner participates in a deliberate focus handoff.
 *  * **Touch** — none of the above runs. There is no focus on a phone, so requesting it would
 *    steal it from nothing, force spurious scrolls, and paint focus rings the user never asked
 *    for. Cells are plain clickable tiles and the grid scrolls with a finger.
 *
 * @param showMonthOverlay floats the current month label over the grid (timeline only).
 * @param autoFocusFirst focus the first cell once when the grid appears; [onAutoFocusConsumed]
 *   fires once that happens (so callers can avoid re-stealing focus on tab reselection). Ignored
 *   on touch.
 * @param onBack invoked when Back is pressed while this grid is showing (used by album detail to
 *   return to Albums). If null, Back is not intercepted and follows native root behavior.
 */
@Composable
fun PhotoBrowser(
    items: LazyPagingItems<PhotoEntity>,
    modifier: Modifier = Modifier,
    showMonthOverlay: Boolean = false,
    autoFocusFirst: Boolean = false,
    onAutoFocusConsumed: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    onRevealRail: (() -> Unit)? = null,
    contentFocusRequester: FocusRequester? = null,
    contentFocusRequestToken: Int = 0,
) {
    // Detail is a real back-stack destination. Back must close it even while the grid is empty,
    // loading, or temporarily has no focused child. [backEnabled] is false when this browser is
    // kept composed in an inactive tab, so its Back doesn't fire from the other section. The
    // viewer's later BackHandler takes precedence while its dialog is open.
    if (onBack != null) BackHandler(enabled = backEnabled, onBack = onBack)

    val refresh = items.loadState.refresh
    when {
        refresh is LoadState.Loading && items.itemCount == 0 ->
            CenterMessage(stringResource(R.string.loading_photos))

        // Only take over the whole screen with an error when there's nothing to show. If we
        // already have (e.g. cached) content, a recoverable refresh error must not hide it.
        refresh is LoadState.Error && items.itemCount == 0 ->
            CenterMessage(stringResource(R.string.load_photos_error), onRetry = { items.retry() })

        refresh is LoadState.NotLoading && items.itemCount == 0 ->
            CenterMessage(
                stringResource(R.string.no_photos_title),
                stringResource(R.string.no_photos_subtitle),
            )

        else -> BrowserContent(
            items, modifier, showMonthOverlay, autoFocusFirst, onAutoFocusConsumed,
            onRevealRail, contentFocusRequester, contentFocusRequestToken, backEnabled)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun BrowserContent(
    items: LazyPagingItems<PhotoEntity>,
    modifier: Modifier,
    showMonthOverlay: Boolean,
    autoFocusFirst: Boolean,
    onAutoFocusConsumed: () -> Unit,
    onRevealRail: (() -> Unit)?,
    contentFocusRequester: FocusRequester?,
    contentFocusRequestToken: Int,
    backEnabled: Boolean,
) {
    val formFactor = LocalFormFactor.current
    val isTv = formFactor.isTv
    // Fixed at six on TV (the row-stepping below reasons in whole rows and must not have the
    // count change under it); derived from the window width on phones, so portrait and landscape
    // both give sensibly sized thumbnails.
    val columns = rememberPhotoColumns()

    // rememberLazyGridState is itself saveable, so scroll position survives recreation.
    val gridState = rememberLazyGridState()
    val registry = rememberItemFocusRegistry()
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingFocusIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var didInitialFocus by rememberSaveable { mutableStateOf(false) }
    var handledContentFocusToken by rememberSaveable { mutableIntStateOf(0) }
    val focusScope = rememberCoroutineScope()
    val focusMoveMutex = remember { Mutex() }

    // When appending more pages has failed, the placeholder cells below the loaded window
    // can't fill, so we make them non-focusable — D-pad Down then exits the grid straight to
    // the reachable Retry overlay instead of getting stuck on a dead placeholder. On touch
    // placeholders are never focusable to begin with.
    val appendState = items.loadState.append
    val placeholdersFocusable = isTv && placeholdersFocusableFor(appendState)

    // Focus bookkeeping for the append-error handoff (finding 4): the last loaded cell that
    // owned focus, whether a (dead-on-error) placeholder currently owns it, and whether the
    // Retry banner owns it — plus a dedicated requester for Retry.
    var lastFocusedIndex by rememberSaveable { mutableIntStateOf(0) }
    // The menu's Down requester is attached directly to this child, never to the non-focusable
    // focusGroup container. Update only when focus leaves the grid so normal cell-to-cell moves
    // do not recompose every visible tile.
    var entryFocusIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusOwner by remember { mutableStateOf<BrowserFocusOwner>(BrowserFocusOwner.None) }
    var retryHandoffInProgress by remember { mutableStateOf(false) }
    val retryFocus = remember { FocusRequester() }
    val appendPhase = appendUiFor(appendState)
    var prevAppendPhase by remember { mutableStateOf(appendPhase) }

    // --- Share multi-select (touch only) --------------------------------------------------
    // Held as id -> row rather than a set of indices: Paging evicts pages the user scrolls past,
    // and a selection must stay *resolvable* long after its page is gone. A plain `remember` is
    // enough — MainActivity handles the rotation config change, so composition survives it, and a
    // selection is not worth restoring across process death.
    val share = rememberShareController()
    var selection by remember { mutableStateOf<Map<Long, PhotoEntity>>(emptyMap()) }
    var limitNotice by remember { mutableStateOf(false) }
    val selectionActive = formFactor.isTouch && selection.isNotEmpty()
    val shareChooserTitle = stringResource(R.string.share_chooser_title)

    fun toggleSelection(photo: PhotoEntity) {
        when (val result = ShareSelection.toggle(selection, photo)) {
            is ShareSelection.Toggle.Changed -> {
                selection = result.selection
                limitNotice = false
            }
            ShareSelection.Toggle.LimitReached -> limitNotice = true
        }
    }

    LaunchedEffect(limitNotice) {
        if (limitNotice) {
            delay(LIMIT_NOTICE_MS)
            limitNotice = false
        }
    }

    // Back leaves selection mode before it does anything else. Registered here rather than beside
    // PhotoBrowser's [onBack] handler so it is added later and therefore wins — leaving a
    // selection must never also pop the album. Gated on [backEnabled] so a selection left behind
    // in the hidden Albums tab cannot swallow the visible tab's Back.
    BackHandler(enabled = selectionActive && backEnabled) { selection = emptyMap() }

    /** Scroll, request, and confirm focus on a real loaded grid cell. */
    suspend fun restoreGridFocus(requestedIndex: Int): Boolean {
        if (items.itemCount <= 0) return false
        // A kept-alive tab is composed at zero size until shown; requesting focus / bringIntoView
        // before this grid is placed throws. Wait for a real viewport first (bounded).
        var placeWait = 0
        while (gridState.layoutInfo.viewportSize.height == 0 && placeWait < FOCUS_RESTORE_MAX_FRAMES) {
            withFrameNanos { }
            placeWait++
        }
        if (gridState.layoutInfo.viewportSize.height == 0) return false
        val index = requestedIndex.coerceIn(0, items.itemCount - 1)
        // Paging may have evicted the viewed item's page while the viewer was open. Accessing
        // by index requests it; snapshotFlow then waits for the real row rather than giving up
        // after a few display frames while SQLite is still loading.
        if (items.peek(index) == null) {
            items[index]
            val loaded = withTimeoutOrNull(FOCUS_LOAD_TIMEOUT_MS) {
                androidx.compose.runtime.snapshotFlow { items.peek(index) }
                    .filterNotNull()
                    .first()
            }
            if (loaded == null) return false
        }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            gridState.scrollToItem(index)
        }
        // On touch there is nothing to focus: bringing the cell into view IS the restore.
        if (!isTv) return true
        var attempts = 0
        while (attempts < FOCUS_RESTORE_MAX_FRAMES) {
            val laidOut = gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (laidOut && items.peek(index) != null) {
                runCatching { registry.of(index.toLong()).requestFocus() }
            }
            withFrameNanos { }
            if (focusOwner == BrowserFocusOwner.Grid(index)) return true
            attempts++
        }
        return focusOwner == BrowserFocusOwner.Grid(index)
    }

    /**
     * Avoid LazyVerticalGrid's expensive beyond-bounds focus search. Keep the current tile
     * focused while one row scrolls into view, then request the exact same-column target.
     */
    fun moveVerticalFocus(currentIndex: Int, targetIndex: Int): Boolean {
        if (targetIndex !in 0 until items.itemCount) return true
        focusScope.launch {
            focusMoveMutex.withLock {
                if (items.peek(targetIndex) == null) {
                    items[targetIndex]
                    withTimeoutOrNull(FOCUS_LOAD_TIMEOUT_MS) {
                        androidx.compose.runtime.snapshotFlow { items.peek(targetIndex) }
                            .filterNotNull()
                            .first()
                    } ?: return@withLock
                }
                gridState.requestFocusInGrid(
                    currentIndex = currentIndex,
                    targetIndex = targetIndex,
                    columns = columns,
                    targetFocusRequester = registry.of(targetIndex.toLong()),
                )
            }
        }
        return true
    }

    Box(
        modifier
            .fillMaxSize()
            .then(
                if (!isTv) {
                    Modifier
                } else {
                    Modifier
                        .onFocusChanged {
                            // A normal exit to tabs/viewer clears stale ownership. During the ERROR
                            // recomposition, however, a focused placeholder is detached before Retry
                            // can be focused; retain that owner just long enough for the handoff
                            // effect below.
                            if (!it.hasFocus && appendPhase != AppendUi.ERROR) {
                                entryFocusIndex = lastFocusedIndex
                                focusOwner = BrowserFocusOwner.None
                            }
                        }
                        // When focus returns from the navigation rail, prefer the exact last cell
                        // instead of the spatial search default (usually column zero). The saved
                        // scroll state keeps that cell composed, and focusRestorer falls back
                        // safely if it is not.
                        .focusRestorer { registry.of(lastFocusedIndex.toLong()) }
                        .focusGroup()
                },
            ),
    ) {
        LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(GridMetrics.spacing(formFactor)),
                verticalArrangement = Arrangement.spacedBy(GridMetrics.spacing(formFactor)),
                // Reserve room for the floating month label so it does not sit on the first
                // thumbnail while the grid is at rest.
                contentPadding = GridMetrics.contentPadding(
                    formFactor,
                    reserveMonthOverlay = showMonthOverlay,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Key by absolute index (stable for the static catalog): a placeholder and the
                // real cell that later replaces it share a key, so Compose keeps the slot and
                // focus is not dropped when the photo pages in.
                items(
                    count = items.itemCount,
                    key = { index -> index },
                    contentType = { "photo" },
                ) { index ->
                    val photo = items[index]
                    if (photo != null) {
                        PhotoGridCell(
                            photo = photo,
                            // While picking, a tap toggles rather than opens: the viewer would
                            // cover the selection the user is still building.
                            onClick = {
                                if (selectionActive) toggleSelection(photo)
                                else viewerIndex = index
                            },
                            onLongClick = if (formFactor.isTouch) {
                                { toggleSelection(photo) }
                            } else {
                                null
                            },
                            selected = if (selectionActive) photo.id in selection else null,
                            modifier = if (!isTv) {
                                Modifier
                            } else {
                                Modifier
                                    .then(
                                        if (index == entryFocusIndex &&
                                            contentFocusRequester != null
                                        ) {
                                            Modifier.focusRequester(contentFocusRequester)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .focusRequester(registry.of(index.toLong()))
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) {
                                            false
                                        } else {
                                            when (event.key) {
                                                Key.DirectionLeft -> {
                                                    if (index % columns != 0 ||
                                                        onRevealRail == null
                                                    ) {
                                                        false
                                                    } else {
                                                        onRevealRail()
                                                        true
                                                    }
                                                }
                                                Key.DirectionUp -> {
                                                    val target = index - columns
                                                    // Top row: consume so Up is a no-op. The
                                                    // breadcrumb header above is a passive hint
                                                    // (not focusable), so there is nothing to
                                                    // move focus onto.
                                                    if (target < 0) true
                                                    else moveVerticalFocus(index, target)
                                                }
                                                Key.DirectionDown ->
                                                    moveVerticalFocus(index, index + columns)
                                                else -> false
                                            }
                                        }
                                    }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            lastFocusedIndex = index
                                            focusOwner = BrowserFocusOwner.Grid(index)
                                        }
                                    }
                            },
                        )
                    } else {
                        // Placeholder for a not-yet-loaded row. Focusable while more can load
                        // (so D-pad traversal triggers the next page), non-focusable once
                        // appending has errored (so Down reaches the Retry overlay) and always
                        // non-focusable on touch, where scrolling pages in the next window.
                        PhotoGridPlaceholder(
                            focusable = placeholdersFocusable,
                            onFocused = { focusOwner = BrowserFocusOwner.Placeholder },
                        )
                    }
                }
            }

        PrefetchPhotoThumbnails(items, gridState, columns)

        // The selection bar occupies the same corner, and the month is the less urgent of the two.
        if (showMonthOverlay && !selectionActive) {
            MonthOverlay(items, gridState, Modifier.align(Alignment.TopStart))
        }

        // Non-blocking append (load-more) status near the viewport, kept reachable.
        when (appendPhase) {
            AppendUi.LOADING -> AppendLoadingChip(Modifier.align(Alignment.BottomCenter))
            AppendUi.ERROR -> AppendErrorBanner(
                onRetry = {
                    if (!retryHandoffInProgress) {
                        // On touch, retry immediately: there is no focus to hand off, and the
                        // deliberate TV handoff would leave the tap doing nothing.
                        if (!isTv) {
                            items.retry()
                        } else {
                            when (val target =
                                AppendFocus.onRetryPressed(focusOwner, lastFocusedIndex)) {
                                is AppendFocusTarget.ToGrid -> {
                                    retryHandoffInProgress = true
                                    focusScope.launch {
                                        val focused = restoreGridFocus(target.index)
                                        if (focused) {
                                            // Retry only after focus is safely back on a node that
                                            // remains composed when the ERROR banner disappears.
                                            items.retry()
                                        } else {
                                            runCatching { retryFocus.requestFocus() }
                                        }
                                        retryHandoffInProgress = false
                                    }
                                }
                                AppendFocusTarget.None, AppendFocusTarget.ToRetry -> Unit
                            }
                        }
                    }
                },
                focusRequester = retryFocus,
                onFocused = { focusOwner = BrowserFocusOwner.Retry },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            AppendUi.NONE -> Unit
        }

        if (selectionActive) {
            SelectionBar(
                count = selection.size,
                shareEnabled = !share.busy,
                onShare = {
                    share.share(
                        photos = selection.values.toList(),
                        chooserTitle = shareChooserTitle,
                        // Only on success: a failed staging must leave the picks intact so the
                        // user can simply retry rather than reselect twenty photos.
                        onShared = { selection = emptyMap() },
                    )
                },
                onClear = { selection = emptyMap() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (limitNotice) {
            Notice(
                text = stringResource(R.string.share_limit_reached, ShareSelection.MAX_ITEMS),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        ShareStatus(
            state = share.state,
            onDismissError = share::dismissError,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    // Entering ERROR after a boundary placeholder had focus moves focus to Retry. Leaving ERROR
    // is handled in Retry's click callback above: focus is confirmed on the grid before retry()
    // changes the load state and removes the banner. Touch has no focus to hand off.
    LaunchedEffect(appendPhase, isTv) {
        if (!isTv) return@LaunchedEffect
        val target = AppendFocus.onPhaseChange(
            prev = prevAppendPhase,
            next = appendPhase,
            owner = focusOwner,
        )
        prevAppendPhase = appendPhase
        when (target) {
            AppendFocusTarget.ToRetry -> {
                var attempts = 0
                while (attempts < FOCUS_RESTORE_MAX_FRAMES &&
                    focusOwner != BrowserFocusOwner.Retry
                ) {
                    runCatching { retryFocus.requestFocus() }
                    withFrameNanos { }
                    attempts++
                }
                if (focusOwner != BrowserFocusOwner.Retry) {
                    // Do not carry a failed placeholder handoff into a future append error.
                    focusOwner = BrowserFocusOwner.None
                }
            }
            is AppendFocusTarget.ToGrid -> Unit // produced only by the Retry click path above
            AppendFocusTarget.None -> Unit
        }
    }

    // Fullscreen viewer in its own window so it covers the tab bar / bottom navigation too.
    val index = viewerIndex
    if (index != null) {
        Dialog(
            onDismissRequest = {
                viewerIndex = null
                pendingFocusIndex = index
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // PhotoViewer owns Back so it can return the *current* page. Letting Dialog
                // dismiss itself would only know the page that originally opened it.
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            PhotoViewer(
                items = items,
                initialIndex = index,
                onClose = { lastIndex ->
                    viewerIndex = null
                    pendingFocusIndex = lastIndex
                },
            )
        }
    }

    // Focus the first cell once when the grid first appears (TV only, and only when asked to).
    LaunchedEffect(autoFocusFirst, items.itemCount, items.loadState.refresh, isTv) {
        if (isTv && !didInitialFocus && autoFocusFirst && items.itemCount > 0) {
            val focused = restoreGridFocus(0)
            if (focused) {
                didInitialFocus = true
                onAutoFocusConsumed()
            }
        }
    }

    // Right from the rail can arrive in the same frame as a tab switch. Wait until Paging has
    // attached the saved cell and confirm that it owns focus; a failed early fast-path therefore
    // never leaves the screen in a focusless state. There is no rail on touch.
    LaunchedEffect(contentFocusRequestToken, items.itemCount, items.loadState.refresh, isTv) {
        if (isTv && contentFocusRequestToken > handledContentFocusToken && items.itemCount > 0) {
            if (restoreGridFocus(entryFocusIndex)) {
                handledContentFocusToken = contentFocusRequestToken
            }
        }
    }

    // On returning from the viewer: scroll the viewed cell into view, then (on TV) refocus it —
    // even when it was off-screen. Rather than a single fragile frame wait, poll layout for a few
    // frames until the target item is actually laid out before requesting focus. On touch the
    // scroll alone is the point: closing the viewer at photo #500 must land back at #500.
    LaunchedEffect(pendingFocusIndex, items.itemSnapshotList) {
        val idx = pendingFocusIndex ?: return@LaunchedEffect
        if (idx in 0 until items.itemCount) {
            val restored = restoreGridFocus(idx)
            if (!isTv && restored) {
                pendingFocusIndex = null
                return@LaunchedEffect
            }
        }
        // Clear only after the exact cell confirms ownership. A transient page/load delay can
        // therefore never silently degrade into focus on an unrelated tile.
        if (focusOwner == BrowserFocusOwner.Grid(idx)) pendingFocusIndex = null
    }
}

private const val FOCUS_RESTORE_MAX_FRAMES = 30
private const val FOCUS_LOAD_TIMEOUT_MS = 3_000L
private const val LIMIT_NOTICE_MS = 2_500L

/**
 * The contextual bar shown while photos are being picked for sharing: how many are selected, the
 * share action, and a way out. Touch only — it is only ever composed when [ShareSelection] holds
 * something, and only a long press on a phone can put anything there.
 *
 * It floats over the grid rather than displacing it so the tiles never reflow mid-selection, and
 * it is opaque enough to stay readable over a bright photo.
 */
@Composable
private fun SelectionBar(
    count: Int,
    shareEnabled: Boolean,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cancelLabel = stringResource(R.string.selection_cancel)
    val shareLabel = stringResource(R.string.share)
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(Color(0xF2101620), RoundedCornerShape(28.dp))
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onClear,
            modifier = Modifier.semantics { contentDescription = cancelLabel },
        ) {
            Text(text = "✕", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        Text(
            text = pluralStringResource(R.plurals.selection_count, count, count),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        IconButton(
            onClick = onShare,
            enabled = shareEnabled,
            modifier = Modifier.semantics { contentDescription = shareLabel },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = null,
                tint = if (shareEnabled) Color.White else Color(0x66FFFFFF),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Transient, non-blocking message (currently only the selection cap). */
@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .padding(24.dp)
            .background(Color(0xE6101418), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private val PLACEHOLDER_COLOR = Color(0xFF1C2230)

/**
 * An image-less stand-in for a not-yet-loaded grid cell. [focusable] toggles whether D-pad can
 * land on it (see the append-error handling in [BrowserContent]); [onFocused] reports when a
 * focusable placeholder gains focus, so the append-error handoff knows focus is at the boundary.
 */
@Composable
private fun PhotoGridPlaceholder(
    focusable: Boolean,
    onFocused: () -> Unit = {},
) {
    val base = Modifier
        .aspectRatio(1f)
        .background(PLACEHOLDER_COLOR, RoundedCornerShape(10.dp))
    if (focusable) {
        // A bare clickable is enough to make the cell a D-pad focus target; it deliberately has
        // no visible focus affordance, because landing here is a transient step that immediately
        // triggers the next page rather than something the user chooses.
        Box(
            base
                .onFocusChanged { if (it.isFocused) onFocused() }
                .clickable(onClick = {}),
        )
    } else {
        Box(base)
    }
}

/** Non-blocking, non-focusable "loading more" chip near the current viewport. */
@Composable
private fun AppendLoadingChip(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.loading_photos),
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .padding(24.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Append-error banner with a Retry — tappable, and reachable by D-pad Down from the loaded rows. */
@Composable
private fun AppendErrorBanner(
    onRetry: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(24.dp)
            .background(Color(0xE6101418), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GalleryButton(
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() },
        ) { Text(stringResource(R.string.retry)) }
    }
}

/** Floating month label reflecting the top-most visible row. */
@Composable
private fun MonthOverlay(
    items: LazyPagingItems<PhotoEntity>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val formFactor = LocalFormFactor.current
    val label by remember {
        derivedStateOf {
            val idx = gridState.firstVisibleItemIndex
            if (idx < items.itemCount) items.peek(idx)?.let { DateFormats.monthLabel(it.captureDate) }
            else null
        }
    }
    label?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = modifier
                .padding(start = GridMetrics.screenPadding(formFactor), top = 8.dp)
                .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
