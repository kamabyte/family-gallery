# Gallery indexer

Turns a photo/video library into the SQLite catalog + thumbnails the Android TV app reads.
Run it on any machine (PC/Mac/NAS) where the SMB share is **mounted as a normal folder**.

It does three things in one run:

1. **Ingest** — files you drop into `Photos/Imports/` are **safe-moved** into `Photos/YYYY/MM/`
   (copy → verify → delete original). You never sort anything by hand.
2. **Index** — scans the whole library, makes upright WebP thumbnails/previews plus bounded
   1080p H.264/AAC video proxies, and reads metadata (capture date, GPS, camera).
3. **Albums** — builds albums **automatically from metadata**: Places (city), Cameras, Years.
   There are no manual folder-based albums.

## Your workflow

```
Export photos from Apple Photos  ─►  drop them (flat, unsorted) into  Photos/Imports/
                                     run the indexer
                                     ─► files tidied into Photos/2024/07/…
                                     ─► catalog + auto-albums updated
```

> **Export "Unmodified Original".** Auto-albums (and correct dates) depend on EXIF/GPS/camera
> metadata. On a Mac: `File → Export → Export Unmodified Original…`. The plain "Export…" and
> some AirDrop/share paths **strip location**, which leaves Places/Cameras albums empty. After
> a run, the summary prints metadata coverage so you can check.

## Requirements

- Python 3.9+
- `ffmpeg` + `ffprobe` on `PATH` (video posters, metadata, and TV playback proxies) —
  `brew install ffmpeg`.
- Python deps: `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt`
  (Pillow, pillow-heif, and `reverse_geocoder`+`pycountry` for offline GPS→city lookup — no
  network or API key needed).

## Usage

```bash
python3 indexer.py --source /Volumes/Photos            # ingest + index + albums
python3 indexer.py --source /Volumes/Photos --no-import # skip ingest, just re-index
python3 indexer.py --source /Volumes/Photos --import-dir Drop   # custom dropbox name
python3 indexer.py --source /Volumes/Photos --rebuild  # ignore incremental cache
python3 indexer.py --source /Volumes/Photos --import-settle 300  # skip files still being copied
```

`--import-settle SECONDS` leaves any Imports file written to in the last SECONDS for a later
run (its change time is checked too, because Finder keeps the original mtime on a copy in
progress). Without it, an unattended run could move a half-copied photo and delete the
"original". The default is 0, so a manual run straight after a copy still ingests everything.

## Running on the NAS (Dokploy)

On the home server the indexer runs unattended as a Dokploy stack instead of by hand:

```
drop files into \\10.20.1.100\Media\Photos\Imports  ─►  within ~6 min they're filed and published
```

- **Image:** `ghcr.io/kamabyte/family-gallery-indexer`, built by
  `.github/workflows/indexer-image.yml` (tests first) on every push touching `indexer/`;
  `latest` tracks `main`.
- **Stack:** [`deploy/compose.yml`](../deploy/compose.yml), Dokploy project `apps`, stack
  `family-gallery-indexer`, source Raw. Library at `/mnt/seagate12tb/Media/Photos`, mounted
  read-write as `/photos`, run as `1000:3000` (`lenar:media`). No domain, no ports.
- **`service.py`** is the container's process. It runs `indexer.py` at start, every
  `INDEX_INTERVAL` (6 h), and as soon as Imports holds settled files it hasn't tried yet
  (checked every minute, `IMPORT_SETTLE` = 5 min). Runs never overlap; a failed run backs off
  15 min, and the container goes `unhealthy` until a run succeeds, which the server's
  `uptime-check` reports to Telegram.
- **Settings** (stack Environment in Dokploy): `TZ` (default `Europe/Moscow`; EXIF dates are
  read in this zone), `IMPORT_SETTLE`, `INDEX_INTERVAL`, `WORKERS` (2, matches the 2-CPU
  limit), `IMAGE_TAG` (`latest` or `sha-…` to roll back).

Logs: Dokploy → stack → Logs. Manual runs inside the container (they take the same library
lock, so the service just skips a pass that collides with one):

```bash
docker exec -it $(docker ps -qf name=family-gallery-indexer) python indexer.py --source /photos --no-import
docker exec -it $(docker ps -qf name=family-gallery-indexer) python refresh_metadata.py --source /photos --dry-run
```

Deploy a new version: push to `main`, wait for the `indexer-image` run, then **Deploy** the
stack in Dokploy (`pull_policy: always` fetches the new `latest`).

### Fixing dates without re-encoding: `refresh_metadata.py`

Because re-running is incremental, a *code* fix to metadata extraction never reaches files
already in the catalog — and `--rebuild`, which would reach them, re-encodes every video proxy
from scratch. `refresh_metadata.py` closes that gap: it re-reads container tags / EXIF only,
corrects the catalog, rebuilds albums and publishes a new revision, **reusing every existing
thumbnail, preview and proxy untouched** (so `derivative_generation` doesn't advance and the TV
keeps its image cache).

```bash
python3 refresh_metadata.py --source /Volumes/Photos --media video --dry-run   # preview
python3 refresh_metadata.py --source /Volumes/Photos --media video             # apply
python3 refresh_metadata.py --source /Volumes/Photos --only-suspect            # guessed dates only
python3 refresh_metadata.py --source /Volumes/Photos --refile                  # also fix YYYY/MM
```

| Flag | Effect |
|------|--------|
| `--media video\|photo\|all` | What to re-read (default `all`) |
| `--only-suspect` | Only rows whose date was guessed (`capture_date_source` = `mtime`, or unrecorded) |
| `--path-prefix 2026/07` | Restrict to a subtree |
| `--refile` | **Moves originals** into the `YYYY/MM` folder their date implies. Off by default |
| `--dry-run` | Report only — writes nothing, publishes nothing |

`--refile` keys off the stored date, not "did the date change in this run", so it works as a
standalone pass and is idempotent. Moves preserve mtime, so the next `indexer.py` run still sees
the files as unchanged and skips them. It takes the same exclusive lock as the indexer.

> **iCloud videos and lost capture dates.** Downloading a video from iCloud in *compressed* /
> optimized form re-encodes it, and the transcoder writes a **fresh** `mvhd` header — so the
> standard `creation_time` (and every per-stream `creation_time`) becomes the *download* time,
> while the true capture instant survives only in `com.apple.quicktime.creationdate`. The indexer
> reads the Apple atom first for exactly this reason. If your library was indexed before that,
> every such video is dated to the day it was downloaded; repair it with
> `refresh_metadata.py --media video --only-suspect` — no transcoding required.

Re-running is **incremental** (unchanged files skipped, deleted files pruned) and **safe to
repeat**. Dropping a photo that's already in the library is detected by content hash and set
aside in `Imports/_duplicates/` rather than duplicated. Schedule it (cron / Task Scheduler).

Runs are **serialized** by an exclusive lock (`.gallery/index.lock`, `flock`): if a second
run starts while one is active it exits immediately with a clear message (exit code 3) and
touches nothing, so overlapping cron invocations can't corrupt the catalog. Non-contention
lock failures (e.g. a filesystem that doesn't implement `flock`, or a permission problem)
are reported distinctly (not as "another run is active") and also refuse to run rather than
proceed unlocked. **`flock` semantics on mounted SMB shares are not guaranteed and must be
verified on your actual target storage** — run the indexer where the share is mounted and
confirm a second concurrent invocation is refused.

Workers auto-scale conservatively: the default is bounded by CPU count, by physical RAM /
~1.5 GiB per worker (one 48 MP photo decodes to >100 MB of RGB), and by a hard cap of 8.
Override with `--workers N`.

## What it writes

Everything under `<source>/.gallery/` (your originals are only ever *moved into* `YYYY/MM`,
never modified):

| File | Purpose |
|------|---------|
| `index.work.db` | Private incremental working catalog — **never served to the app** |
| `catalogs/index-<rev>-<fp>.db` | Immutable published snapshot the app copies |
| `manifest.json` | Points at the current catalog (`revision`, `catalog`, fingerprint, derivative generation, counts) |
| `thumbs/<xx>/<hash>_t.webp` | ~320px grid thumbnail (upright, EXIF-corrected) |
| `thumbs/<xx>/<hash>_p.webp` | ~1600px viewer preview |
| `thumbs/<xx>/<hash>_v.mp4` | At most 1080p/30 H.264/AAC fast-start video proxy |

`<hash>` is a **full SHA-256** of the source file, so two different files can never collide
onto one derivative. Derivatives are written to temp files and atomically renamed, and the
preview is generated once from the source with the thumbnail derived from it.

### Atomic publication

Each run mutates only `index.work.db`. When (and only when) the content actually changed, it:

1. snapshots the working DB to a standalone `journal_mode=DELETE` temp file and validates it
   (`quick_check`, tables, schema, journal mode);
2. atomically renames it to `catalogs/index-<rev>-<fp>.db`;
3. atomically replaces `manifest.json` to point at the new catalog (the single commit point);
4. garbage-collects superseded catalogs and now-unreferenced derivatives (never originals).

A **no-op run publishes nothing** (same fingerprint ⇒ same revision), an interrupted run
leaves the previous published catalog fully usable, and each catalog gets a monotonic
`revision` plus a content fingerprint (not a one-second timestamp) as its identity.

Derivative dimensions and encoder parameters are part of a separate fingerprint. Changing
`--thumb`, `--preview`, quality/method, or the derivative format version automatically
reprocesses affected content and advances `derivative_generation`; `--rebuild` is not required
for configuration changes. Ordinary photo additions/deletions keep the generation stable so
existing TV image-cache entries remain reusable.

## Catalog schema (v4)

- **photos**: `relative_path` (unique), `filename`, `media_type`, `capture_date` (epoch ms;
  EXIF has no timezone so it's read in the indexing machine's local time), `capture_date_source`,
  `width/height/orientation`, `thumb_path`, `preview_path`, `video_path`, `duration_ms`,
  `gps_lat/lon`, `place_city`, `place_country`, `camera_make`, `camera_model`.

### Capture-date provenance

`capture_date_source` records where each date came from, so a guess is never mistaken for a
recorded fact. It is **additive and indexer-only** — no app query reads it, and the app's
validator checks that required columns are *present*, not that no others are — so it needed no
`SCHEMA_VERSION` bump and no coordinated APK release. Existing work databases gain it via an
`ALTER TABLE` on next open, with rows keeping `NULL` (treated as unproven by `--only-suspect`).

| Value | Meaning |
|---|---|
| `apple_quicktime` | `com.apple.quicktime.creationdate` — survives re-encoding, carries the original UTC offset |
| `quicktime_header` | Container `creation_time` — **rewritten by transcoders**, so only trusted when the Apple atom is absent |
| `exif` | EXIF `DateTimeOriginal`, falling back to `DateTime` |
| `filename` | Date parsed out of the file name (`VID_20181201_141857.mov`) |
| `mtime` | No capture metadata at all — for downloaded files this is the *download* time |

Every run prints the breakdown, and warns loudly when anything fell through to `mtime`.
- **albums**: `name`, `type` (`place` | `camera` | `year`), `album_key`, `cover_photo_id`,
  `photo_count`, `sort_order`. Identity is `(type, album_key)` — a place key embeds the
  country, so two cities that share a name (Springfield US vs AU) never merge; reconciliation
  keeps album ids stable across runs. Membership lives in…
- **photo_albums**: `(photo_id, album_id)` join plus the immutable `capture_date` order key,
  indexed per album so paged album queries never re-sort the full membership.
- **meta**: `schema_version` (=4), `photo_count`, `content_fingerprint`.
- Composite indexes `idx_photos_capture_id` and `idx_photo_albums_album_capture` match the app's
  exact query shapes.

The app groups the Albums tab by `type` (Places / Cameras / Years). The Timeline ignores
albums entirely — it's ordered purely by `capture_date`.

> **Upgrading from v3:** `video_path` and the TV playback proxy make v4 a format bump. The first
> v4 run reprocesses the library, generates proxies for videos, and GCs obsolete derivatives
> after publishing. This is expected and one-time.

## Notes

- Photos: JPG, PNG, HEIC/HEIF, WEBP, GIF, BMP, TIFF. Videos: MP4, MOV, M4V, MKV, AVI, WEBM.
- HEIC is decoded here and thumbnails are upright WebP, so the TV never needs a HEIC decoder.
- One bad file never aborts a run — it's logged and counted. A *new* file that fails is
  `failed=`; an *existing* file that fails to re-process is `failed_updates=` and flagged
  loudly, because its catalog row is now stale (never silently presented as current).
- If a photo's GPS changes or disappears, its Place is recomputed / cleared on the next run.

## Tests

```bash
.venv/bin/python -m unittest discover -s tests -v
```

Covers full-content hashing, worker selection, atomic/no-op/incremental publication, orphan
cleanup, place identity + GPS invalidation, catalog validation, and concurrent duplicate
derivative writes. `tests/test_capture_dates.py` additionally pins the capture-date source
ordering (using real `ffprobe` tag blocks from iCloud-downloaded videos), ISO-8601 parsing
including the colon-less `+0300` offset Python 3.9 rejects, the additive column migration, and
the refresh tool end-to-end. Uses temp dirs and generated fixtures only — never a real NAS or
media.
