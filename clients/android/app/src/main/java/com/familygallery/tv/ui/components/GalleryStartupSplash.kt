package com.familygallery.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familygallery.tv.R

private val SplashBackground = Color(0xFF0A0C11)

/**
 * Cold-start splash. The logo is pinned dead-centre at the exact size/position of the pre-Compose
 * window background (`@drawable/splash_window_background`), so the handoff from the first frame is
 * seamless. The wordmark and an indeterminate loading bar then appear beneath it.
 */
@Composable
fun GalleryStartupSplash(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SplashBackground),
    ) {
        // Matches the 132dp centred logo in splash_window_background.xml — no jump on handoff.
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(132.dp).align(Alignment.Center),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BasicText(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            IndeterminateLoadingBar(Modifier.width(180.dp))
        }
    }
}

/** Dependency-free indeterminate loading bar (no Material3 in this module). */
@Composable
private fun IndeterminateLoadingBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )
    val track = Color.White.copy(alpha = 0.16f)
    Box(
        modifier
            .height(4.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(color = track, cornerRadius = r)
                val segW = size.width * 0.4f
                val x = (size.width - segW) * progress
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x, 0f),
                    size = Size(segW, size.height),
                    cornerRadius = r,
                )
            },
    )
}
