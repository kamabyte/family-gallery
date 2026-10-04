package com.familygallery.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A dark theme shared by the TV and phone shells. Dark is not a TV-only choice: photos read best
 * against a near-black surface at any viewing distance, and committing to one scheme means the
 * system bars, the pre-Compose window background and the Compose surface all agree, so there is
 * no flash on launch and no light/dark seam around a full-bleed photo.
 *
 * This uses stock `androidx.compose.material3` rather than the `androidx.tv.material3` fork. The
 * fork's value is focus-reactive `Surface`/`Card` scaling, which this app never used — every
 * focus affordance here is hand-drawn in a single draw pass (see [com.familygallery.tv.ui.components.PhotoGridCell],
 * [com.familygallery.tv.ui.components.GalleryNavigationRail]) because the fork's colour/scale
 * animations were too expensive on low-end TV boxes. Dropping it lets one component tree serve
 * both form factors; the type scale is the same in both libraries, so TV rendering is unchanged.
 */
private val GalleryColorScheme = darkColorScheme(
    primary = Color(0xFF9B92FF),
    onPrimary = Color(0xFF171522),
    secondary = Color(0xFF65D6B4),
    background = Color(0xFF0C1118),
    surface = Color(0xFF151B25),
    onSurface = Color(0xFFF0F1F5),
    onBackground = Color(0xFFF0F1F5),
)

private val GalleryBackground = Brush.linearGradient(
    colors = listOf(
        Color(0xFF0B1017),
        Color(0xFF111824),
        Color(0xFF171522),
    ),
    start = Offset.Zero,
    end = Offset.Infinite,
)

@Composable
fun FamilyGalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GalleryColorScheme,
        content = content,
    )
}

/** One cheap draw pass for every screen instead of a hierarchy of decorative surfaces. */
fun Modifier.galleryBackground(): Modifier = drawBehind { drawRect(GalleryBackground) }
