#!/usr/bin/env python3
"""
Family gallery indexer (schema v4).

Pipeline, in one run:

  1. INGEST   Take flat files dropped in <source>/Imports and safe-move them into
              <source>/YYYY/MM (copy -> verify -> delete original). Content-hash dedup
              means re-dropping the same photo never duplicates it; exact duplicates of
              photos already in the library are set aside in Imports/_duplicates.
  2. INDEX    Scan the whole library, generate upright WebP thumbnails + previews, and
              extract metadata (capture date, GPS, camera). All mutation happens in a
              private WORKING database (index.work.db); it is never served directly.
  3. ALBUMS   Build albums automatically from metadata — Places (city), Cameras, Years, and
              Trips (clusters of away-from-home photos). No manual folder-based albums.
  4. PUBLISH  Snapshot the working DB, validate it, and expose it as an immutable,
              versioned catalog file. The manifest is flipped atomically to point at the
              new catalog, so a TV copying the catalog never sees a partial write, and an
              interrupted run always leaves the previously published catalog usable.

Outputs, all under <source>/.gallery/:
  index.work.db                private working catalog (incremental state; NOT served)
  catalogs/index-<rev>-<fp>.db immutable published snapshot the app copies
  manifest.json                {schema_version, revision, catalog, content_fingerprint, ...}
  thumbs/<xx>/<hash>_t.webp     ~320px grid thumbnail
  thumbs/<xx>/<hash>_p.webp     ~1600px viewer preview
  thumbs/<xx>/<hash>_v.mp4      TV-friendly H.264/AAC video proxy

Content identity (hash naming derivatives) is a full SHA-256 of the whole file, so two
different files can never share a derivative. Changing that algorithm is a derivative
format change; SCHEMA_VERSION is bumped so open_db() rebuilds and regenerates cleanly.

Usage:
  python3 indexer.py --source /Volumes/Photos
  python3 indexer.py --source /Volumes/Photos --no-import      # skip the ingest phase
  python3 indexer.py --source /Volumes/Photos --import-dir Drop
  python3 indexer.py --source /Volumes/Photos --rebuild
  python3 indexer.py --source /Volumes/Photos --workers 4      # explicit worker override
  python3 indexer.py --source /Volumes/Photos --import-settle 300   # unattended runs
"""
from __future__ import annotations

import argparse
import errno
import fcntl
import json
import hashlib
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple

try:
    from PIL import Image, ImageOps
except ImportError:
    sys.exit("Pillow is required. Run: pip install -r requirements.txt")

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
    HEIF_OK = True
except ImportError:
    HEIF_OK = False

# --- Constants ---------------------------------------------------------------

SCHEMA_VERSION = 4
# Bump whenever the *album-derivation logic* changes without the photo columns changing (e.g. a new
# album type, or retuned Trip thresholds). It is folded into the content fingerprint so an otherwise
# no-op re-index still republishes the rebuilt albums. v2 = added Trips.
ALBUMS_LOGIC_VERSION = 2
GALLERY_DIRNAME = ".gallery"
WORK_DB_NAME = "index.work.db"
LEGACY_DB_NAME = "index.db"          # pre-v3 in-place catalog; cleaned up on upgrade
CATALOGS_DIRNAME = "catalogs"
CATALOG_PREFIX = "index-"
CATALOG_TMP_PREFIX = ".tmp-"
MANIFEST_NAME = "manifest.json"
THUMBS_DIRNAME = "thumbs"
DEFAULT_IMPORT_DIR = "Imports"
DUPLICATES_DIRNAME = "_duplicates"
LOCK_NAME = "index.lock"

# Full-file streaming hash chunk. SHA-256 over the entire file — never a size+edges
# shortcut — so distinct files cannot collide onto the same content-addressed derivative.
HASH_CHUNK = 1 << 20  # 1 MiB

# A single 48 MP photo decodes to well over 100 MB of RGB, and HEIF/WebP encode needs
# working headroom on top. Budget ~1.5 GiB per worker so a handful of huge photos in
# flight can't balloon into many gigabytes of concurrent allocations.
PER_WORKER_MEM_BUDGET = 1536 * 1024 * 1024
MAX_AUTO_WORKERS = 8

# How many previously published catalogs to retain after a successful publish, so a TV
# still mid-download of the prior revision isn't cut off.
KEEP_OLD_CATALOGS = 1

# Separator used inside album identity keys (unit-separator, never appears in text).
_KEY_SEP = "\x1f"

# Trip clustering (the "trip" auto-album). A trip is a run of photos taken away from the home
# location and close together in time. Tunable thresholds — deliberately conservative so a single
# out-of-town errand never becomes a "trip".
TRIP_GAP_MS = 3 * 24 * 60 * 60 * 1000   # start a new trip after > 3 days with no away photos
TRIP_MIN_PHOTOS = 5                     # a trip needs at least this many photos …
TRIP_MIN_DAYS = 2                       # … spread over at least this many calendar days (overnight)

# Nominative Russian month names for trip labels ("Saint Petersburg, июль 2024").
_RU_MONTHS = ("январь", "февраль", "март", "апрель", "май", "июнь",
              "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")

PHOTO_EXTS = {".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp", ".gif", ".bmp",
              ".tif", ".tiff"}
VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".mkv", ".avi", ".webm"}

# EXIF tag ids
_EXIF_DATETIME = 0x0132            # DateTime
_EXIF_ORIENTATION = 0x0112        # Orientation
_EXIF_MAKE = 0x010F               # Make
_EXIF_MODEL = 0x0110              # Model
_EXIF_SUB_IFD = 0x8769            # Exif IFD pointer
_EXIF_GPS_IFD = 0x8825           # GPS IFD pointer
_EXIF_DATETIME_ORIGINAL = 0x9003  # DateTimeOriginal (inside Exif IFD)

# QuickTime/MP4 container tags carrying a capture timestamp.
#
# ``com.apple.quicktime.creationdate`` (an Apple ``udta`` atom) is checked FIRST and is the only
# trustworthy source for anything that has passed through iCloud. Downloading a video in
# "compressed"/optimized form re-encodes it, and the transcoder writes a *fresh* ``mvhd`` header —
# so the standard ``creation_time`` (and every per-stream ``creation_time``) becomes the download
# time, while the Apple atom is copied through with the original capture instant. The Apple value
# also carries the original UTC offset (e.g. ``2021-05-29T19:27:13+03:00``), so it buckets into the
# right local day; ``creation_time`` is UTC-normalized and can land on the wrong date near midnight.
_QT_APPLE_CREATIONDATE = "com.apple.quicktime.creationdate"
_QT_CREATION_TIME = "creation_time"

# Provenance recorded in ``photos.capture_date_source``. Purely diagnostic — the app never reads
# it — but it makes a guessed date distinguishable from a real one, which is what let a library of
# iCloud downloads silently date itself to the indexing day.
CAPTURE_SOURCE_APPLE = "apple_quicktime"     # com.apple.quicktime.creationdate
CAPTURE_SOURCE_QUICKTIME = "quicktime_header"  # container/stream creation_time
CAPTURE_SOURCE_EXIF = "exif"                 # EXIF DateTimeOriginal / DateTime
CAPTURE_SOURCE_FILENAME = "filename"         # date parsed out of the file name
CAPTURE_SOURCE_MTIME = "mtime"               # last resort — almost certainly not the capture time

# Sources that are a guess rather than recorded capture metadata.
CAPTURE_SOURCES_SUSPECT = frozenset({CAPTURE_SOURCE_MTIME})

# Capture dates embedded in file names, most specific first. Only ever consulted after the
# container/EXIF metadata has come up empty.
_FILENAME_DATE_PATTERNS = (
    # 20160611_191446, VID_20181201-141857, PXL_20220101T120000
    re.compile(r"(?<!\d)((?:19|20)\d{2})(\d{2})(\d{2})[_\-T](\d{2})(\d{2})(\d{2})(?!\d)"),
    # 2021-05-29 at 19.27.13  /  2021_05_29-19_27_13
    re.compile(r"(?<!\d)((?:19|20)\d{2})[-_.](\d{2})[-_.](\d{2})"
               r"(?:[ T_]|[ ]at[ ])+(\d{2})[-_.:](\d{2})[-_.:](\d{2})(?!\d)"),
    # 2021-05-29 / 2021_05_29 — date only
    re.compile(r"(?<!\d)((?:19|20)\d{2})[-_.](\d{2})[-_.](\d{2})(?!\d)"),
)

WEBP_QUALITY = 80
WEBP_METHOD = 2  # 0=fast … 6=slow/smaller; 2 is a good speed/size balance
VIDEO_PROXY_MAX_WIDTH = 1920
VIDEO_PROXY_MAX_HEIGHT = 1080
VIDEO_PROXY_MAX_FPS = 30.0
VIDEO_PROXY_CRF = 23
VIDEO_PROXY_AUDIO_KBPS = 128
COPY_BUFFER = 1 << 16

# Bump when the derivative ENCODER/FORMAT logic changes in a way that alters output bytes for
# an unchanged source+params (e.g. switching encoder, changing how we transpose/convert). It
# feeds the derivative generation, which the app uses to key its image cache — so bumping this
# (or changing dimensions/quality/method, or running --rebuild) invalidates cached images,
# while ordinary catalog additions/deletions do NOT.
DERIVATIVE_FORMAT_VERSION = 2

# Bump when the published SQLite container changes independently of the logical schema.  The
# first version normalizes snapshots to rollback-journal (DELETE) mode so a catalog is a truly
# self-contained, read-only file with no WAL/SHM sidecar dependency.
CATALOG_FORMAT_VERSION = 1


# --- Data model --------------------------------------------------------------


@dataclass
class MediaRecord:
    relative_path: str
    filename: str
    media_type: str
    capture_date: int
    width: int
    height: int
    orientation: int
    mime_type: str
    size_bytes: int
    file_mtime_ns: int
    content_hash: str
    thumb_path: str
    preview_path: str
    video_path: Optional[str] = None
    duration_ms: Optional[int] = None
    gps_lat: Optional[float] = None
    gps_lon: Optional[float] = None
    camera_make: Optional[str] = None
    camera_model: Optional[str] = None
    # Provenance of capture_date (one of the CAPTURE_SOURCE_* constants). Diagnostic only — no
    # app query reads it — but it is what makes a guessed date auditable after the fact.
    capture_date_source: Optional[str] = None


@dataclass
class Stats:
    imported: int = 0
    import_dupes: int = 0
    import_waiting: int = 0    # Imports files skipped as still being written
    added: int = 0
    updated: int = 0
    skipped: int = 0
    pruned: int = 0
    failed: int = 0            # new files that could not be processed
    failed_updates: int = 0    # existing files whose re-process failed (kept stale row)
    videos: int = 0


def log(msg: str) -> None:
    print(msg, flush=True)


def have_ffmpeg() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def content_hash(path: Path, size: Optional[int] = None) -> str:
    """Full-content SHA-256 digest of a file, streamed in fixed-size chunks.

    Hashing the *entire* byte stream (not just size + first/last MiB) is what lets us use
    the digest as a safe content-addressed identity for dedup and derivative filenames:
    two files that differ anywhere — including only in the middle — get different hashes.
    ``size`` is accepted for call-site convenience but is not part of the digest.
    """
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(HASH_CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def total_ram_bytes() -> int:
    """Best-effort physical RAM in bytes (0 if it can't be determined)."""
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (ValueError, OSError, AttributeError):
        return 0


def default_worker_count(
    cpu: Optional[int],
    ram_bytes: int,
    budget: int = PER_WORKER_MEM_BUDGET,
    cap: int = MAX_AUTO_WORKERS,
) -> int:
    """Conservative, memory-aware default worker count.

    Bounded by CPU count, by how many per-worker memory budgets fit in physical RAM, and
    by a hard cap. Never oversubscribes cores the way the old ``min(32, 4*cpu)`` did — big
    photos make this decode/encode work memory-bound, not I/O-bound.
    """
    cpu = cpu or 4
    mem_workers = max(1, ram_bytes // budget) if ram_bytes > 0 else cpu
    return max(1, min(cpu, mem_workers, cap))


# --- Metadata extraction -----------------------------------------------------


def exif_to_epoch_ms(value: str) -> Optional[int]:
    """Parse an EXIF ``DateTimeOriginal``/``DateTime`` string to epoch milliseconds.

    Timezone assumption: EXIF datetimes carry no timezone, so we interpret them in the
    indexing machine's *local* time (``datetime.timestamp()``). Run the indexer in the
    same timezone the photos were taken in for correct day/month bucketing; the app then
    formats this epoch-ms value in the device's local timezone.
    """
    try:
        dt = datetime.strptime(value.strip(), "%Y:%m:%d %H:%M:%S")
        return int(dt.timestamp() * 1000)
    except (ValueError, TypeError):
        return None


def iso_to_epoch_ms(value: str) -> Optional[int]:
    """Parse an ISO-8601 timestamp to epoch milliseconds.

    Normalizes the two forms ``datetime.fromisoformat`` rejects before Python 3.11 (this runs on
    3.9 on the Pi): a trailing ``Z``, and a colon-less UTC offset such as ``+0300``. Apple writes
    the offset both ways depending on iOS version, and silently returning ``None`` for one of them
    would drop the file through to the mtime fallback — the exact failure this whole path exists
    to prevent. A naive timestamp (no offset at all) is interpreted in the indexing machine's
    local timezone, matching :func:`exif_to_epoch_ms`.
    """
    try:
        v = value.strip()
    except AttributeError:
        return None
    if v.endswith(("Z", "z")):
        v = v[:-1] + "+00:00"
    # +0300 / -0430 → +03:00 / -04:30 (leave an already-colonised offset alone).
    v = re.sub(r"([+-]\d{2})(\d{2})$", r"\1:\2", v)
    try:
        return int(datetime.fromisoformat(v).timestamp() * 1000)
    except (ValueError, TypeError):
        return None


def filename_to_epoch_ms(name: str) -> Optional[int]:
    """Recover a capture date embedded in a file name (``VID_20181201_141857.mov``).

    A weak signal, so it is only consulted after container/EXIF metadata yields nothing. Rejects
    impossible calendar values and anything in the future, since a random digit run in a name can
    otherwise parse into a plausible-looking timestamp.
    """
    now_ms = int(time.time() * 1000)
    for pattern in _FILENAME_DATE_PATTERNS:
        m = pattern.search(name or "")
        if not m:
            continue
        parts = [int(g) for g in m.groups()]
        try:
            dt = datetime(*parts) if len(parts) == 6 else datetime(parts[0], parts[1], parts[2])
        except ValueError:
            continue  # e.g. month 19 — a coincidental digit run, not a date
        ms = int(dt.timestamp() * 1000)
        if ms <= now_ms:
            return ms
    return None


def _rationals_to_degrees(values) -> Optional[float]:
    try:
        d, m, s = values
        return float(d) + float(m) / 60.0 + float(s) / 3600.0
    except (TypeError, ValueError, ZeroDivisionError):
        return None


def extract_gps(exif) -> Tuple[Optional[float], Optional[float]]:
    try:
        gps = exif.get_ifd(_EXIF_GPS_IFD)
        if not gps:
            return (None, None)
        lat = _rationals_to_degrees(gps.get(2))
        lon = _rationals_to_degrees(gps.get(4))
        if lat is None or lon is None:
            return (None, None)
        if str(gps.get(1, "N")).upper().startswith("S"):
            lat = -lat
        if str(gps.get(3, "E")).upper().startswith("W"):
            lon = -lon
        return (round(lat, 6), round(lon, 6))
    except Exception:
        return (None, None)


def _clean(value) -> Optional[str]:
    if value is None:
        return None
    s = str(value).strip().strip("\x00").strip()
    return s or None


def parse_iso6709(value: str) -> Tuple[Optional[float], Optional[float]]:
    """Parse a QuickTime ISO-6709 location like '+41.8902+012.4922+000.000/'."""
    nums = re.findall(r"[+-]\d+(?:\.\d+)?", value or "")
    if len(nums) >= 2:
        try:
            return (round(float(nums[0]), 6), round(float(nums[1]), 6))
        except ValueError:
            return (None, None)
    return (None, None)


# --- Shared tag → catalog-field extraction -----------------------------------
#
# These read ONLY container tags / EXIF — no pixel decoding, no transcoding — so both the full
# indexer and the standalone metadata refresh tool can call them. Keeping one implementation is
# what stops the two paths from drifting into disagreeing about a file's capture date.


def capture_ms_from_video_tags(tags: dict) -> Tuple[Optional[int], Optional[str]]:
    """Best capture timestamp from a QuickTime/MP4 format-tag block, with its provenance.

    Preference order and why it is not negotiable: the Apple ``creationdate`` atom survives
    re-encoding, the ``mvhd`` ``creation_time`` does not. Reading them the other way round is
    exactly what dated a library of iCloud downloads to the day they were downloaded.

    Per-stream ``creation_time`` is deliberately NOT consulted: a transcoder rewrites the track
    headers alongside the movie header, so it is never fresher than the format-level value — it
    only adds a second way to record the same wrong answer.
    """
    apple = _clean(tags.get(_QT_APPLE_CREATIONDATE))
    if apple:
        ms = iso_to_epoch_ms(apple)
        if ms is not None:
            return ms, CAPTURE_SOURCE_APPLE
    header = _clean(tags.get(_QT_CREATION_TIME))
    if header:
        ms = iso_to_epoch_ms(header)
        if ms is not None:
            return ms, CAPTURE_SOURCE_QUICKTIME
    return None, None


def video_metadata_from_probe(info: dict, path: Path,
                              mtime_ns: Optional[int] = None) -> dict:
    """Every catalog field derivable from an ``ffprobe`` result, without touching the pixels.

    ``mtime_ns`` supplies the last-resort fallback; pass ``None`` to leave ``capture_date`` unset
    when no real metadata exists (the refresh tool uses that to avoid overwriting a good stored
    date with a worse guess).
    """
    fmt = info.get("format", {}) or {}
    tags = fmt.get("tags", {}) or {}
    v_stream = next(
        (s for s in info.get("streams", []) if s.get("codec_type") == "video"), {}
    )

    capture_ms, source = capture_ms_from_video_tags(tags)
    if capture_ms is None:
        capture_ms = filename_to_epoch_ms(path.name)
        source = CAPTURE_SOURCE_FILENAME if capture_ms is not None else None
    if capture_ms is None and mtime_ns is not None:
        capture_ms, source = int(mtime_ns / 1_000_000), CAPTURE_SOURCE_MTIME

    duration_s = float(fmt.get("duration") or v_stream.get("duration") or 0.0)
    loc = tags.get("com.apple.quicktime.location.ISO6709") or tags.get("location")
    gps_lat, gps_lon = parse_iso6709(loc) if loc else (None, None)

    return {
        "capture_date": capture_ms,
        "capture_date_source": source,
        "duration_s": duration_s,
        "duration_ms": int(duration_s * 1000) if duration_s > 0 else None,
        "width": int(v_stream.get("width") or 0),
        "height": int(v_stream.get("height") or 0),
        "gps_lat": gps_lat,
        "gps_lon": gps_lon,
        "camera_make": _clean(tags.get("com.apple.quicktime.make")),
        "camera_model": _clean(tags.get("com.apple.quicktime.model")),
        "source_fps": _frame_rate(v_stream.get("avg_frame_rate")
                                  or v_stream.get("r_frame_rate") or ""),
    }


def photo_metadata_from_exif(exif, path: Path,
                             mtime_ns: Optional[int] = None) -> dict:
    """Catalog fields derivable from an image's EXIF block (``exif`` may be falsy/absent)."""
    capture_ms = source = None
    orientation = 1
    gps_lat = gps_lon = make = model = None

    if exif:
        orientation = int(exif.get(_EXIF_ORIENTATION, 1) or 1)
        make = _clean(exif.get(_EXIF_MAKE))
        model = _clean(exif.get(_EXIF_MODEL))
        gps_lat, gps_lon = extract_gps(exif)
        try:
            sub = exif.get_ifd(_EXIF_SUB_IFD)
            dt_original = sub.get(_EXIF_DATETIME_ORIGINAL) if sub else None
        except Exception:
            dt_original = None
        capture_ms = exif_to_epoch_ms(dt_original) if dt_original else None
        if capture_ms is None and exif.get(_EXIF_DATETIME):
            capture_ms = exif_to_epoch_ms(exif.get(_EXIF_DATETIME))
        if capture_ms is not None:
            source = CAPTURE_SOURCE_EXIF

    if capture_ms is None:
        capture_ms = filename_to_epoch_ms(path.name)
        source = CAPTURE_SOURCE_FILENAME if capture_ms is not None else None
    if capture_ms is None and mtime_ns is not None:
        capture_ms, source = int(mtime_ns / 1_000_000), CAPTURE_SOURCE_MTIME

    return {
        "capture_date": capture_ms,
        "capture_date_source": source,
        "orientation": orientation,
        "gps_lat": gps_lat,
        "gps_lon": gps_lon,
        "camera_make": make,
        "camera_model": model,
    }


# --- Derivative generation ---------------------------------------------------


def shard_dir(thumbs_root: Path, digest: str) -> Path:
    d = thumbs_root / digest[:2]
    d.mkdir(parents=True, exist_ok=True)
    return d


def _atomic_save_webp(img, dest: Path) -> None:
    """Encode to a unique temp file in the destination dir, then atomically rename.

    Two library files with identical bytes hash to the same digest and therefore target
    the same derivative path. Because every worker writes its own temp file and finishes
    with a single ``os.replace``, concurrent writers can never interleave into a corrupt
    half-written derivative — the last writer wins with a complete, valid file.
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=str(dest.parent), prefix=CATALOG_TMP_PREFIX,
                                    suffix=".webp")
    os.close(fd)
    tmp = Path(tmp_name)
    try:
        img.save(tmp, "WEBP", quality=WEBP_QUALITY, method=WEBP_METHOD)
        os.replace(tmp, dest)
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)


def write_derivatives(img, digest, thumbs_root, gallery_root, thumb_px, preview_px):
    """Generate the preview once from the source, then derive the thumbnail from it.

    Resizing the (already down-scaled) preview to the thumbnail avoids a second full-size
    LANCZOS pass over the original — a real saving on 48 MP sources. Both files are written
    atomically so a concurrent duplicate can't corrupt them.
    """
    base = img.convert("RGB")
    out_dir = shard_dir(thumbs_root, digest)

    preview = base.copy()
    preview.thumbnail((preview_px, preview_px), Image.LANCZOS)
    preview_out = out_dir / f"{digest}_p.webp"
    _atomic_save_webp(preview, preview_out)

    thumb = preview.copy()
    thumb.thumbnail((thumb_px, thumb_px), Image.LANCZOS)
    thumb_out = out_dir / f"{digest}_t.webp"
    _atomic_save_webp(thumb, thumb_out)

    return (
        str(thumb_out.relative_to(gallery_root.parent).as_posix()),
        str(preview_out.relative_to(gallery_root.parent).as_posix()),
    )


# --- Capture-date probe (light, used during ingest) --------------------------


def probe_capture_ms(path: Path) -> int:
    """Capture date for the ingest phase, which files originals into ``<source>/YYYY/MM/``.

    Shares the extraction helpers with the indexing phase on purpose: if this disagreed with
    :func:`process_video`, a file would be *filed* under one date and *catalogued* under another.
    """
    ext = path.suffix.lower()
    try:
        if ext in VIDEO_EXTS and have_ffmpeg():
            meta = video_metadata_from_probe(ffprobe_info(path), path)
            if meta["capture_date"] is not None:
                return meta["capture_date"]
        elif ext not in VIDEO_EXTS:
            with Image.open(path) as im:
                meta = photo_metadata_from_exif(im.getexif(), path)
            if meta["capture_date"] is not None:
                return meta["capture_date"]
    except Exception:
        pass
    ms = filename_to_epoch_ms(path.name)
    return ms if ms is not None else int(path.stat().st_mtime_ns / 1_000_000)


# --- Photo / video processing ------------------------------------------------


def process_photo(path, relative_path, size, mtime_ns, digest, thumbs_root,
                gallery_root, thumb_px, preview_px) -> MediaRecord:
    with Image.open(path) as raw:
        meta = photo_metadata_from_exif(raw.getexif(), path, mtime_ns)
        upright = ImageOps.exif_transpose(raw)
        width, height = upright.size
        thumb_rel, preview_rel = write_derivatives(
            upright, digest, thumbs_root, gallery_root, thumb_px, preview_px
        )

    return MediaRecord(
        relative_path=relative_path, filename=path.name, media_type="photo",
        capture_date=meta["capture_date"], width=width, height=height,
        orientation=meta["orientation"],
        mime_type=f"image/{path.suffix.lower().lstrip('.')}", size_bytes=size,
        file_mtime_ns=mtime_ns, content_hash=digest, thumb_path=thumb_rel,
        preview_path=preview_rel, gps_lat=meta["gps_lat"], gps_lon=meta["gps_lon"],
        camera_make=meta["camera_make"], camera_model=meta["camera_model"],
        capture_date_source=meta["capture_date_source"],
    )


def ffprobe_info(path: Path) -> dict:
    cmd = ["ffprobe", "-v", "quiet", "-print_format", "json",
        "-show_format", "-show_streams", str(path)]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError(f"ffprobe failed: {out.stderr.strip()[:200]}")
    return json.loads(out.stdout or "{}")


def _frame_rate(value: str) -> Optional[float]:
    """Parse ffprobe's rational frame-rate form (for example ``30000/1001``)."""
    try:
        numerator, denominator = (value or "").split("/", 1)
        rate = float(numerator) / float(denominator)
        return rate if rate > 0 else None
    except (ValueError, ZeroDivisionError):
        return None


def write_video_proxy(path: Path, digest: str, thumbs_root: Path,
                      gallery_root: Path, source_fps: Optional[float]) -> str:
    """Create an atomically-published, broadly compatible 1080p TV playback proxy.

    Originals can be 4K/60 HEVC, ProRes, or live in containers that cheap Android TV boxes
    cannot decode smoothly. The app always streams this bounded H.264/AAC MP4 instead. Each
    worker gets a unique temp file because byte-identical duplicates intentionally share the
    same content-addressed destination.
    """
    out_dir = shard_dir(thumbs_root, digest)
    dest = out_dir / f"{digest}_v.mp4"
    fd, tmp_name = tempfile.mkstemp(
        dir=str(out_dir), prefix=CATALOG_TMP_PREFIX, suffix=".mp4")
    os.close(fd)
    tmp = Path(tmp_name)
    try:
        filters = [
            "scale="
            f"w='min({VIDEO_PROXY_MAX_WIDTH},iw)':"
            f"h='min({VIDEO_PROXY_MAX_HEIGHT},ih)':"
            "force_original_aspect_ratio=decrease:force_divisible_by=2",
        ]
        if source_fps is not None and source_fps > VIDEO_PROXY_MAX_FPS:
            filters.append(f"fps={VIDEO_PROXY_MAX_FPS:g}")
        cmd = [
            "ffmpeg", "-y", "-loglevel", "error", "-i", str(path),
            "-map", "0:v:0", "-map", "0:a:0?", "-vf", ",".join(filters),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", str(VIDEO_PROXY_CRF),
            "-profile:v", "high", "-level:v", "4.0", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", f"{VIDEO_PROXY_AUDIO_KBPS}k", "-ac", "2",
            "-movflags", "+faststart", "-sn", "-dn", str(tmp),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60 * 60)
        if result.returncode != 0 or not tmp.exists() or tmp.stat().st_size == 0:
            raise RuntimeError(f"video proxy failed: {result.stderr.strip()[:240]}")
        os.replace(tmp, dest)
    finally:
        tmp.unlink(missing_ok=True)
    return str(dest.relative_to(gallery_root.parent).as_posix())


def process_video(path, relative_path, size, mtime_ns, digest, thumbs_root,
                gallery_root, thumb_px, preview_px) -> MediaRecord:
    meta = video_metadata_from_probe(ffprobe_info(path), path, mtime_ns)
    duration_s = meta["duration_s"]
    width, height = meta["width"], meta["height"]
    source_fps = meta["source_fps"]

    seek = max(0.0, duration_s / 2.0) if duration_s else 0.0
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        tmp_path = Path(tmp.name)
    try:
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-ss", f"{seek:.3f}",
            "-i", str(path), "-frames:v", "1", "-q:v", "2", str(tmp_path)]
        subprocess.run(cmd, capture_output=True, timeout=120, check=True)
        with Image.open(tmp_path) as poster:
            if not width or not height:
                width, height = poster.size
            thumb_rel, preview_rel = write_derivatives(
                poster, digest, thumbs_root, gallery_root, thumb_px, preview_px
            )
    finally:
        tmp_path.unlink(missing_ok=True)

    video_rel = write_video_proxy(
        path, digest, thumbs_root, gallery_root, source_fps)

    return MediaRecord(
        relative_path=relative_path, filename=path.name, media_type="video",
        capture_date=meta["capture_date"], width=width, height=height, orientation=1,
        mime_type=f"video/{path.suffix.lower().lstrip('.')}", size_bytes=size,
        file_mtime_ns=mtime_ns, content_hash=digest, thumb_path=thumb_rel,
        preview_path=preview_rel, video_path=video_rel, duration_ms=meta["duration_ms"],
        gps_lat=meta["gps_lat"], gps_lon=meta["gps_lon"],
        camera_make=meta["camera_make"], camera_model=meta["camera_model"],
        capture_date_source=meta["capture_date_source"],
    )


# --- Parallel worker ---------------------------------------------------------

# Task tuple: (path, rel, size, mtime_ns, is_video, thumbs_root, gallery_root, thumb, preview)
Task = Tuple[str, str, int, int, bool, str, str, int, int]


def _process_one(task: Task):
    """Runs in a worker process: hash, decode, write derivatives, extract metadata.

    Returns (True, MediaRecord, None) on success or (False, rel, message) on failure.
    All heavy per-file work (HEIC decode, resize, WebP encode, SMB writes) happens here.
    """
    (path_str, rel, size, mtime_ns, is_video,
    thumbs_root_str, gallery_root_str, thumb_px, preview_px) = task
    path = Path(path_str)
    thumbs_root = Path(thumbs_root_str)
    gallery_root = Path(gallery_root_str)
    try:
        digest = content_hash(path, size)
        if is_video:
            rec = process_video(path, rel, size, mtime_ns, digest, thumbs_root,
                                gallery_root, thumb_px, preview_px)
        else:
            rec = process_photo(path, rel, size, mtime_ns, digest, thumbs_root,
                                gallery_root, thumb_px, preview_px)
        return (True, rec, None)
    except Exception as e:
        return (False, rel, f"{type(e).__name__}: {str(e)[:160]}")


# --- Database ----------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS photos (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    relative_path  TEXT NOT NULL UNIQUE,
    filename       TEXT NOT NULL,
    media_type     TEXT NOT NULL DEFAULT 'photo',
    capture_date   INTEGER NOT NULL,
    width          INTEGER NOT NULL DEFAULT 0,
    height         INTEGER NOT NULL DEFAULT 0,
    orientation    INTEGER NOT NULL DEFAULT 1,
    mime_type      TEXT,
    size_bytes     INTEGER NOT NULL DEFAULT 0,
    file_mtime_ns  INTEGER NOT NULL DEFAULT 0,
    content_hash   TEXT,
    thumb_path     TEXT NOT NULL,
    preview_path   TEXT NOT NULL,
    video_path     TEXT,
    duration_ms    INTEGER,
    gps_lat        REAL,
    gps_lon        REAL,
    place_city     TEXT,
    place_country  TEXT,
    camera_make    TEXT,
    camera_model   TEXT,
    -- Where capture_date came from (CAPTURE_SOURCE_*). Additive and indexer-only: the app's
    -- validator checks that required columns are PRESENT, not that no others are, so this needs
    -- no SCHEMA_VERSION bump and no coordinated APK release. See ADDITIVE_PHOTO_COLUMNS.
    capture_date_source TEXT
);

CREATE TABLE IF NOT EXISTS albums (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT NOT NULL,              -- display label (may repeat across types)
    type           TEXT NOT NULL,              -- 'place' | 'camera' | 'year' | 'trip'
    album_key      TEXT NOT NULL,              -- stable identity within a type
    cover_photo_id INTEGER,
    photo_count    INTEGER NOT NULL DEFAULT 0,
    sort_order     INTEGER NOT NULL DEFAULT 0,
    -- Identity is (type, album_key), NOT (type, name): a place key embeds the country so
    -- two different cities that share a name (e.g. Springfield US vs AU) never merge.
    UNIQUE(type, album_key)
);

CREATE TABLE IF NOT EXISTS photo_albums (
    photo_id INTEGER NOT NULL,
    album_id INTEGER NOT NULL,
    capture_date INTEGER NOT NULL,
    PRIMARY KEY (photo_id, album_id)
);

CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);

-- Composite indexes matching the app's exact ORDER BY / JOIN shapes.
CREATE INDEX IF NOT EXISTS idx_photos_capture_id
    ON photos(capture_date DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_photo_albums_album_capture
    ON photo_albums(album_id, capture_date DESC, photo_id DESC);
"""


# Columns added to `photos` without a SCHEMA_VERSION bump, because they are purely additive and
# nothing in the app reads them. `CREATE TABLE IF NOT EXISTS` is a no-op on a work DB that already
# exists at the current version, so each one needs an explicit ALTER to appear there.
ADDITIVE_PHOTO_COLUMNS: Tuple[Tuple[str, str], ...] = (
    ("capture_date_source", "TEXT"),
)


def migrate_additive_columns(conn) -> List[str]:
    """Add any missing additive `photos` column in place. Returns the columns added."""
    present = {r[1] for r in conn.execute("PRAGMA table_info(photos)")}
    added = []
    for name, decl in ADDITIVE_PHOTO_COLUMNS:
        if name not in present:
            conn.execute(f"ALTER TABLE photos ADD COLUMN {name} {decl}")
            added.append(name)
    return added


def open_db(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA journal_mode=WAL")
    # If an older schema is present, drop the derived tables and rebuild from scratch.
    version = 0
    try:
        row = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
        version = int(row[0]) if row else 0
    except sqlite3.OperationalError:
        version = 0
    if version and version < SCHEMA_VERSION:
        log(f"schema v{version} -> v{SCHEMA_VERSION}: rebuilding catalog tables")
        for tbl in ("photo_albums", "albums", "photos"):
            conn.execute(f"DROP TABLE IF EXISTS {tbl}")
    conn.executescript(SCHEMA)
    added = migrate_additive_columns(conn)
    if added:
        log(f"schema: added photos column(s) {', '.join(added)} (no version bump needed)")
    conn.commit()
    return conn


def load_signatures(conn) -> Dict[str, Tuple[int, int]]:
    rows = conn.execute("SELECT relative_path, size_bytes, file_mtime_ns FROM photos")
    return {r[0]: (r[1], r[2]) for r in rows}


def load_hashes(conn) -> Set[str]:
    return {r[0] for r in conn.execute(
        "SELECT content_hash FROM photos WHERE content_hash IS NOT NULL")}


def load_gps(conn) -> Dict[str, Tuple[Optional[float], Optional[float]]]:
    """Prior (lat, lon) per relative_path, so we can detect coordinate changes on update."""
    return {r[0]: (r[1], r[2])
            for r in conn.execute("SELECT relative_path, gps_lat, gps_lon FROM photos")}


def upsert(conn, rec: MediaRecord) -> None:
    conn.execute(
        """
        INSERT INTO photos (relative_path, filename, media_type, capture_date,
            width, height, orientation, mime_type, size_bytes, file_mtime_ns,
            content_hash, thumb_path, preview_path, video_path, duration_ms, gps_lat,
            gps_lon, camera_make, camera_model, capture_date_source)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(relative_path) DO UPDATE SET
            filename=excluded.filename, media_type=excluded.media_type,
            capture_date=excluded.capture_date, width=excluded.width,
            height=excluded.height, orientation=excluded.orientation,
            mime_type=excluded.mime_type, size_bytes=excluded.size_bytes,
            file_mtime_ns=excluded.file_mtime_ns, content_hash=excluded.content_hash,
            thumb_path=excluded.thumb_path, preview_path=excluded.preview_path,
            video_path=excluded.video_path, duration_ms=excluded.duration_ms,
            gps_lat=excluded.gps_lat,
            gps_lon=excluded.gps_lon, camera_make=excluded.camera_make,
            camera_model=excluded.camera_model,
            capture_date_source=excluded.capture_date_source
        """,
        (rec.relative_path, rec.filename, rec.media_type, rec.capture_date, rec.width,
        rec.height, rec.orientation, rec.mime_type, rec.size_bytes, rec.file_mtime_ns,
        rec.content_hash, rec.thumb_path, rec.preview_path, rec.video_path, rec.duration_ms,
        rec.gps_lat, rec.gps_lon, rec.camera_make, rec.camera_model, rec.capture_date_source),
    )


# --- Ingest phase ------------------------------------------------------------


def iter_media(root: Path, skip_names: Set[str]) -> Iterable[Path]:
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in skip_names and not d.startswith(".")]
        for name in files:
            # Skip hidden files and macOS AppleDouble sidecars (._foo.jpg, .DS_Store).
            if name.startswith("."):
                continue
            ext = Path(name).suffix.lower()
            if ext in PHOTO_EXTS or ext in VIDEO_EXTS:
                yield Path(base) / name


def unique_path(dest: Path) -> Path:
    if not dest.exists():
        return dest
    stem, suffix = os.path.splitext(dest.name)
    i = 1
    while True:
        cand = dest.with_name(f"{stem} ({i}){suffix}")
        if not cand.exists():
            return cand
        i += 1


def is_settled(path: Path, settle_s: float, now: Optional[float] = None) -> bool:
    """True once ``path`` has gone ``settle_s`` seconds without a write.

    Guards ingest against a file that is still being copied in over SMB: moving it would
    "verify" the partial copy against the equally partial source and then delete the
    source. ``st_ctime`` is included because a client that preserves the original
    timestamps (Finder does) sets an old ``st_mtime`` on the half-written file, while the
    change time always moves with every write and attribute update.
    """
    if settle_s <= 0:
        return True
    st = path.stat()
    last_change = max(st.st_mtime, st.st_ctime)
    return (time.time() if now is None else now) - last_change >= settle_s


def safe_move(src: Path, dest: Path) -> None:
    """Copy -> verify size -> atomic rename -> remove original. Never loses the file."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    shutil.copy2(src, tmp)  # copy2 preserves mtime
    if tmp.stat().st_size != src.stat().st_size:
        tmp.unlink(missing_ok=True)
        raise IOError(f"size mismatch copying {src}")
    os.replace(tmp, dest)
    src.unlink()


def run_ingest(source: Path, import_dir: str, existing_hashes: Set[str],
            stats: Stats, settle_s: float = 0.0) -> None:
    idir = source / import_dir
    if not idir.is_dir():
        return
    log(f"Ingest: scanning {idir} …")
    dup_dir = idir / DUPLICATES_DIRNAME
    for path in iter_media(idir, skip_names={DUPLICATES_DIRNAME}):
        try:
            if not is_settled(path, settle_s):
                stats.import_waiting += 1
                continue
            size = path.stat().st_size
            digest = content_hash(path, size)
            if digest in existing_hashes:
                dest = unique_path(dup_dir / path.name)
                safe_move(path, dest)
                stats.import_dupes += 1
                continue
            capture_ms = probe_capture_ms(path)
            dt = datetime.fromtimestamp(capture_ms / 1000)
            dest = unique_path(source / f"{dt.year:04d}" / f"{dt.month:02d}" / path.name)
            safe_move(path, dest)
            existing_hashes.add(digest)
            stats.imported += 1
        except Exception as e:
            log(f"  ! ingest failed {path.name}: {type(e).__name__}: {str(e)[:140]}")
            stats.failed += 1
    if stats.imported or stats.import_dupes:
        log(f"  imported={stats.imported} duplicates_set_aside={stats.import_dupes}")
    if stats.import_waiting:
        log(f"  {stats.import_waiting} file(s) changed in the last {settle_s:g}s — "
            "left in place until the copy settles")


# --- Geocoding + album building ----------------------------------------------


def invalidate_stale_places(conn) -> int:
    """Clear place labels that can no longer be valid.

    A photo with no coordinates must not keep a city/country from a previous run (e.g. its
    GPS was stripped, or an edited file replaced a geotagged one). Coordinate *changes* on
    re-index are handled at apply time by nulling the place so it re-geocodes here.
    """
    cur = conn.execute(
        "UPDATE photos SET place_city=NULL, place_country=NULL "
        "WHERE (gps_lat IS NULL OR gps_lon IS NULL) "
        "AND (place_city IS NOT NULL OR place_country IS NOT NULL)"
    )
    return cur.rowcount


def geocode_places(conn) -> int:
    # Only rows that have coordinates but no resolved place yet — freshly added photos and
    # those whose coordinates changed (place was invalidated at apply time).
    rows = conn.execute(
        "SELECT id, gps_lat, gps_lon FROM photos "
        "WHERE gps_lat IS NOT NULL AND gps_lon IS NOT NULL AND place_city IS NULL"
    ).fetchall()
    if not rows:
        return 0
    try:
        import reverse_geocoder as rg
        import pycountry
    except ImportError:
        log("  ! reverse_geocoder/pycountry not installed — skipping Places albums")
        return 0

    coords = [(r[1], r[2]) for r in rows]
    results = rg.search(coords, mode=1)  # single-threaded; returns one dict per coord
    updated = 0
    for (pid, _, _), res in zip(rows, results):
        city = _clean(res.get("name"))
        cc = res.get("cc")
        country = None
        if cc:
            c = pycountry.countries.get(alpha_2=cc)
            country = c.name if c else cc
        conn.execute(
            "UPDATE photos SET place_city=?, place_country=? WHERE id=?",
            (city, country, pid),
        )
        updated += 1
    return updated


def _desired_albums(rows) -> Tuple[Dict[Tuple[str, str], str], Dict[Tuple[str, str], List[int]]]:
    """Pure derivation of the album set from photo rows.

    Returns (names, memberships) keyed by (type, album_key). Place identity embeds the
    country so same-named cities in different countries stay distinct; the display name is
    disambiguated with the country only when a city name actually recurs across countries.

    rows: iterable of (photo_id, capture_ms, camera_model, place_city, place_country).
    """
    names: Dict[Tuple[str, str], str] = {}
    members: Dict[Tuple[str, str], List[int]] = defaultdict(list)
    place_countries: Dict[str, Set[str]] = defaultdict(set)
    place_rows: List[Tuple[int, str, Optional[str]]] = []

    for pid, capture_ms, model, city, country in rows:
        year = str(datetime.fromtimestamp(capture_ms / 1000).year)
        key = ("year", year)
        names[key] = year
        members[key].append(pid)

        model = _clean(model)
        if model:
            key = ("camera", model)
            names[key] = model
            members[key].append(pid)

        city = _clean(city)
        if city:
            country = _clean(country)
            akey = f"{city}{_KEY_SEP}{country or ''}"
            key = ("place", akey)
            names[key] = city  # provisional; disambiguated below
            members[key].append(pid)
            place_countries[city].add(country or "")
            place_rows.append((pid, akey, country))

    # Disambiguate place display names only where the same city name spans >1 country.
    for pid, akey, country in place_rows:
        key = ("place", akey)
        city = akey.split(_KEY_SEP, 1)[0]
        if country and len(place_countries[city]) > 1:
            names[key] = f"{city} · {country}"

    return names, members


def _trip_albums(rows) -> Tuple[Dict[Tuple[str, str], str], Dict[Tuple[str, str], List[int]]]:
    """Derive 'trip' albums: clusters of away-from-home photos that are contiguous in time.

    Home is the single most-photographed place; any photo elsewhere counts as "away". Away photos
    are ordered by time and split into trips wherever more than `TRIP_GAP_MS` passes between two
    consecutive ones. A cluster is kept only when it holds >= `TRIP_MIN_PHOTOS` across >=
    `TRIP_MIN_DAYS` calendar days, so a lone lunch in the next town doesn't become a trip.

    Each trip is named "<city>, <month> <year>" from its dominant place and start date, and keyed by
    (start-day, dominant place) so its album id stays stable across re-indexes (matching the
    reconcile contract in `build_albums`).

    rows: iterable of (photo_id, capture_ms, camera_model, place_city, place_country).
    Returns (names, members) keyed by (type, album_key), same shape as `_desired_albums`.
    """
    geo: List[Tuple[int, int, str, Optional[str]]] = []
    for pid, capture_ms, _model, city, country in rows:
        city = _clean(city)
        if city:
            geo.append((pid, capture_ms, city, _clean(country)))
    if not geo:
        return {}, {}

    home = Counter((city, country) for _, _, city, country in geo).most_common(1)[0][0]
    away = sorted((g for g in geo if (g[2], g[3]) != home), key=lambda g: g[1])

    names: Dict[Tuple[str, str], str] = {}
    members: Dict[Tuple[str, str], List[int]] = {}

    def flush(cluster: List[Tuple[int, int, str, Optional[str]]]) -> None:
        if len(cluster) < TRIP_MIN_PHOTOS:
            return
        days = {datetime.fromtimestamp(g[1] / 1000).date() for g in cluster}
        if len(days) < TRIP_MIN_DAYS:
            return
        dom_city, dom_country = Counter((g[2], g[3]) for g in cluster).most_common(1)[0][0]
        start = datetime.fromtimestamp(cluster[0][1] / 1000)
        akey = f"{start:%Y%m%d}{_KEY_SEP}{dom_city}{_KEY_SEP}{dom_country or ''}"
        key = ("trip", akey)
        names[key] = f"{dom_city}, {_RU_MONTHS[start.month - 1]} {start.year}"
        members[key] = [g[0] for g in cluster]

    cluster: List[Tuple[int, int, str, Optional[str]]] = []
    for g in away:
        if cluster and g[1] - cluster[-1][1] > TRIP_GAP_MS:
            flush(cluster)
            cluster = []
        cluster.append(g)
    flush(cluster)
    return names, members


def build_albums(conn) -> None:
    """Reconcile auto-albums (Places/Cameras/Years/Trips) and memberships from photos.

    Albums are a pure function of the photo rows, but we *reconcile* rather than drop-and-
    recreate: albums keyed by (type, album_key) that still exist keep their row id, so the
    catalog fingerprint (and the app's focus/scroll identity) doesn't churn when nothing
    about a given album changed. Only truly new albums get new ids.
    """
    rows = conn.execute(
        "SELECT id, capture_date, camera_model, place_city, place_country FROM photos"
    ).fetchall()
    names, members = _desired_albums(rows)
    # Trips share the same (type, album_key) namespace; keys are disjoint from place/camera/year.
    trip_names, trip_members = _trip_albums(rows)
    names.update(trip_names)
    members.update(trip_members)

    existing = {
        (t, k): aid
        for aid, t, k in conn.execute("SELECT id, type, album_key FROM albums")
    }

    # Drop albums that no longer have any desired identity.
    for (t, k), aid in existing.items():
        if (t, k) not in names:
            conn.execute("DELETE FROM albums WHERE id=?", (aid,))

    # Upsert desired albums, preserving ids for ones that already exist.
    album_id: Dict[Tuple[str, str], int] = {}
    for (t, k), name in names.items():
        aid = existing.get((t, k))
        if aid is None:
            cur = conn.execute(
                "INSERT INTO albums(name, type, album_key) VALUES (?, ?, ?)",
                (name, t, k),
            )
            aid = cur.lastrowid
        else:
            conn.execute("UPDATE albums SET name=? WHERE id=?", (name, aid))
        album_id[(t, k)] = aid

    conn.execute("DELETE FROM photo_albums")
    capture_by_id = {row[0]: row[1] for row in rows}
    memberships = [
        (pid, album_id[key], capture_by_id[pid])
        for key, pids in members.items() for pid in pids
    ]
    conn.executemany(
        "INSERT OR IGNORE INTO photo_albums(photo_id, album_id, capture_date) VALUES (?, ?, ?)",
        memberships,
    )

    # Counts + covers (most recent photo in each album).
    conn.execute(
        """UPDATE albums SET photo_count = (
            SELECT COUNT(*) FROM photo_albums WHERE photo_albums.album_id = albums.id)"""
    )
    conn.execute(
        """UPDATE albums SET cover_photo_id = (
            SELECT p.id FROM photos p
            JOIN photo_albums pa ON pa.photo_id = p.id
            WHERE pa.album_id = albums.id
            ORDER BY p.capture_date DESC LIMIT 1)"""
    )
    conn.execute("DELETE FROM albums WHERE photo_count = 0")

    # Sort order: years newest-first; places/cameras by popularity.
    conn.execute(
        "UPDATE albums SET sort_order = 9999 - CAST(name AS INTEGER) WHERE type='year'"
    )
    for kind in ("place", "camera"):
        ranked = conn.execute(
            "SELECT id FROM albums WHERE type=? ORDER BY photo_count DESC, name ASC",
            (kind,),
        ).fetchall()
        for order, (aid,) in enumerate(ranked):
            conn.execute("UPDATE albums SET sort_order=? WHERE id=?", (order, aid))

    # Trips newest-first: cover_photo_id is each trip's most recent photo, so order by its date.
    trip_ranked = conn.execute(
        "SELECT a.id FROM albums a JOIN photos p ON p.id = a.cover_photo_id "
        "WHERE a.type='trip' ORDER BY p.capture_date DESC"
    ).fetchall()
    for order, (aid,) in enumerate(trip_ranked):
        conn.execute("UPDATE albums SET sort_order=? WHERE id=?", (order, aid))


# --- Atomic catalog publication ----------------------------------------------


def read_manifest(path: Path) -> Optional[dict]:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def write_manifest_atomic(path: Path, data: dict) -> None:
    """Write the manifest via a temp file + atomic replace — never a partial manifest."""
    fd, tmp_name = tempfile.mkstemp(dir=str(path.parent), prefix=CATALOG_TMP_PREFIX,
                                    suffix=".json")
    tmp = Path(tmp_name)
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)


def derivative_params_fingerprint(
    thumb_px: int,
    preview_px: int,
    quality: int = WEBP_QUALITY,
    method: int = WEBP_METHOD,
    fmt: int = DERIVATIVE_FORMAT_VERSION,
) -> str:
    """Fingerprint of everything that determines derivative BYTES for a given source file."""
    raw = (
        f"{fmt}|{thumb_px}|{preview_px}|{quality}|{method}|"
        f"{VIDEO_PROXY_MAX_WIDTH}|{VIDEO_PROXY_MAX_HEIGHT}|{VIDEO_PROXY_MAX_FPS}|"
        f"{VIDEO_PROXY_CRF}|{VIDEO_PROXY_AUDIO_KBPS}"
    )
    return hashlib.sha256(raw.encode()).hexdigest()[:12]


def next_derivative_generation(
    prev_generation: int,
    prev_params: Optional[str],
    new_params: str,
    rebuild: bool,
) -> int:
    """Advance the derivative generation only when cached derivative bytes may be invalid.

    Advances on the first publish, on an explicit --rebuild, or when the derivative params
    fingerprint changes (dimensions / quality / method / format version). Ordinary catalog
    additions and deletions keep the generation stable, so byte-identical existing derivatives
    stay cache hits on the device.
    """
    if prev_generation <= 0:
        return 1
    if rebuild or new_params != prev_params:
        return prev_generation + 1
    return prev_generation


def catalog_fingerprint(conn) -> str:
    """Content fingerprint over the meaningful photo columns (order-stable), plus the album-logic
    version.

    Albums are a pure function of the photo rows *and the derivation code*, so the photo columns
    alone can't detect a no-op: changing how albums are built (a new type, retuned thresholds)
    leaves the photos untouched yet must still republish. Seeding the hash with
    [ALBUMS_LOGIC_VERSION] makes such a change flip the fingerprint exactly once.
    """
    h = hashlib.sha256()
    h.update(f"albums_logic={ALBUMS_LOGIC_VERSION}\x1e".encode("utf-8"))
    for row in conn.execute(
        "SELECT relative_path, content_hash, capture_date, media_type, width, height, "
        "orientation, mime_type, duration_ms, gps_lat, gps_lon, place_city, place_country, "
        "camera_make, camera_model, thumb_path, preview_path, video_path "
        "FROM photos ORDER BY relative_path"
    ):
        h.update(repr(row).encode("utf-8"))
        h.update(b"\x1e")
    return h.hexdigest()


def validate_catalog_file(path: Path) -> Tuple[bool, str]:
    """Structural validation of a snapshot before it is exposed (and reused by tests)."""
    if not path.exists() or path.stat().st_size == 0:
        return False, "missing or empty"
    conn = sqlite3.connect(str(path))
    try:
        if conn.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            return False, "quick_check failed"
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        required = {"photos", "albums", "photo_albums", "meta"}
        missing = required - tables
        if missing:
            return False, f"missing tables: {sorted(missing)}"
        row = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
        if not row or int(row[0]) != SCHEMA_VERSION:
            return False, f"schema_version != {SCHEMA_VERSION}"
        photo_columns = {r[1] for r in conn.execute("PRAGMA table_info(photos)")}
        if "video_path" not in photo_columns:
            return False, "photos missing video_path"
        membership_columns = {
            r[1] for r in conn.execute("PRAGMA table_info(photo_albums)")}
        if "capture_date" not in membership_columns:
            return False, "photo_albums missing capture_date"
        journal_mode = conn.execute("PRAGMA journal_mode").fetchone()[0].lower()
        if journal_mode != "delete":
            return False, f"journal_mode is {journal_mode}, expected delete"
        return True, "ok"
    except sqlite3.DatabaseError as e:
        return False, f"unreadable: {e}"
    finally:
        conn.close()


def cleanup_temp_files(*dirs: Path) -> None:
    """Remove stray temp files left by an interrupted run (safe to call anytime)."""
    for d in dirs:
        if not d.is_dir():
            continue
        for p in d.iterdir():
            if p.name.startswith(CATALOG_TMP_PREFIX):
                p.unlink(missing_ok=True)


def publish_catalog(work_db_path: Path, catalogs_root: Path, catalog_name: str,
                    manifest_path: Path, manifest: dict) -> Path:
    """Snapshot → validate → atomically expose the catalog, then flip the manifest.

    Order matters for crash safety: the versioned catalog file is fully written and renamed
    into place *before* the manifest is atomically replaced to reference it. If the run is
    interrupted at any point before the manifest replace, the previous manifest still points
    at the previous (intact) catalog. The manifest replace is the single commit point.
    """
    catalogs_root.mkdir(parents=True, exist_ok=True)
    final = catalogs_root / catalog_name
    tmp = catalogs_root / f"{CATALOG_TMP_PREFIX}{uuid.uuid4().hex}.db"
    try:
        # Consistent single-file snapshot of the working DB (no WAL sidecars).
        src = sqlite3.connect(str(work_db_path))
        try:
            dst = sqlite3.connect(str(tmp))
            try:
                src.backup(dst)
                # A backup copies the source database header, including its persistent WAL
                # journal mode. Published catalogs must be standalone files: switch the snapshot
                # to DELETE mode before validation/publication so readers never need a matching
                # -wal/-shm pair.
                mode = dst.execute("PRAGMA journal_mode=DELETE").fetchone()[0].lower()
                if mode != "delete":
                    raise RuntimeError(f"could not normalize snapshot journal mode: {mode}")
            finally:
                dst.close()
        finally:
            src.close()

        ok, reason = validate_catalog_file(tmp)
        if not ok:
            raise RuntimeError(f"refusing to publish invalid snapshot: {reason}")

        os.replace(tmp, final)              # expose the immutable catalog
        write_manifest_atomic(manifest_path, manifest)  # commit point
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)
    return final


def gc_catalogs(catalogs_root: Path, keep: Set[str]) -> int:
    """Delete published catalogs not in ``keep`` (current + retained previous)."""
    removed = 0
    if not catalogs_root.is_dir():
        return 0
    for p in catalogs_root.iterdir():
        if p.name.startswith(CATALOG_PREFIX) and p.name.endswith(".db") and p.name not in keep:
            p.unlink(missing_ok=True)
            removed += 1
    return removed


def collect_referenced_derivatives(
    catalog_files: Iterable[Path], source: Path
) -> Tuple[Set[Path], List[str]]:
    """Union of derivative paths referenced by every given catalog.

    Returns (referenced, unreadable) where ``unreadable`` names catalogs that could not be
    read. Callers must treat a non-empty ``unreadable`` as "cannot safely GC" — deleting a
    derivative that an unreadable retained catalog might reference would corrupt it.
    """
    referenced: Set[Path] = set()
    unreadable: List[str] = []
    for cf in catalog_files:
        if not cf.exists():
            continue
        try:
            conn = sqlite3.connect(str(cf))
            try:
                columns = {row[1] for row in conn.execute("PRAGMA table_info(photos)")}
                for col in ("thumb_path", "preview_path", "video_path"):
                    if col not in columns:  # retained pre-v4 catalogs have no video_path
                        continue
                    for (rel_path,) in conn.execute(f"SELECT {col} FROM photos"):
                        if rel_path:
                            referenced.add((source / rel_path).resolve())
            finally:
                conn.close()
        except sqlite3.DatabaseError as e:
            unreadable.append(f"{cf.name}: {e}")
    return referenced, unreadable


def gc_orphan_derivatives(referenced: Set[Path], thumbs_root: Path) -> int:
    """Delete generated media no longer referenced by the catalog. Never touches originals.

    ``referenced`` is the set of absolute derivative paths still named by photo rows. Only
    files under ``thumbs_root`` are considered, so originals elsewhere are never at risk.
    """
    removed = 0
    if not thumbs_root.is_dir():
        return 0
    for base, _dirs, files in os.walk(thumbs_root):
        for name in files:
            p = Path(base) / name
            if name.startswith(CATALOG_TMP_PREFIX):
                p.unlink(missing_ok=True)
                continue
            if name.endswith((".webp", ".mp4")) and p.resolve() not in referenced:
                p.unlink(missing_ok=True)
                removed += 1
    return removed


# --- Run serialization -------------------------------------------------------


class LockError(RuntimeError):
    """Raised when the library lock can't be acquired. ``kind`` distinguishes the reason:
    'contended' (another run holds it) vs 'unsupported'/'permission'/'other' (flock failed for
    a non-contention reason). ``contended`` is a convenience flag for the common case.
    """

    def __init__(self, message: str, kind: str = "contended"):
        super().__init__(message)
        self.kind = kind

    @property
    def contended(self) -> bool:
        return self.kind == "contended"


_EOPNOTSUPP = getattr(errno, "EOPNOTSUPP", errno.ENOTSUP)


def classify_lock_error(errno_value: int) -> str:
    """Classify an flock ``OSError.errno``.

    'contended'  → non-blocking lock is held by another run (EWOULDBLOCK / EAGAIN).
    'unsupported'→ the filesystem doesn't implement flock (ENOTSUP / EOPNOTSUPP) — common on
                   some network mounts; real mounted-SMB semantics must be verified in-situ.
    'permission' → EACCES / EPERM.
    'other'      → anything else.
    """
    if errno_value in (errno.EWOULDBLOCK, errno.EAGAIN):
        return "contended"
    if errno_value in (errno.ENOTSUP, _EOPNOTSUPP):
        return "unsupported"
    if errno_value in (errno.EACCES, errno.EPERM):
        return "permission"
    return "other"


class LibraryLock:
    """Exclusive, advisory library-level lock (``flock`` on ``.gallery/index.lock``).

    Suitable for the documented macOS/Linux, locally-mounted-library workflow. Held for the
    entire run — cleanup, ingest, work-DB mutation, publication, and GC — so two concurrent
    runs can't share the work DB, allocate the same revision, or delete each other's temps.

    On ANY failure to acquire (contention OR a non-contention flock error such as an
    unsupported filesystem or a permission problem) it raises before any cleanup/mutation, so
    the run never proceeds without real mutual exclusion. Note: flock semantics on mounted SMB
    shares are not guaranteed and must be verified on the actual target storage.
    """

    def __init__(self, gallery_root: Path):
        self.path = gallery_root / LOCK_NAME
        self._fd: Optional[int] = None

    def acquire(self) -> "LibraryLock":
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(str(self.path), os.O_CREAT | os.O_RDWR, 0o644)
        except OSError as e:
            raise self._lock_error(e) from e
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as e:
            os.close(fd)
            raise self._lock_error(e) from e
        self._fd = fd
        try:
            os.ftruncate(fd, 0)
            os.write(fd, f"{os.getpid()}\n".encode())  # informational only
        except OSError:
            pass
        return self

    def _lock_error(self, error: OSError) -> LockError:
        errno_value = error.errno if error.errno is not None else -1
        kind = classify_lock_error(errno_value)
        name = errno.errorcode.get(errno_value, str(errno_value))
        detail = os.strerror(errno_value) if errno_value >= 0 else str(error)
        if kind == "contended":
            return LockError(
                f"another indexer run is active for this library (lock: {self.path})",
                kind=kind)
        return LockError(
            f"could not acquire library lock [{kind}] ({name}: {detail}); "
            f"refusing to run to avoid concurrent corruption (lock: {self.path})",
            kind=kind)

    def release(self) -> None:
        if self._fd is None:
            return
        try:
            fcntl.flock(self._fd, fcntl.LOCK_UN)
        finally:
            os.close(self._fd)
            self._fd = None

    def __enter__(self) -> "LibraryLock":
        return self.acquire()

    def __exit__(self, *exc) -> None:
        self.release()


# --- Orchestration -----------------------------------------------------------


def run(args: argparse.Namespace) -> int:
    source = Path(args.source).expanduser().resolve()
    if not source.is_dir():
        log(f"error: source is not a directory: {source}")
        return 2

    gallery_root = source / GALLERY_DIRNAME
    gallery_root.mkdir(parents=True, exist_ok=True)

    lock = LibraryLock(gallery_root)
    try:
        lock.acquire()
    except LockError as e:
        log(f"error: {e}")
        return 3
    try:
        return _run_locked(args, source, gallery_root)
    finally:
        lock.release()


def _run_locked(args: argparse.Namespace, source: Path, gallery_root: Path) -> int:
    thumbs_root = gallery_root / THUMBS_DIRNAME
    thumbs_root.mkdir(parents=True, exist_ok=True)
    catalogs_root = gallery_root / CATALOGS_DIRNAME
    catalogs_root.mkdir(parents=True, exist_ok=True)
    work_db_path = gallery_root / WORK_DB_NAME
    manifest_path = gallery_root / MANIFEST_NAME

    # Sweep any temp files an earlier interrupted run may have left behind.
    cleanup_temp_files(catalogs_root, gallery_root)

    ffmpeg_ok = have_ffmpeg()
    if not ffmpeg_ok:
        log("warning: ffmpeg/ffprobe not found — videos will be skipped.")
    if not HEIF_OK:
        log("warning: pillow-heif not installed — HEIC/HEIF files will be skipped.")

    prev_manifest = read_manifest(manifest_path) or {}
    prev_revision = int(prev_manifest.get("revision", 0) or 0)
    prev_fingerprint = prev_manifest.get("content_fingerprint")
    prev_catalog_name = prev_manifest.get("catalog")
    prev_generation = int(prev_manifest.get("derivative_generation", 0) or 0)
    prev_deriv_params = prev_manifest.get("derivative_params")
    prev_catalog_format = int(prev_manifest.get("catalog_format_version", 0) or 0)
    deriv_params = derivative_params_fingerprint(args.thumb, args.preview)
    # A parameter change means every existing content-addressed path may now contain different
    # bytes. Treat it as an automatic full derivative rebuild; requiring operators to remember
    # --rebuild would otherwise silently keep stale dimensions/encoder output.
    params_changed = bool(prev_catalog_name) and prev_deriv_params != deriv_params
    force_reprocess = args.rebuild or params_changed
    if params_changed:
        log("Derivative settings changed — regenerating thumbnails/previews automatically.")
    if prev_catalog_name and prev_catalog_format != CATALOG_FORMAT_VERSION:
        log("Catalog container format changed — publishing a standalone DELETE-mode snapshot.")

    conn = open_db(work_db_path)
    stats = Stats()
    started = time.time()

    # 1. INGEST
    if not args.no_import:
        run_ingest(source, args.import_dir, load_hashes(conn), stats, args.import_settle)

    # 2. INDEX — collect the files that need (re)processing, then fan out to workers.
    signatures = load_signatures(conn)
    prior_gps = load_gps(conn)
    seen: Set[str] = set()
    skip_names = {GALLERY_DIRNAME, args.import_dir}
    tasks: List[Task] = []

    for path in iter_media(source, skip_names):
        rel = path.relative_to(source).as_posix()
        ext = path.suffix.lower()
        seen.add(rel)
        try:
            st = path.stat()
            size, mtime_ns = st.st_size, st.st_mtime_ns
        except OSError as e:
            log(f"  ! stat failed {rel}: {e}")
            stats.failed += 1
            continue

        prior = signatures.get(rel)
        if prior and prior == (size, mtime_ns) and not force_reprocess:
            stats.skipped += 1
            continue

        is_video = ext in VIDEO_EXTS
        if is_video and not ffmpeg_ok:
            stats.skipped += 1
            continue
        if ext in {".heic", ".heif"} and not HEIF_OK:
            stats.skipped += 1
            continue

        tasks.append((str(path), rel, size, mtime_ns, is_video,
                    str(thumbs_root), str(gallery_root), args.thumb, args.preview))

    if tasks:
        # Memory-aware default; large photos make this decode/encode memory-bound.
        workers = args.workers if args.workers > 0 else default_worker_count(
            os.cpu_count(), total_ram_bytes())
        log(f"Indexing {len(tasks)} files with {workers} workers …")
        done = 0
        with ProcessPoolExecutor(max_workers=workers) as pool:
            for future in as_completed(pool.submit(_process_one, t) for t in tasks):
                ok, payload, message = future.result()
                if ok:
                    rec = payload
                    upsert(conn, rec)
                    if rec.media_type == "video":
                        stats.videos += 1
                    if rec.relative_path in signatures:
                        stats.updated += 1
                        # Coordinate change on an updated file ⇒ re-geocode its place.
                        old = prior_gps.get(rec.relative_path)
                        if old is not None and old != (rec.gps_lat, rec.gps_lon):
                            conn.execute(
                                "UPDATE photos SET place_city=NULL, place_country=NULL "
                                "WHERE relative_path=?", (rec.relative_path,))
                    else:
                        stats.added += 1
                elif payload in signatures:
                    # An existing file failed to re-process: its previous row is kept, but
                    # that means the catalog is now presenting stale metadata for it. Report
                    # loudly rather than silently — do not pass it off as current.
                    log(f"  ! UPDATE FAILED {payload}: {message} (keeping previous, stale entry)")
                    stats.failed_updates += 1
                else:
                    log(f"  ! failed {payload}: {message}")
                    stats.failed += 1
                done += 1
                if done % 25 == 0:
                    log(f"  … {done}/{len(tasks)} indexed")

    for rel in set(signatures.keys()) - seen:
        conn.execute("DELETE FROM photos WHERE relative_path = ?", (rel,))
        stats.pruned += 1

    # 3. PLACES + ALBUMS
    invalidate_stale_places(conn)          # drop places for photos that lost coordinates
    geocoded = geocode_places(conn)
    build_albums(conn)

    photo_count = conn.execute("SELECT COUNT(*) FROM photos").fetchone()[0]
    fingerprint = catalog_fingerprint(conn)
    for k, v in (("schema_version", SCHEMA_VERSION), ("photo_count", photo_count),
                ("content_fingerprint", fingerprint)):
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", (k, str(v)))
    conn.commit()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")

    # Coverage report
    with_gps = conn.execute("SELECT COUNT(*) FROM photos WHERE gps_lat IS NOT NULL").fetchone()[0]
    with_cam = conn.execute("SELECT COUNT(*) FROM photos WHERE camera_model IS NOT NULL").fetchone()[0]
    with_place = conn.execute("SELECT COUNT(*) FROM photos WHERE place_city IS NOT NULL").fetchone()[0]
    album_counts = dict(conn.execute("SELECT type, COUNT(*) FROM albums GROUP BY type").fetchall())
    date_sources = dict(conn.execute(
        "SELECT COALESCE(capture_date_source,'unknown'), COUNT(*) "
        "FROM photos GROUP BY 1").fetchall())
    conn.close()

    # 4. PUBLISH — only if content actually changed (a true no-op keeps the old revision).
    prev_catalog_ok = bool(prev_catalog_name) and (gallery_root / prev_catalog_name).exists()
    is_noop = (
        not force_reprocess
        and fingerprint == prev_fingerprint
        and int(prev_manifest.get("schema_version", 0) or 0) == SCHEMA_VERSION
        and prev_catalog_format == CATALOG_FORMAT_VERSION
        and prev_catalog_ok
    )

    if is_noop:
        catalog_name = prev_catalog_name
        revision = prev_revision
        log(f"No changes — catalog revision {revision} left in place (nothing republished).")
    else:
        revision = prev_revision + 1
        db_version = int(time.time())
        # Derivative generation: advances only when cached derivative bytes may be invalid
        # (first publish / --rebuild / changed dims/quality/method/format). Ordinary content
        # changes keep it stable so the app preserves image cache hits.
        generation = next_derivative_generation(
            prev_generation, prev_deriv_params, deriv_params, force_reprocess)
        catalog_name = f"{CATALOGS_DIRNAME}/{CATALOG_PREFIX}{revision:06d}-{fingerprint[:12]}.db"
        manifest = {
            "schema_version": SCHEMA_VERSION,
            "catalog_format_version": CATALOG_FORMAT_VERSION,
            "revision": revision,
            "catalog": catalog_name,               # path relative to .gallery/
            "content_fingerprint": fingerprint,
            "derivative_generation": generation,   # app keys its image cache on this
            "derivative_params": deriv_params,      # dims/quality/method/format fingerprint
            "db_version": db_version,              # legacy/display only
            "generated_at": datetime.fromtimestamp(db_version).isoformat(),
            "photo_count": photo_count,
            "thumb_px": args.thumb,
            "preview_px": args.preview,
        }
        publish_catalog(work_db_path, catalogs_root,
                        Path(catalog_name).name, manifest_path, manifest)
        log(f"Published catalog revision {revision} (derivative generation {generation}): "
            f"{catalog_name}")

        # 5. GC — only after a successful publish. Never touches originals.
        keep = {Path(catalog_name).name}
        if prev_catalog_name and KEEP_OLD_CATALOGS:
            keep.add(Path(prev_catalog_name).name)
        gc_catalogs(catalogs_root, keep)

        # The derivative keep-set is the UNION of references from EVERY retained catalog, not
        # just the current one — otherwise an updated/deleted photo would let us delete a
        # derivative the retained previous catalog still points at. If any retained catalog is
        # unreadable we skip derivative GC entirely (deleting something it might reference is
        # worse than leaving a few orphans); the current catalog is already published, so this
        # never blocks publication.
        retained = [catalogs_root / name for name in keep]
        referenced, unreadable = collect_referenced_derivatives(retained, source)
        if unreadable:
            log(f"  ! GC: skipping derivative cleanup — unreadable retained catalog(s): "
                f"{', '.join(unreadable)}")
        else:
            gc_orphan_derivatives(referenced, thumbs_root)

    # Legacy pre-v3 in-place catalog is obsolete once we publish versioned catalogs.
    (gallery_root / LEGACY_DB_NAME).unlink(missing_ok=True)

    elapsed = time.time() - started
    log("")
    log(f"Done in {elapsed:.1f}s — {photo_count} items in catalog.")
    if not args.no_import:
        log(f"  ingest: imported={stats.imported} duplicates={stats.import_dupes}")
    log(f"  index:  added={stats.added} updated={stats.updated} skipped={stats.skipped} "
        f"pruned={stats.pruned} failed={stats.failed} failed_updates={stats.failed_updates} "
        f"videos={stats.videos}")
    log(f"  albums: places={album_counts.get('place',0)} "
        f"cameras={album_counts.get('camera',0)} years={album_counts.get('year',0)}")
    log(f"  metadata coverage: GPS {with_gps}/{photo_count} · "
        f"camera {with_cam}/{photo_count} · places resolved {with_place}/{photo_count}")
    log("  capture dates: " + " · ".join(
        f"{src} {n}" for src, n in sorted(date_sources.items(), key=lambda kv: -kv[1])))
    guessed = sum(n for src, n in date_sources.items() if src in CAPTURE_SOURCES_SUSPECT)
    if guessed:
        log(f"  NOTE: {guessed} item(s) have no capture metadata — dated from file mtime, which "
            "for downloaded files is the download time. Run refresh_metadata.py --only-suspect "
            "after fixing the originals.")
    if stats.failed_updates:
        log(f"  WARNING: {stats.failed_updates} existing file(s) failed to re-process; "
            "their catalog entries are stale until the underlying error is fixed.")
    if with_gps == 0 and photo_count:
        log("  note: no GPS found — export 'Unmodified Original' from Apple Photos to "
            "keep location/camera metadata.")
    log(f"  catalog: {gallery_root / catalog_name}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Ingest + index a photo/video library.")
    p.add_argument("--source", "-s", required=True,
                help="Library root (the mounted SMB share).")
    p.add_argument("--import-dir", default=DEFAULT_IMPORT_DIR,
                help=f"Dropbox folder to ingest, relative to source (default: {DEFAULT_IMPORT_DIR}).")
    p.add_argument("--no-import", action="store_true", help="Skip the ingest phase.")
    p.add_argument("--import-settle", type=float, default=0.0, metavar="SECONDS",
                help="Leave Imports files written to in the last SECONDS for a later run, so a "
                    "copy still in progress is never moved. Use it for unattended runs "
                    "(default: 0, ingest everything).")
    p.add_argument("--thumb", type=int, default=320, help="Thumbnail long-edge px.")
    p.add_argument("--preview", type=int, default=1600, help="Preview long-edge px.")
    p.add_argument("--workers", type=int, default=0,
                help="Parallel worker processes (0 = auto: a conservative, memory-aware "
                    "default bounded by CPU count, RAM/1.5GiB per worker, and a hard cap of "
                    f"{MAX_AUTO_WORKERS}). Set an explicit number to override.")
    p.add_argument("--rebuild", action="store_true",
                help="Regenerate everything, ignoring the incremental cache.")
    return p


def main() -> None:
    args = build_parser().parse_args()
    try:
        sys.exit(run(args))
    except KeyboardInterrupt:
        log("\ninterrupted.")
        sys.exit(130)


if __name__ == "__main__":
    main()
