# photogallery-androidtv

Family gallery for Android TV **and** phones, laid out like MyTube:

| Dir | What |
|---|---|
| `workers/` | Python workers: the indexer (`workers/indexer.py`) pre-bakes thumbnails, video proxies and an SQLite catalog onto the SMB share; `service.py` runs it unattended on the NAS (Docker image `family-gallery-workers`, stack in `deploy/compose.yml`) |
| `clients/android/` | Kotlin/Compose app — a self-contained Gradle project (run `./gradlew` from there); validates, swaps in and browses that catalog |
| `web/` | Laravel + Inertia + React web app (prototype on fake data for now) |

See `README.md` for the data flow and `workers/README.md` for running the indexer.

**One APK, two shells.** `detectFormFactor` resolves `FormFactor.Tv` / `FormFactor.Mobile`
once in `MainActivity` and publishes it via `LocalFormFactor`; `TvShell` (hidden D-pad rail)
and `MobileShell` (bottom navigation) diverge from there. Everything below the UI is shared.
When touching any shared UI component, check BOTH branches — the TV path is full of explicit
focus choreography that must not run on touch, and the touch path must not lose the TV
behaviour. The app uses stock `androidx.compose.material3`; `androidx.tv.material3` was
removed, so do not reintroduce it.

## Code intelligence: CodeGraph before grep

This repo is indexed by CodeGraph (`.codegraph/`, local and gitignored). **Reach for the
graph before Grep/Glob/Read when locating or understanding code.** One `explore` call
returns the verbatim line-numbered source of the relevant symbols *plus* the call paths
between them and a blast-radius summary — including dynamic-dispatch hops that text search
cannot follow. Grep is still right for non-code text: XML resources, Gradle config,
string literals, SQL inside `indexer.py`.

```bash
codegraph explore "<symbols or a question>"   # source + call flow + blast radius
codegraph callers <Symbol>                    # who calls it (check before signature changes)
codegraph impact <Symbol>                     # everything a change ripples into
codegraph affected <changed files…>           # which test files to run
codegraph status                              # index freshness / stats
```

The MCP tool `codegraph_explore` does the same from inside a session; if it is listed as
deferred, load it with `ToolSearch("select:mcp__codegraph__codegraph_explore")` rather than
falling back to grep.

`affected` resolves this project's tests without a filter — it finds both
`clients/android/app/src/test/**/*Test.kt` and `workers/tests/test_*.py`. No `-f` glob needed.

### Anchor symbols — paste these straight into a query

| Area | Symbols |
|---|---|
| Catalog sync + validation | `GallerySync`, `CatalogSyncCoordinator`, `CatalogSyncIo`, `CatalogSwapper`, `CatalogValidator`, `CatalogManifest`, `CatalogProbe`, `NoValidCatalogException` |
| Catalog queries + paging | `GalleryRepository`, `CatalogDatabase`, `OffsetPagingSource`, `OffsetKeyMath`, `AlbumSummary`, `AlbumSection`, `SmartAlbum`, `SeasonSummary`, `Seasons` |
| SMB + image loading | `SmbClient`, `SmbDataSource`, `SmbFetcher`, `SmbKeyer`, `SmbCacheKey`, `SmbErrorClassifier` |
| UI + navigation | `MainActivity`, `MainViewModel`, `GalleryUiState`, `AlbumsScreen`, `AlbumDetailScreen`, `TimelineScreen`, `PhotoViewer`, `PhotoBrowser`, `FocusableCardGrid`, `PhotoGridCell`, `GalleryNavigationRail`, `GalleryStartupSplash` |
| Form factor + shells | `FormFactor`, `LocalFormFactor`, `detectFormFactor`, `GridMetrics`, `rememberPhotoColumns`, `rememberTileColumns`, `TvShell`, `MobileShell`, `SectionHost`, `GallerySection`, `GalleryButton` |
| App wiring | `GalleryApplication`, `GalleryConfig` |
| Indexer (Python) | `MediaRecord`, `Stats`, `LibraryLock`, `LockError`, `SCHEMA_VERSION` |
| Capture dates (Python) | `capture_ms_from_video_tags`, `video_metadata_from_probe`, `photo_metadata_from_exif`, `iso_to_epoch_ms`, `filename_to_epoch_ms`, `CAPTURE_SOURCE_APPLE`, `probe_capture_ms` |
| Metadata repair (Python) | `refresh_metadata.py`: `select_rows`, `compute_changes`, `refile_destination`, `REFRESHABLE`, `migrate_additive_columns` |

### Seams where grep goes blind — required graph calls

**Sync ports (`CatalogSyncIo`, `CatalogInspector`).** `CatalogSyncCoordinator` and
`CatalogSwapper` are pure logic that only ever call through these interfaces; the real
implementations (`GallerySync`, `AndroidCatalogInspector`) and the test fakes in
`CatalogSyncTest` are wired in at runtime. Grepping a method name finds the declaration,
not the call chain. Before changing any port method's signature or contract, run
`codegraph explore "CatalogSyncIo CatalogSyncCoordinator GallerySync"` and check every
implementation, fakes included.

**Form-factor branches (`LocalFormFactor`).** `PhotoBrowser`, `FocusableCardGrid`,
`PhotoGridCell`, `PhotoViewer`, `CenterMessage` and `AlbumsBreadcrumb` each contain a
`formFactor.isTv` branch that the graph shows as one function. Grep for a modifier and you
see one call site; what actually ships depends on the device. Before changing any of them run
`codegraph explore "PhotoBrowser FocusableCardGrid LocalFormFactor GridMetrics"` and confirm
the change is right for **both** paths — a focus requester that is never attached on touch
must never be `requestFocus()`-ed there, and a column count is a constant on TV but a
window-derived value on a phone.

**Coil registry (`SmbKeyer`, `SmbFetcher`).** Nothing calls these by name — they are
`add()`-ed to the `ImageLoader` in `GalleryApplication` and invoked by Coil. When touching
cache keying or image fetching, run
`codegraph explore "SmbKeyer SmbFetcher SmbCacheKey GalleryApplication"`; a stale
`cacheVersion` silently serves old thumbnails after a catalog swap.

**Cross-language catalog contract.** The graph indexes Kotlin and Python but does *not*
link them: the SQL schema in `indexer.py` (`CREATE TABLE photos/albums/photo_albums/meta`,
`SCHEMA_VERSION`) and the app's expectations (`CatalogValidator.REQUIRED_COLUMNS`,
`CatalogDatabase` queries) must be changed together by hand. Any column/table change the app
*reads* means: bump `SCHEMA_VERSION`, update `REQUIRED_COLUMNS` **and**
`SUPPORTED_SCHEMA_VERSION`, and run `codegraph impact CatalogDatabase` to find every read site.
`CatalogValidator.validate` compares the schema version for **exact equality**, so a bump the app
hasn't shipped makes the TV reject every catalog — indexer and APK must deploy in lockstep.

An *indexer-only* column is the exception: `REQUIRED_COLUMNS` is checked as a subset, so a column
no app query reads (e.g. `photos.capture_date_source`) needs no version bump and no APK release.
Add it to `SCHEMA` **and** to `ADDITIVE_PHOTO_COLUMNS`, since `CREATE TABLE IF NOT EXISTS` is a
no-op on an existing work DB and only the `ALTER TABLE` in `migrate_additive_columns` will
actually add it.

## Tests

```bash
cd clients/android && ./gradlew testDebugUnitTest            # JVM unit tests
cd workers && .venv/bin/python -m unittest discover -s tests  # workers tests
cd web && php artisan test && npm run types:check              # web (see web/CLAUDE.md)
```
