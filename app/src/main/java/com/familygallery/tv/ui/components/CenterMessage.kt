package com.familygallery.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familygallery.tv.R
import com.familygallery.tv.ui.LocalFormFactor

/** Centered title + optional subtitle + optional retry — used for loading/empty/error. */
@Composable
fun CenterMessage(
    title: String,
    subtitle: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val formFactor = LocalFormFactor.current
    val retryFocus = remember { FocusRequester() }
    // A TV has room to spare; a phone in portrait is ~360dp wide, so the message must be able to
    // wrap and centre rather than run into the screen edges.
    Box(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            onRetry?.let {
                GalleryButton(onClick = it, modifier = Modifier.focusRequester(retryFocus)) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
    // A remote has nothing else on screen to aim at, so Retry takes focus and OK just works. On
    // touch there is no focus to move: requesting it would only draw a stray focus outline on a
    // button the user is about to tap anyway.
    if (onRetry != null && formFactor.isTv) {
        LaunchedEffect(onRetry) { runCatching { retryFocus.requestFocus() } }
    }
}
