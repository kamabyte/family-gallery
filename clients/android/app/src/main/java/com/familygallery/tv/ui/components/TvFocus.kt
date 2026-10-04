package com.familygallery.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlin.math.abs

/**
 * Keyline scrolling for TV lists/grids (adapted from the mytube app). Overrides the
 * platform BringIntoViewSpec so the focused item is pinned to a fixed fraction of the
 * viewport with a fast tween, instead of Compose's default "just barely into view" jump —
 * which is what makes D-pad scrolling feel smooth.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PositionFocusedItemInLazyLayout(
    parentFraction: Float = 0.3f,
    childFraction: Float = 0f,
    alignFullyVisible: Boolean = true,
    scrollDurationMillis: Int = 90,
    content: @Composable () -> Unit,
) {
    val spec = remember(parentFraction, childFraction, alignFullyVisible, scrollDurationMillis) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                if (scrollDurationMillis <= 0) snap()
                else tween(durationMillis = scrollDurationMillis, easing = FastOutSlowInEasing)

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                if (!alignFullyVisible && size <= containerSize &&
                    offset >= 0f && offset + size <= containerSize
                ) {
                    return 0f
                }
                val childSmallerThanParent = size <= containerSize
                val initialTarget = parentFraction * containerSize - (childFraction * size)
                val spaceAvailable = containerSize - initialTarget
                val target =
                    if (childSmallerThanParent && spaceAvailable < size) containerSize - size
                    else initialTarget
                return offset - target
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * A **bounded** registry of one [FocusRequester] per item id, reused as paging appends more
 * items. Lets us re-focus a specific cell (e.g. after returning from the fullscreen viewer)
 * rather than resetting focus to the first item.
 *
 * The map is LRU-capped so browsing a 20k-item library can't accumulate 20k requesters — we
 * only ever need requesters for currently/recently visible cells, which is what an LRU keeps.
 */
class ItemFocusRegistry(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val requesters = object : LinkedHashMap<Long, FocusRequester>(
        /* initialCapacity = */ 64, /* loadFactor = */ 0.75f, /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FocusRequester>) =
            size > maxEntries
    }

    fun of(id: Long): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

@Composable
fun rememberItemFocusRegistry(): ItemFocusRegistry = remember { ItemFocusRegistry() }

/**
 * Moves exactly one grid row and retains the current column without asking Compose to perform
 * an expensive beyond-bounds spatial focus search. The current cell stays focused during the
 * short scroll; the target receives focus only after it is attached.
 */
suspend fun LazyGridState.requestFocusInGrid(
    currentIndex: Int,
    targetIndex: Int,
    columns: Int,
    targetFocusRequester: FocusRequester,
) {
    if (layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
        val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
        if (currentItem != null) {
            val sameColumnItem = layoutInfo.visibleItemsInfo
                .asSequence()
                .filter { item ->
                    item.index != currentIndex && item.index % columns == currentIndex % columns
                }
                .minByOrNull { abs(it.index - currentIndex) }
            val rowDistance = sameColumnItem
                ?.let { abs(it.index - currentIndex) / columns }
                ?.coerceAtLeast(1)
            val rowStride = if (sameColumnItem != null && rowDistance != null) {
                abs(sameColumnItem.offset.y - currentItem.offset.y).toFloat() / rowDistance
            } else {
                currentItem.size.height.toFloat()
            }
            val direction = if (targetIndex > currentIndex) 1f else -1f
            animateScrollBy(
                value = direction * rowStride,
                animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
            )
            withFrameNanos { }
        }

        if (layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
            scrollToItem(targetIndex - (targetIndex % columns))
            withFrameNanos { }
        }
    }

    runCatching { targetFocusRequester.requestFocus() }
}
