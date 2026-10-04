package com.familygallery.tv.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.familygallery.tv"
private const val UI_TIMEOUT_MS = 15_000L

/**
 * Critical-journey macrobenchmarks. Run on the **weakest target Android TV box**, connected
 * via adb, using the app's `benchmark` build type. See README.md for exact commands.
 *
 * These are D-pad driven (TV has no touch): scrolling/navigation is simulated with UiAutomator
 * key events. They compile without a device; they only *run* against a connected TV.
 */
@RunWith(AndroidJUnit4::class)
class GalleryBenchmarks {

    @get:Rule val rule = MacrobenchmarkRule()

    /** Cold startup — process + first frame from scratch. */
    @Test
    fun startupCold() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /** Warm startup — process already warm, activity recreated. */
    @Test
    fun startupWarm() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /** Timeline D-pad scrolling — frame timing while paging down the grid. */
    @Test
    fun timelineScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = { startActivityAndWait() },
    ) {
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS)
        // Vertical focus is deliberately completed after the row scroll attaches its target.
        // Space remote presses like a real user so a burst does not benchmark queued duplicate
        // key events from the same still-focused card.
        repeat(20) {
            device.pressDPadDown()
            SystemClock.sleep(120)
        }
        device.waitForIdle()
    }

    // The remaining journeys (tab switching, viewer open/close, album return, cold-vs-warm
    // SMB image cache) follow the same shape: FrameTimingMetric + StartupMode.WARM + a
    // setupBlock that drives the app into the right screen with D-pad key events, then a
    // measured block that performs the interaction. Fill these in on the target device where
    // the SMB share and real library are reachable — see README.md.
}
