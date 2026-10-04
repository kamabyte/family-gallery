package com.familygallery.tv.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The one button style in the app, usable from a remote and from a finger.
 *
 * `androidx.compose.material3.Button` indicates press and hover well but its focus indication is
 * a subtle ripple overlay — legible on a phone held at arm's length, invisible on a TV across a
 * room. So when the button holds D-pad focus it flips to a solid white fill with dark content,
 * which is the same focus language the navigation rail and the photo tiles already use. On touch
 * there is no focus state at all, so the button simply renders as a normal filled Material button
 * with a ripple.
 */
@Composable
fun GalleryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    // A shared interaction source is required: the focus state must be read from the *same*
    // source the Button reports into, otherwise the colours never change.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interaction,
        colors = if (focused) {
            ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF171522),
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        content = content,
    )
}
