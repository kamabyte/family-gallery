package com.familygallery.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.familygallery.tv.ui.FormFactor
import com.familygallery.tv.ui.GalleryUiState
import com.familygallery.tv.ui.LocalCatalogCacheVersion
import com.familygallery.tv.ui.LocalFormFactor
import com.familygallery.tv.ui.MainViewModel
import com.familygallery.tv.ui.components.CenterMessage
import com.familygallery.tv.ui.components.GalleryStartupSplash
import com.familygallery.tv.ui.detectFormFactor
import com.familygallery.tv.ui.shell.MobileShell
import com.familygallery.tv.ui.shell.TvShell
import com.familygallery.tv.ui.theme.FamilyGalleryTheme
import com.familygallery.tv.ui.theme.galleryBackground

/**
 * The single entry point for both form factors.
 *
 * The form factor is resolved once here, from the device rather than the window, and published
 * through [LocalFormFactor]. Everything below reads it: the shell choice, the grid column counts,
 * whether the focus machinery runs at all, and how the viewer is driven. Resolving it once (as
 * opposed to per-composable) means a rotation can change layout without ever changing interaction
 * model — a phone in landscape is still a phone.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val formFactor = detectFormFactor(this)
        // Phones draw behind the status and navigation bars; the shell insets its own chrome and
        // lets photos run full-bleed underneath. TVs have no system bars to draw behind, and
        // calling this there would only add a no-op inset listener.
        //
        // Both bars are pinned to `dark` + fully transparent rather than left on the default
        // `auto`, which contributes a translucent scrim. The app is permanently dark and paints
        // its own gradient behind the bars, so a scrim only shows up as a mismatched grey band
        // along the gesture area.
        if (formFactor.isTouch) {
            // Fully qualified: `Color` in this file is Compose's, not the Android framework's.
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
        }

        setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                FamilyGalleryTheme {
                    Box(Modifier.fillMaxSize().galleryBackground()) {
                        // Transparent Surface supplies Material's on-background content color
                        // while the single drawBehind gradient remains visible underneath.
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ) {
                            GalleryApp()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formFactor = LocalFormFactor.current

    when (val s = state) {
        is GalleryUiState.Loading -> GalleryStartupSplash()
        is GalleryUiState.Error -> CenterMessage(
            title = androidx.compose.ui.res.stringResource(R.string.connection_error_title),
            // Credential-free detail (host/share/reason only) to help diagnose the failure.
            subtitle = s.message.ifBlank { null },
            onRetry = viewModel::connect,
        )
        is GalleryUiState.Ready ->
            CompositionLocalProvider(LocalCatalogCacheVersion provides s.cacheVersion) {
                when (formFactor) {
                    FormFactor.Tv -> TvShell(viewModel)
                    FormFactor.Mobile -> MobileShell(viewModel)
                }
            }
    }
}
