# Family Photo Gallery for Android TV and phones

A "Photos-on-Apple-TV"-style family gallery. Photos and videos live on a local SMB share;
a Python indexer pre-generates thumbnails + previews and an SQLite database, and the app
browses that catalog — with a D-pad-friendly UI on Android TV, and a touch UI on phones
and tablets.

**One APK covers both.** It installs on TV boxes (Android 7+) and on phones (Android P and
up, the oldest phone target), detects the device once at startup and picks the matching
shell. Everything below the UI — SMB, catalog sync, Paging, image loading — is shared
verbatim; see [Form factors](#form-factors).

## Why this shape

Weak Android TV boxes cannot decode full-resolution photos (especially iPhone HEIC) at
scroll time, over the network. So the heavy lifting happens **once, offline**, in the
indexer:

- Every photo/video gets a small **thumbnail** (grid) and a medium **preview** (viewer),
  stored as WebP — no HEIC decoding required on the TV. Derivatives are content-addressed by
  a **full SHA-256** of the source file, so distinct files can never share a derivative.
- Videos also get a fast-start, at-most-1080p/30 **H.264/AAC proxy**, so weak TV hardware never
  has to decode an arbitrary 4K/60 HEVC, ProRes, or unsupported source container.
- All metadata (capture date, dimensions, album, duration, ...) goes into **SQLite**.
- The indexer publishes each catalog as an **immutable, versioned file** (`catalogs/index-<rev>.db`)
  and flips `manifest.json` to point at it **atomically**, so a TV copying the catalog never
  sees a half-written file and an interrupted run always leaves the previous catalog usable.
- The TV app validates the remote catalog (`PRAGMA quick_check` + schema/revision/fingerprint)
  and only then atomically swaps it in locally; it browses with Paging 3 (direct SQLite, not
  Room) and streams the small pre-baked images over SMB with aggressive, generation-keyed caching.

## Repository layout

```
.
├── indexer/          # Python 3 indexer — run it where the SMB share is mounted
│   ├── indexer.py
│   ├── requirements.txt
│   └── README.md
└── app/              # Android app, TV + phone (Kotlin, Jetpack Compose)
```

## Form factors

`FormFactor` is resolved once in `MainActivity` from the device (leanback feature /
`UiModeManager` / absence of a touchscreen), published through `LocalFormFactor`, and read
everywhere below. It is deliberately a *device* property, not a window property: rotating a
phone changes the layout, never the interaction model.

|                | Android TV (`FormFactor.Tv`)             | Phone / tablet (`FormFactor.Mobile`)          |
| -------------- | ---------------------------------------- | --------------------------------------------- |
| Shell          | `TvShell` — hidden rail, revealed by Left/Back | `MobileShell` — persistent bottom navigation |
| Focus          | Explicit `FocusRequester` choreography, focus rings, restore-on-return | None: touch has no focus, so all of it is skipped |
| Photo grid     | Fixed 6 columns                          | Derived from width (3 portrait / 6 landscape) |
| Album tiles    | Fixed 4 columns                          | Derived from width (2 portrait / 4 landscape) |
| Viewer         | D-pad paging, OK plays video, Play/Pause starts a slideshow | Swipe, pinch + double-tap zoom, tap hides chrome and system bars |
| Back           | Reveals the navigation rail              | Walks up: album → Timeline tab → exit          |
| Extras         | —                                        | Edge-to-edge, notch-aware, pull-to-refresh     |

The column math lives in `GridMetrics` as pure `Int -> Int` functions so it is unit-tested
rather than discovered on a device (`GridMetricsTest`).

## Data flow

```
 SMB share (mounted)                     Android TV app
 ┌─────────────────────────┐ indexer.py  ┌────────────────────────────────┐
 │ 2023/summer/*.HEIC       │ ──────────▶ │ 1. connect SMB (SMBJ)          │
 │ 2024/trip/*.mp4          │             │ 2. read manifest.json          │
 │ .gallery/                │ ◀─ writes ─ │ 3. revision changed? copy      │
 │   index.work.db (state)  │             │    catalog → validate → swap   │
 │   catalogs/index-<rev>.db│             │ 4. Paging 3 over direct SQLite │
 │   manifest.json          │             │ 5. stream thumbs (Coil, cached)│
 │   thumbs/…_t.webp         │             │ 6. Timeline / Albums UI        │
 │   thumbs/…_p.webp         │             │ 7. range-read video playback   │
 │   thumbs/…_v.mp4          │             └────────────────────────────────┘
 └─────────────────────────┘

 index.work.db is the indexer's private incremental state and is never served; the app only
 copies the immutable catalogs/index-<rev>.db named by the manifest.
```

## Getting started

1. **Index your photos** — see [`indexer/README.md`](indexer/README.md).
2. **Run the app** — open the repo in Android Studio, put your SMB host/share in
   `local.properties` (`gallery.host`, `gallery.share`, `gallery.basePath` — see
   [`app/README.md`](app/README.md)), and run on an Android TV device/emulator or on a phone. The same
   `assembleDebug` output installs on both; the device decides which shell it gets.

## Production-hardening notes

- **Catalog format is schema v4.** The TV video proxy is recorded in `video_path`; upgrading
  rebuilds derivatives once and garbage-collects old ones after a successful publish.
  Originals are only ever *moved into*
  `YYYY/MM`, never modified or deleted.
- **Atomic, revision-based sync.** The app keeps its previous catalog if the remote one is
  unavailable or fails validation, and never overwrites an open SQLite database.
- **Bounded memory + real totals.** Paging keeps only a small window resident (placeholders
  give accurate totals and stable absolute positions), so the viewer shows the true
  `position / total` even across 10–20k items.
- **Tests.** JVM unit tests: `./gradlew testDebugUnitTest`. Indexer tests:
  `cd indexer && .venv/bin/python -m unittest discover -s tests`. Release build (R8):
  `./gradlew assembleRelease`. Device benchmarks: see [`macrobenchmark/README.md`](macrobenchmark/README.md).
- **Device verification:** run the supplied benchmark journeys on the weakest target TV box
  before quoting performance numbers; D-pad focus and cache behaviour must be tested with the
  real SMB library rather than an emulator.

## License

[MIT](LICENSE)
