package com.familygallery.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.smb.SmbImage
import com.familygallery.tv.ui.DateFormats
import com.familygallery.tv.ui.FormFactor
import com.familygallery.tv.ui.LocalCatalogCacheVersion
import com.familygallery.tv.ui.LocalFormFactor

private val PLACEHOLDER_COLOR = Color(0xFF1C2230)
private val CELL_SHAPE = RoundedCornerShape(10.dp)

/** Matched to the navigation indicator, so "picked" reads as the same accent across the app. */
private val SELECTION_COLOR = Color(0xFF9B92FF)
private const val GRID_PREFETCH_ROWS = 3
private const val GRID_RETAIN_ROWS = 2

/**
 * Decode size for a grid thumbnail.
 *
 * The indexer bakes thumbnails at 320px on the long edge, so that is a hard quality ceiling —
 * asking for more only costs an upscale. TVs deliberately ask for less: a 1080p panel gives each
 * of six columns about 320 physical pixels anyway, and 256 keeps WebP decode and bitmap memory
 * down on the weak boxes this app targets. A phone packs three columns into ~1100 physical pixels
 * at 3x density, so each cell wants every pixel that was baked.
 */
private fun gridThumbnailSizePx(formFactor: FormFactor) = if (formFactor.isTv) 256 else 320

/**
 * A single square photo/video tile: pre-baked SMB thumbnail, a play + duration badge for videos,
 * and — on TV only — an immediate focus ring. Shared by the Timeline and album detail grids.
 *
 * The focus ring costs nothing on touch: `onFocusChanged` never reports focus on a phone, so the
 * conditional draw simply never runs, and the tile falls back to `clickable`'s ripple, which is
 * the correct touch affordance.
 *
 * @param onLongClick non-null only on touch, where a long press starts share multi-select. Left
 *   null on TV so the tile keeps a plain `clickable` — `combinedClickable` would add a long-press
 *   gesture that a D-pad can trigger by holding OK, which is not a gesture this UI has.
 * @param selected tri-state on purpose. `null` means selection mode is off and the tile draws
 *   nothing extra; `false` draws an empty check target (so it is discoverable that the *other*
 *   tiles can be picked too); `true` draws the filled check and dims the thumbnail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGridCell(
    photo: PhotoEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val formFactor = LocalFormFactor.current
    val context = LocalPlatformContext.current
    val imageModel = SmbImage(photo.thumbPath, LocalCatalogCacheVersion.current)
    val imageRequest = remember(context, imageModel, formFactor) {
        gridThumbnailRequest(context, imageModel, gridThumbnailSizePx(formFactor))
    }
    Box(
        // A Material Surface animates hidden colors/scales for every focus change. Foundation
        // clickable plus one draw pass gives the same D-pad/Enter and tap semantics without that
        // work — which is what keeps rapid D-pad scrolling smooth on low-end TV boxes.
        modifier
            .aspectRatio(1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(CELL_SHAPE)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
            .drawWithContent {
                drawContent()
                if (focused) {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2f
                    val radius = 10.dp.toPx() - inset
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(stroke),
                    )
                }
            },
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = photo.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(PLACEHOLDER_COLOR)
                // Inset the picked thumbnail instead of overlaying a tint: the gap between tiles
                // becomes the selection cue, which stays legible on a bright photo where a
                // translucent scrim would not.
                .then(if (selected == true) Modifier.padding(6.dp).clip(CELL_SHAPE) else Modifier),
        )
        if (photo.isVideo) {
            VideoBadge(photo.durationMs)
        }
        selected?.let { SelectionCheck(it, Modifier.align(Alignment.TopStart)) }
    }
}

/**
 * The pick indicator, drawn rather than composed from a Material checkbox: it sits on arbitrary
 * photo content, so it needs its own contrast (dark ring + white fill) rather than theme colours.
 */
@Composable
private fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(5.dp)
            .size(22.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            // A dark disc behind the ring keeps the unchecked state visible on white sky.
            drawCircle(color = Color(0x66000000), radius = radius, center = center)
            if (selected) {
                drawCircle(color = SELECTION_COLOR, radius = radius - 1f, center = center)
            }
            drawCircle(
                color = Color.White,
                radius = radius - 1f,
                center = center,
                style = Stroke(2.dp.toPx()),
            )
            if (selected) {
                val tick = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.74f, size.height * 0.34f)
                }
                drawPath(tick, Color.White, style = Stroke(2.2.dp.toPx()))
            }
        }
    }
}

/**
 * Starts the next three rows before the user reaches them — by D-pad on TV, by flinging on a
 * phone. The cell and prefetch requests use the exact same decode size, so Coil can hand the cell
 * the memory-cached bitmap directly instead of doing SMB I/O and a WebP decode mid-scroll.
 *
 * [columns] must match the grid's live column count: it is what converts "three rows ahead" into
 * an item-index window, and on a phone it changes with every rotation.
 */
@Composable
internal fun PrefetchPhotoThumbnails(
    items: LazyPagingItems<PhotoEntity>,
    gridState: LazyGridState,
    columns: Int,
) {
    val context = LocalPlatformContext.current
    val formFactor = LocalFormFactor.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val cacheVersion = LocalCatalogCacheVersion.current
    val models by remember(items, gridState, cacheVersion, columns) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                emptyList()
            } else {
                val first = (visible.first().index - columns * GRID_RETAIN_ROWS)
                    .coerceAtLeast(0)
                val last = (visible.last().index + columns * GRID_PREFETCH_ROWS)
                    .coerceAtMost(items.itemCount - 1)
                if (last < first) emptyList() else (first..last).mapNotNull { index ->
                    items.peek(index)?.let { SmbImage(it.thumbPath, cacheVersion) }
                }
            }
        }
    }
    val activeRequests = remember(imageLoader) { mutableMapOf<SmbImage, Disposable>() }

    DisposableEffect(imageLoader, models) {
        val wanted = models.toSet()
        val iterator = activeRequests.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in wanted) {
                entry.value.dispose()
                iterator.remove()
            }
        }
        val sizePx = gridThumbnailSizePx(formFactor)
        wanted.forEach { model ->
            if (model !in activeRequests) {
                activeRequests[model] =
                    imageLoader.enqueue(gridThumbnailRequest(context, model, sizePx))
            }
        }
        onDispose { }
    }

    DisposableEffect(imageLoader) {
        onDispose {
            activeRequests.values.forEach(Disposable::dispose)
            activeRequests.clear()
        }
    }
}

private fun gridThumbnailRequest(
    context: PlatformContext,
    model: SmbImage,
    sizePx: Int,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(model)
        .size(sizePx, sizePx)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .crossfade(false)
        .build()

@Composable
private fun BoxScope.VideoBadge(durationMs: Long?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(6.dp)
            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "▶", style = MaterialTheme.typography.labelSmall, color = Color.White)
        Text(
            text = DateFormats.durationLabel(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
