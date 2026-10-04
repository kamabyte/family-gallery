# Macrobenchmark scaffold

Measures the critical Android TV journeys against the app's **`benchmark`** build type
(release-like, minified, non-debuggable, profileable) on a **real device**. Benchmarks are
meaningless on an emulator and there is no TV device in this repo's environment, so this
module is wired to *compile and configure*; you run it later on the weakest target box.

## Journeys covered / to fill in

- **Cold startup** — `GalleryBenchmarks.startupCold`
- **Warm startup** — `GalleryBenchmarks.startupWarm`
- **Timeline D-pad scrolling** — `GalleryBenchmarks.timelineScroll`
- Switching tabs, opening/closing the viewer, returning from album detail, and
  cold-vs-warm SMB image cache — stubbed in comments; complete them on the device where the
  SMB share is reachable (they need real thumbnails to load).

## Prerequisites

- A physical Android TV device (the *weakest* one you support) connected over adb
  (`adb connect <ip>` for network boxes), USB debugging on.
- The device reachable to the configured SMB share (`GalleryConfig`), with an indexed
  library, so image-cache journeys have data.

## Commands

```bash
# 1. Confirm the device is visible
adb devices

# 2. Run all macrobenchmarks against the benchmark build (installs it automatically)
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest

# 3. Run a single journey
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
    -P android.testInstrumentationRunnerArguments.class=com.familygallery.tv.macrobenchmark.GalleryBenchmarks#startupCold

# 4. Pull the JSON results (timings) off the device build dir
ls macrobenchmark/build/outputs/connected_android_test_additional_output/
```

Results (median/min/max ms, frame durations) are printed to the Gradle output and written as
JSON under `macrobenchmark/build/outputs/`. **Do not quote any performance numbers until they
come from a run on the real target device** — none have been measured in this environment.

## Baseline Profile (optional next step)

To also generate a Baseline Profile for faster cold start, add the
`androidx.baselineprofile` plugin and a `BaselineProfileGenerator`, then wire
`baselineProfile { }` into `:app`. Left out here to keep the change small and reviewable.
