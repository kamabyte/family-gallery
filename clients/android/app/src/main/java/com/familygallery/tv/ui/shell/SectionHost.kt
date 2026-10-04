package com.familygallery.tv.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Keeps every [GallerySection] composed at once and shows only the selected one.
 *
 * Both shells need exactly this, for the same reason: switching tabs must be instant. Rebuilding
 * a section would re-collect its Paging flow, re-run its SQLite queries, re-request its
 * thumbnails and — for Albums — throw away the drill-down back-stack the user built. So the
 * inactive section is not removed, it is translated out of the window: still measured and placed
 * (a zero-size layout makes focus and `bringIntoView` throw), but invisible and unreachable.
 *
 * @param content receives the section to draw and whether it is the visible one. Sections must
 *   use that flag to disable anything global — BackHandlers above all — so a hidden section can
 *   never consume input meant for the visible one.
 */
@Composable
internal fun SectionHost(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable (section: GallerySection, active: Boolean) -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        GallerySection.entries.forEach { section ->
            key(section) {
                val active = section.ordinal == selectedIndex
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (active) Modifier
                            else Modifier.offset(x = OFFSCREEN_TAB_X_DP.dp),
                        ),
                ) {
                    content(section, active)
                }
            }
        }
    }
}
