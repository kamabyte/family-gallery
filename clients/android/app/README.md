# Family Gallery — Android TV app

Kotlin + Jetpack Compose for TV. Browses the catalog produced by the [indexer](../../../workers).

## Open & run

1. Open **`clients/android/`** (the Gradle root) in Android Studio (Ladybug or newer). On first sync,
   Gradle downloads the wrapper distribution and all dependencies.
2. Set your NAS details in `clients/android/local.properties` (it is git-ignored;
   Android Studio creates it with `sdk.dir`):
   ```properties
   gallery.host=10.20.1.100       # the home server
   gallery.share=Photos           # the SMB share name (system SSD, /srv/family-gallery/photos)
   gallery.basePath=              # folder inside the share the indexer ran on; blank = share root
   # gallery.username= / gallery.password= / gallery.domain=   — only if the share needs auth
   ```
   Anonymous access is the default. The values are baked into `BuildConfig` at build time
   and read by [`GalleryConfig.kt`](src/main/java/com/familygallery/tv/GalleryConfig.kt).
3. Run on an Android TV emulator (Android TV system image) or a real box/TV.

## What's here now (Phases 0–2)

- `GalleryConfig` — SMB connection from `local.properties` via `BuildConfig`.
- `smb/` — `SmbClient` (SMBJ, anonymous, reused connection) + a Coil 3 `SmbFetcher`/`SmbKeyer`
  that stream thumbnails/previews over SMB with caching (Phase 2).
- `data/` — direct-SQLite `CatalogDatabase`, `OffsetPagingSource`, and `GallerySync`
  (manifest check + local DB copy) behind `GalleryRepository` (Phase 2).
- `GalleryApplication` — manual DI container + the tuned Coil image loader.
- `ui/MainViewModel` — runs the sync and exposes Loading / Ready / Error + Paging flows,
  including the timeline with month-header separators (`insertSeparators`).
- `ui/components/PhotoBrowser` — shared photo grid + fullscreen viewer over one paging list.
  Vertical D-pad movement explicitly keeps the current column, scrolls one row, and then focuses
  the target (avoiding Compose's expensive beyond-bounds grid search). The next three rows of
  fixed-size thumbnails are prefetched. Used by both the timeline and album detail.
- `ui/timeline/TimelineScreen` — the home grid (Phase 3): thin wrapper over `PhotoBrowser`
  with the floating month label; pure-photo paging, D-pad focus, SMB thumbnails, video badges.
- `ui/albums/` — the Albums tab (Phase 4): typed sections (Places / Cameras / Years) of
  cover-art cards → paged album-detail grid (also `PhotoBrowser`). Back returns to the tab.
- `ui/viewer/PhotoViewer` — the fullscreen viewer (Phase 5): `HorizontalPager` over the
  medium preview, responsive D-pad left/right paging (across page boundaries), and Back closes
  to the exact viewed tile. On a video, Center or media **Play/Pause** starts/toggles Media3
  playback of the indexed proxy over seekable SMB range reads; on a photo, Play/Pause toggles
  the slideshow. The bottom overlay shows date · place · camera · true `position / total`.
- `MainActivity` + `ui/theme/` — Compose-for-TV shell with a permanent compact left rail.
  Focus only previews a rail item; OK selects it, Right restores the exact content tile, and
  Back from root content opens the rail. Album detail remains a normal Back destination.

## Architecture

```
MainActivity (Compose for TV)
  └─ MainViewModel ── state: Loading / Ready / Error
       │             ── timeline / albums: Flow<PagingData>
       ▼
  GalleryRepository (mutex-serialized init/retry)
    ├─ GallerySync    ← manifest → download versioned catalog → validate → atomic swap
    ├─ CatalogDatabase (direct SQLite, read-only) ── OffsetPagingSource (count-aware LIMIT/OFFSET)
    ├─ SmbClient (SMBJ) ← reused connection; reconnect only on transient errors, storm-guarded
    └─ Coil SmbFetcher ← streams bytes over SMB; generation-keyed mem+disk cache (adaptive size)

Why not Room? The catalog is authored by the Python indexer. Room's schema/identity
validation fights an externally-created DB (index names, foreign keys, identity hash), so
we read it directly with plain SQL and stay fully decoupled.
```

## Device validation

- Run the Macrobenchmark journeys on the weakest target box and tune only from measured frame
  timing. The app bounds Paging memory, SMB/image-decode concurrency, video resolution/fps,
  and disables per-tile crossfades and hidden TV Material focus animations.

## Performance notes (why it stays smooth on weak boxes)

- The grid only ever shows pre-baked ~320px WebP thumbnails — no HEIC decode, no rotation.
- Paging 3 keeps a flat memory footprint regardless of library size.
- Coil memory + disk caches mean each thumbnail crosses the network at most once; upcoming rows
  are warmed before focus reaches them and use the same 256px decode request as visible cells.
