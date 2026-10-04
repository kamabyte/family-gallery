package com.familygallery.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.material3.Icon
import com.familygallery.tv.R

val NAVIGATION_WIDTH = 226.dp

/** Rail slide-in/out duration; the shell waits this long before swapping a section's content. */
const val NAVIGATION_RAIL_ANIM_MS = 220

data class GalleryNavigationItem(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
)

/**
 * YouTube-style navigation rail for a remote control:
 *
 *  * it is fully hidden while content owns focus and slides in over (not into) the grid;
 *  * focusing an item never changes the current screen — only OK/click selects it;
 *  * Right always returns to the active screen's remembered focus target.
 *
 * The rail is always composed (so its item [FocusRequester]s stay attached and Back / D-pad
 * Left can focus it) but is translated fully off the left edge when [expanded] is false, so
 * content gets the whole width. Avoiding animated width/scale transitions is deliberate: the
 * rail is a fast escape hatch on low-end TV boxes, so focus feedback is immediate and creates
 * no GPU layers.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GalleryNavigationRail(
    items: List<GalleryNavigationItem>,
    selectedIndex: Int,
    expanded: Boolean,
    itemFocusRequesters: List<FocusRequester>,
    onEnterContent: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Slide the rail in/out (translation only — no relayout), the native drawer feel on TV.
    val targetOffsetPx = with(LocalDensity.current) {
        (if (expanded) 0.dp else -NAVIGATION_WIDTH).roundToPx()
    }
    val offsetX by animateIntAsState(
        targetValue = targetOffsetPx,
        animationSpec = tween(durationMillis = NAVIGATION_RAIL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "railOffset",
    )
    Column(
        modifier = modifier
            .zIndex(2f)
            .offset { IntOffset(offsetX, 0) }
            .width(NAVIGATION_WIDTH)
            .fillMaxHeight()
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFF101620), Color(0xFA101620)),
                    ),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 12.dp, vertical = 22.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusRestorer { itemFocusRequesters[selectedIndex] }
            .focusGroup(),
        horizontalAlignment = Alignment.Start,
    ) {
        Brand()
        Spacer(Modifier.height(34.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEachIndexed { index, item ->
                NavigationButton(
                    label = stringResource(item.labelRes),
                    iconRes = item.iconRes,
                    selected = selectedIndex == index,
                    focusRequester = itemFocusRequesters[index],
                    onEnterContent = onEnterContent,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun Brand() {
    Row(
        modifier = Modifier.height(54.dp).fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_logo),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)),
        )
        BasicText(
            text = stringResource(R.string.app_name),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = Color.White,
                fontSize = 19.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun NavigationButton(
    label: String,
    @DrawableRes iconRes: Int,
    selected: Boolean,
    focusRequester: FocusRequester,
    onEnterContent: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        focused -> Color.White
        selected -> Color(0xFF7267E8).copy(alpha = 0.28f)
        else -> Color.Transparent
    }
    val border = when {
        focused -> Color.White
        selected -> Color(0xFF9B92FF).copy(alpha = 0.48f)
        else -> Color.Transparent
    }
    val contentColor = when {
        focused -> Color(0xFF171522)
        selected -> Color(0xFFE3E0FF)
        else -> Color.White.copy(alpha = 0.72f)
    }

    Box(
        modifier = Modifier
            .height(52.dp)
            .fillMaxWidth()
            .drawBehind {
                val radius = 14.dp.toPx()
                if (background.alpha > 0f) {
                    drawRoundRect(background, cornerRadius = CornerRadius(radius, radius))
                }
                if (border.alpha > 0f && !focused) {
                    drawRoundRect(
                        color = border,
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                // Right dismisses the rail into content. Left must be consumed the same way:
                // the rail is the leftmost visible element, but the inactive tab is kept composed
                // off-screen to its left, so an un-intercepted Left focus-search would strand focus
                // on the invisible section instead of closing the rail. Routing Left through
                // onEnterContent closes the rail and returns focus to the visible content grid.
                if (event.key != Key.DirectionRight && event.key != Key.DirectionLeft) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyDown) {
                        onEnterContent()
                    }
                    true
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            BasicText(
                text = label,
                maxLines = 1,
                style = TextStyle(
                    color = contentColor,
                    fontSize = 18.sp,
                    fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                ),
            )
        }
    }
}
