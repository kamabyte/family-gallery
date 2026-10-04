package com.familygallery.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which interaction model the UI must present. One APK ships both shells and picks between them
 * once, at Activity start — this is a device property, not a window property, so it never changes
 * while the app runs (unlike the window size, which changes on every rotation).
 *
 * The distinction drives three things that genuinely cannot be shared:
 *
 *  * **Navigation** — a hidden, D-pad-revealed rail ([FormFactor.Tv]) vs. a bottom navigation bar
 *    ([FormFactor.Mobile]).
 *  * **Focus** — TV drives everything through explicit [androidx.compose.ui.focus.FocusRequester]
 *    choreography (focus rings, restore-on-return, manual row stepping). On touch there is no
 *    focus at all: running that machinery would steal focus, force spurious scrolls and draw
 *    focus rings the user never asked for.
 *  * **Density** — TV is a 10-foot UI (large tiles, overscan-safe padding); phones are held at
 *    arm's length and want tighter spacing and more columns per unit of width.
 *
 * Everything below the UI layer (SMB, catalog, paging, image loading) is form-factor agnostic and
 * is shared verbatim.
 */
enum class FormFactor {
    Tv,
    Mobile;

    val isTv: Boolean get() = this == Tv
    val isTouch: Boolean get() = this == Mobile
}

/**
 * The form factor for the current composition. Defaults to [FormFactor.Mobile] so a preview or a
 * test that forgets to provide it gets the touch UI, which degrades gracefully — the TV shell
 * without a D-pad would be unusable, the reverse is not true.
 */
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Mobile }

/**
 * Detects the form factor from the device rather than the window.
 *
 * Three independent signals, in order of reliability:
 *
 *  1. [UiModeManager.getCurrentModeType] == [Configuration.UI_MODE_TYPE_TELEVISION] — set by every
 *     compliant Android TV build and the signal Google itself recommends.
 *  2. The `android.software.leanback` feature — present on TV devices whose `UiModeManager` is
 *     mis-reported (some cheap boxes ship a phone-flavoured framework with the leanback add-on).
 *  3. No touchscreen — a device that cannot be touched must be driven by a D-pad, whatever it
 *     calls itself.
 *
 * Any single hit means TV. A phone trips none of them: `UI_MODE_TYPE_NORMAL`, no leanback, and a
 * touchscreen.
 */
fun detectFormFactor(context: Context): FormFactor {
    val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return FormFactor.Tv

    val pm = context.packageManager
    if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return FormFactor.Tv
    if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return FormFactor.Tv

    return FormFactor.Mobile
}
