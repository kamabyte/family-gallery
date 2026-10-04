package com.familygallery.tv.ui.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.familygallery.tv.GalleryApplication
import com.familygallery.tv.R
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.share.MediaShareStager
import com.familygallery.tv.share.ShareTargets
import com.familygallery.tv.share.shareIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the user should see about an in-flight share. */
sealed interface ShareUiState {
    data object Idle : ShareUiState

    /** Files are being pulled off the NAS; [total] is 1 for a single photo. */
    data class Preparing(val done: Int, val total: Int) : ShareUiState

    /** Staging failed (share unreachable, file missing, no disk space). Dismissible. */
    data object Failed : ShareUiState
}

/**
 * Drives "prepare the files, then open the system share sheet".
 *
 * The preparation step is the reason this exists at all: every shared file has to be streamed off
 * the SMB share first, which for a full-resolution original is seconds of network on a phone.
 * That has to be visible, cancellable by leaving, and survivable when it fails — a bare
 * `startActivity` would give none of it.
 *
 * Scoped to the composition that remembers it: navigating away cancels a download in flight
 * rather than leaking it, which is the right trade for an action the user can simply repeat.
 */
@Stable
class ShareController internal constructor(
    private val context: Context,
    private val stager: MediaShareStager,
    private val scope: CoroutineScope,
) {
    var state: ShareUiState by mutableStateOf(ShareUiState.Idle)
        private set

    val busy: Boolean get() = state is ShareUiState.Preparing

    /**
     * Stage [photos] and open the chooser. [onShared] runs only once the chooser was actually
     * launched, so callers can clear a selection without losing it on a failure.
     */
    fun share(photos: List<PhotoEntity>, chooserTitle: String, onShared: () -> Unit = {}) {
        if (photos.isEmpty() || busy) return
        val targets = ShareTargets.of(photos)
        state = ShareUiState.Preparing(done = 0, total = targets.size)
        scope.launch {
            try {
                val uris = stager.stage(targets) { completed ->
                    state = ShareUiState.Preparing(done = completed, total = targets.size)
                }
                val intent = shareIntent(uris, ShareTargets.commonMimeType(targets), chooserTitle)
                // A Dialog's context is still the Activity, but the viewer can be hosted in one
                // and a plain application context cannot start an Activity without its own task.
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                state = ShareUiState.Idle
                onShared()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                state = ShareUiState.Failed
            } catch (e: Exception) {
                state = ShareUiState.Failed
            }
        }
    }

    fun dismissError() {
        if (state is ShareUiState.Failed) state = ShareUiState.Idle
    }
}

/**
 * A [ShareController] bound to the current composition, reusing the app's single SMB connection.
 */
@Composable
fun rememberShareController(): ShareController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        val smb = (context.applicationContext as GalleryApplication).container.smbClient
        ShareController(context, MediaShareStager(context.applicationContext, smb), scope)
    }
}

/**
 * Centered status for an in-flight or failed share. Sized to the text so it reads as a transient
 * notice over the photo rather than a modal step; taps elsewhere keep working.
 */
@Composable
fun ShareStatus(
    state: ShareUiState,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (state) {
        ShareUiState.Idle -> return
        // A single photo has no useful count to show — "1 of 1" is noise.
        is ShareUiState.Preparing ->
            if (state.total <= 1) {
                stringResource(R.string.share_preparing)
            } else {
                stringResource(R.string.share_preparing_count, state.done, state.total)
            }
        ShareUiState.Failed -> stringResource(R.string.share_failed)
    }
    Box(
        modifier = modifier
            .background(Color(0xE6101418), RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            if (state is ShareUiState.Failed) {
                Text(
                    text = stringResource(R.string.close),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFB6AEFF),
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable(onClick = onDismissError)
                        .padding(top = 2.dp, start = 8.dp),
                )
            }
        }
    }
}
