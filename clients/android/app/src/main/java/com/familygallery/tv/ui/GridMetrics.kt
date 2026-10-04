package com.familygallery.tv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How many columns each grid gets, and how much room to leave around them.
 *
 * On TV the counts are fixed: the viewing distance is fixed too, every Android TV renders a
 * 960dp-wide layout, and the D-pad row-stepping in `PhotoBrowser` reasons in whole rows — a
 * column count that changed under it would break the "move exactly one row, keep the column"
 * contract. On phones the width varies by a factor of two between portrait and landscape, so the
 * count is derived from the available width instead.
 *
 * The column functions are deliberately pure `Int -> Int`: they are the part worth testing, and
 * they must give the same answer in a unit test as they do on a device.
 */
object GridMetrics {

    /** Fixed TV counts — the 10-foot layout these screens were designed against. */
    const val TV_PHOTO_COLUMNS = 6
    const val TV_TILE_COLUMNS = 4

    /**
     * Roughly the width each grid cell should end up with on a phone. Photo thumbnails are square
     * and pre-baked at 256px, so ~116dp keeps them near 1:1 with the source on a 2x–3x screen
     * without ever upscaling; album covers carry a title and a count line, so they need more.
     */
    private const val PHONE_PHOTO_CELL_DP = 116
    private const val PHONE_TILE_CELL_DP = 180

    /**
     * Columns for the square photo/video grid.
     *
     * Clamped at three because two enormous thumbnails per row read as a list rather than a
     * gallery, and at eight because past that a tile is smaller than a comfortable tap target.
     */
    fun photoColumns(widthDp: Int, formFactor: FormFactor): Int = when (formFactor) {
        FormFactor.Tv -> TV_PHOTO_COLUMNS
        FormFactor.Mobile -> (widthDp / PHONE_PHOTO_CELL_DP).coerceIn(3, 8)
    }

    /** Columns for the album/month/season cover tiles. */
    fun tileColumns(widthDp: Int, formFactor: FormFactor): Int = when (formFactor) {
        FormFactor.Tv -> TV_TILE_COLUMNS
        FormFactor.Mobile -> (widthDp / PHONE_TILE_CELL_DP).coerceIn(2, 5)
    }

    /**
     * Gap between cells. TV keeps generous gutters so the focus ring around a scaled tile never
     * touches its neighbour; on a phone that space is wasted screen.
     */
    fun spacing(formFactor: FormFactor): Dp = if (formFactor.isTv) 10.dp else 3.dp

    /** Gap between cover tiles, which carry a caption and so need to stay visually separate. */
    fun tileSpacing(formFactor: FormFactor): Dp = if (formFactor.isTv) 20.dp else 12.dp

    /**
     * Height reserved above the first row when a floating month label is shown.
     *
     * The label is drawn over the grid, not in it. On TV the 24dp of vertical padding happens to
     * be enough that it lands in empty space, but a phone's 4dp gutter let it sit squarely on top
     * of the first thumbnail. Reserving its height means the label occupies blank space at rest;
     * once the user scrolls it floats over content, which is the intended behaviour and matches
     * how other galleries show a scroll date.
     */
    private val MONTH_OVERLAY_RESERVE = 44.dp

    /**
     * Padding around a full-screen grid. The large TV values are overscan margin — many TVs crop
     * the outer few percent of the panel. Phones show every pixel, so they only need enough to
     * keep tiles off the rounded corners.
     *
     * [reserveMonthOverlay] adds room at the top for the floating month label.
     */
    fun contentPadding(
        formFactor: FormFactor,
        reserveMonthOverlay: Boolean = false,
    ): PaddingValues {
        val vertical = if (formFactor.isTv) 24.dp else 4.dp
        val horizontal = if (formFactor.isTv) 32.dp else 4.dp
        val top = if (reserveMonthOverlay) vertical + MONTH_OVERLAY_RESERVE else vertical
        return PaddingValues(start = horizontal, end = horizontal, top = top, bottom = vertical)
    }

    /** Horizontal margin for headers, breadcrumbs and titles — matched to [contentPadding]. */
    fun screenPadding(formFactor: FormFactor): Dp = if (formFactor.isTv) 32.dp else 16.dp
}

/** The current window width in dp, recomputed on rotation (the Activity handles the config change). */
@Composable
fun rememberWindowWidthDp(): Int = LocalConfiguration.current.screenWidthDp

/** Convenience: photo-grid columns for the current form factor and window width. */
@Composable
fun rememberPhotoColumns(): Int {
    val formFactor = LocalFormFactor.current
    val widthDp = rememberWindowWidthDp()
    return remember(formFactor, widthDp) { GridMetrics.photoColumns(widthDp, formFactor) }
}

/** Convenience: cover-tile columns for the current form factor and window width. */
@Composable
fun rememberTileColumns(): Int {
    val formFactor = LocalFormFactor.current
    val widthDp = rememberWindowWidthDp()
    return remember(formFactor, widthDp) { GridMetrics.tileColumns(widthDp, formFactor) }
}
