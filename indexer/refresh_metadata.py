#!/usr/bin/env python3
"""Re-read metadata for already-indexed media and republish the catalog.

Companion to ``indexer.py``. The indexer skips any file whose ``(size, mtime_ns)`` signature is
unchanged, so a *code* fix to metadata extraction never reaches files that are already in the
catalog — and ``--rebuild``, which would reach them, also re-encodes every video proxy from
scratch. This tool closes that gap: it re-probes container tags / EXIF only, writes the corrected
columns, rebuilds albums, and publishes a new revision **reusing the existing thumbnails, previews
and video proxies untouched**. No ffmpeg transcoding, no image re-encoding.

Why it exists: videos downloaded from iCloud in "compressed"/optimized form are re-encoded, and
the transcoder rewrites the ``mvhd`` header — so ``creation_time`` becomes the download date while
the true capture instant survives only in ``com.apple.quicktime.creationdate``. A library indexed
before that tag was read has every such video dated to the day it was downloaded.

    # See what would change, touch nothing
    python3 refresh_metadata.py --source /Volumes/Photos --media video --dry-run

    # Apply, then publish a new catalog revision
    python3 refresh_metadata.py --source /Volumes/Photos --media video

    # Only rows whose date was guessed from mtime (needs a prior run to have recorded provenance)
    python3 refresh_metadata.py --source /Volumes/Photos --only-suspect

    # Also move originals into the YYYY/MM folder implied by the corrected date
    python3 refresh_metadata.py --source /Volumes/Photos --media video --refile

Safe to interrupt: the work DB is committed only after the whole scan succeeds, and catalog
publication is the same atomic manifest-flip the indexer uses.
"""
from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from indexer import (
    CATALOG_PREFIX,
    CATALOGS_DIRNAME,
    CAPTURE_SOURCES_SUSPECT,
    CATALOG_FORMAT_VERSION,
    GALLERY_DIRNAME,
    KEEP_OLD_CATALOGS,
    MANIFEST_NAME,
    PHOTO_EXTS,
    SCHEMA_VERSION,
    THUMBS_DIRNAME,
    VIDEO_EXTS,
    WORK_DB_NAME,
    LibraryLock,
    LockError,
    build_albums,
    catalog_fingerprint,
    cleanup_temp_files,
    collect_referenced_derivatives,
    ffprobe_info,
    gc_catalogs,
    gc_orphan_derivatives,
    geocode_places,
    have_ffmpeg,
    invalidate_stale_places,
    log,
    migrate_additive_columns,
    open_db,
    photo_metadata_from_exif,
    publish_catalog,
    read_manifest,
    safe_move,
    unique_path,
    video_metadata_from_probe,
)

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required. Run: pip install -r requirements.txt")

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
    HEIF_OK = True
except ImportError:
    HEIF_OK = False

# Columns this tool is allowed to rewrite. Everything else in `photos` (paths, hashes, sizes,
# derivative locations) describes bytes on disk that we deliberately do not touch.
REFRESHABLE = (
    "capture_date", "capture_date_source", "gps_lat", "gps_lon",
    "camera_make", "camera_model", "duration_ms", "width", "height", "orientation",
)


def _fmt(ms: Optional[int]) -> str:
    if ms is None:
        return "—"
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")


def select_rows(conn, media: str, only_suspect: bool,
                path_prefix: Optional[str], limit: int) -> List[dict]:
    where, params = [], []
    if media == "video":
        where.append("media_type = 'video'")
    elif media == "photo":
        where.append("media_type = 'photo'")
    if only_suspect:
        # A NULL source means the row predates provenance tracking, so it is equally unproven.
        marks = ",".join("?" for _ in CAPTURE_SOURCES_SUSPECT)
        where.append(f"(capture_date_source IS NULL OR capture_date_source IN ({marks}))")
        params.extend(sorted(CAPTURE_SOURCES_SUSPECT))
    if path_prefix:
        where.append("relative_path LIKE ?")
        params.append(f"{path_prefix.rstrip('/')}/%")

    sql = ("SELECT id, relative_path, filename, media_type, capture_date, capture_date_source, "
           "gps_lat, gps_lon, camera_make, camera_model, duration_ms, width, height, orientation "
           "FROM photos")
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY relative_path"
    if limit > 0:
        sql += f" LIMIT {int(limit)}"
    cur = conn.execute(sql, params)
    cols = [c[0] for c in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]


def probe_row(path: Path, media_type: str) -> Optional[dict]:
    """Re-extract metadata from the original. Returns None when it cannot be read.

    ``mtime_ns`` is intentionally NOT passed to the extractors: with no fallback they return
    ``capture_date=None`` when the file genuinely carries no date, and the caller keeps the stored
    value rather than overwriting a possibly-correct date with today's mtime.
    """
    if media_type == "video":
        return video_metadata_from_probe(ffprobe_info(path), path)
    with Image.open(path) as im:
        return photo_metadata_from_exif(im.getexif(), path)


def compute_changes(row: dict, meta: dict) -> Dict[str, object]:
    """Fields whose freshly-probed value differs from what the catalog holds."""
    changes = {}
    for col in REFRESHABLE:
        if col not in meta:
            continue
        new = meta[col]
        # Never clear a stored value because this probe came up empty — a partial read (missing
        # ffprobe, an unreadable EXIF block) must not erase good metadata.
        if new is None:
            continue
        # Zero width/height means ffprobe reported nothing useful, not "the video is 0px wide".
        if col in ("width", "height") and not new:
            continue
        if row.get(col) != new:
            changes[col] = new
    return changes


def refile_destination(source: Path, row: dict, capture_ms: int) -> Optional[Path]:
    """The ``YYYY/MM`` path the corrected date implies, or None if already correct."""
    dt = datetime.fromtimestamp(capture_ms / 1000)
    current = Path(row["relative_path"])
    desired_dir = f"{dt.year:04d}/{dt.month:02d}"
    if current.parent.as_posix() == desired_dir:
        return None
    return source / desired_dir / current.name


def run(args: argparse.Namespace) -> int:
    source = Path(args.source).expanduser().resolve()
    if not source.is_dir():
        log(f"error: source is not a directory: {source}")
        return 2

    gallery_root = source / GALLERY_DIRNAME
    work_db_path = gallery_root / WORK_DB_NAME
    if not work_db_path.exists():
        log(f"error: no work database at {work_db_path} — run indexer.py first.")
        return 2

    manifest_path = gallery_root / MANIFEST_NAME
    prev_manifest = read_manifest(manifest_path)
    if not prev_manifest:
        log(f"error: no readable manifest at {manifest_path} — run indexer.py first.")
        return 2

    if args.media in ("video", "all") and not have_ffmpeg():
        log("error: ffprobe not found — required to re-read video metadata.")
        return 2

    lock = LibraryLock(gallery_root)
    try:
        lock.acquire()
    except LockError as e:
        log(f"error: {e}")
        return 3
    try:
        return _run_locked(args, source, gallery_root, work_db_path,
                           manifest_path, prev_manifest)
    finally:
        lock.release()


def _run_locked(args, source: Path, gallery_root: Path, work_db_path: Path,
                manifest_path: Path, prev_manifest: dict) -> int:
    catalogs_root = gallery_root / CATALOGS_DIRNAME
    thumbs_root = gallery_root / THUMBS_DIRNAME
    cleanup_temp_files(catalogs_root, gallery_root)

    started = time.time()
    conn = open_db(work_db_path)
    migrate_additive_columns(conn)

    rows = select_rows(conn, args.media, args.only_suspect, args.path_prefix, args.limit)
    if not rows:
        log("Nothing matched the selection — nothing to do.")
        conn.close()
        return 0
    log(f"Re-reading metadata for {len(rows)} item(s) "
        f"[media={args.media}{' only-suspect' if args.only_suspect else ''}]"
        f"{' (dry run)' if args.dry_run else ''} …")

    updated = date_changed = unreadable = missing = refiled = 0
    moves: List[Tuple[int, str, Path]] = []   # (id, new relative_path, absolute destination)

    for i, row in enumerate(rows, 1):
        path = source / row["relative_path"]
        if not path.exists():
            log(f"  ! missing original: {row['relative_path']}")
            missing += 1
            continue
        ext = path.suffix.lower()
        if ext in {".heic", ".heif"} and not HEIF_OK:
            continue
        if row["media_type"] != "video" and ext not in PHOTO_EXTS and ext not in VIDEO_EXTS:
            continue
        try:
            meta = probe_row(path, row["media_type"])
        except Exception as e:
            log(f"  ! unreadable {row['relative_path']}: {type(e).__name__}: {str(e)[:120]}")
            unreadable += 1
            continue

        changes = compute_changes(row, meta)

        if "capture_date" in changes:
            date_changed += 1
            if args.verbose or date_changed <= args.show:
                log(f"    {row['relative_path']}: {_fmt(row['capture_date'])}"
                    f" -> {_fmt(changes['capture_date'])}"
                    f"  [{row['capture_date_source'] or 'unknown'}"
                    f" -> {changes.get('capture_date_source', '?')}]")

        # Re-filing keys off the EFFECTIVE date (corrected if it changed, stored otherwise), not
        # off "did the date change". A file already carrying the right date can still sit in the
        # wrong YYYY/MM folder — that is precisely the state left behind when the date is fixed
        # in one run and --refile is passed in a later one.
        effective_ms = changes.get("capture_date", row["capture_date"])
        if args.refile and effective_ms is not None:
            dest = refile_destination(source, row, effective_ms)
            if dest is not None:
                moves.append((row["id"], dest, path))

        if not changes:
            continue
        updated += 1

        if not args.dry_run:
            assignments = ", ".join(f"{c} = ?" for c in changes)
            conn.execute(f"UPDATE photos SET {assignments} WHERE id = ?",
                         [*changes.values(), row["id"]])

        if i % 500 == 0:
            log(f"  … {i}/{len(rows)}")

    # Re-file originals only after every probe succeeded, so a mid-scan failure never leaves the
    # library half-reorganised. Moves preserve mtime, so the (size, mtime_ns) signature stays
    # valid and the next indexer.py run still treats these files as unchanged.
    if moves and not args.dry_run:
        for photo_id, dest, src_path in moves:
            try:
                final = unique_path(dest)
                final.parent.mkdir(parents=True, exist_ok=True)
                safe_move(src_path, final)
                rel = final.relative_to(source).as_posix()
                conn.execute(
                    "UPDATE photos SET relative_path = ?, filename = ? WHERE id = ?",
                    (rel, final.name, photo_id))
                refiled += 1
            except Exception as e:
                log(f"  ! refile failed {src_path.name}: {type(e).__name__}: {str(e)[:120]}")
    elif moves:
        refiled = len(moves)
        for _, dest, src_path in moves[: args.show]:
            log(f"    would move {src_path.relative_to(source)} -> "
                f"{Path(dest).relative_to(source)}")

    if args.dry_run:
        conn.rollback()
        conn.close()
        log("")
        log(f"Dry run — nothing written. {updated} row(s) would change "
            f"({date_changed} capture date(s)"
            f"{f', {refiled} file(s) re-filed' if args.refile else ''}); "
            f"unreadable={unreadable} missing={missing}")
        return 0

    # A pure re-file changes no metadata column but does change relative_path, which the app
    # resolves originals by — so it still has to be published.
    if updated == 0 and refiled == 0:
        conn.close()
        log("All metadata already current — catalog left untouched.")
        return 0

    # Coordinates may have appeared or moved, so stale reverse-geocodes must go before albums are
    # rebuilt; year/trip albums are a pure function of the (now corrected) capture dates.
    invalidate_stale_places(conn)
    geocode_places(conn)
    build_albums(conn)

    photo_count = conn.execute("SELECT COUNT(*) FROM photos").fetchone()[0]
    fingerprint = catalog_fingerprint(conn)
    for k, v in (("schema_version", SCHEMA_VERSION), ("photo_count", photo_count),
                 ("content_fingerprint", fingerprint)):
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", (k, str(v)))
    conn.commit()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    date_sources = dict(conn.execute(
        "SELECT COALESCE(capture_date_source,'unknown'), COUNT(*) FROM photos GROUP BY 1"))
    conn.close()

    if fingerprint == prev_manifest.get("content_fingerprint"):
        log("Fingerprint unchanged — nothing the app reads actually differs; not republishing.")
        return 0

    revision = int(prev_manifest.get("revision", 0) or 0) + 1
    db_version = int(time.time())
    catalog_name = f"{CATALOGS_DIRNAME}/{CATALOG_PREFIX}{revision:06d}-{fingerprint[:12]}.db"
    # Carry the derivative fields through VERBATIM. This tool never regenerates a thumbnail,
    # preview or proxy, so advancing derivative_generation would pointlessly invalidate every
    # cached image on the TV — the whole point of refreshing metadata separately.
    manifest = {
        **prev_manifest,
        "schema_version": SCHEMA_VERSION,
        "catalog_format_version": CATALOG_FORMAT_VERSION,
        "revision": revision,
        "catalog": catalog_name,
        "content_fingerprint": fingerprint,
        "db_version": db_version,
        "generated_at": datetime.fromtimestamp(db_version).isoformat(),
        "photo_count": photo_count,
    }
    publish_catalog(work_db_path, catalogs_root,
                    Path(catalog_name).name, manifest_path, manifest)
    log(f"Published catalog revision {revision} "
        f"(derivative generation {manifest.get('derivative_generation')} — unchanged): "
        f"{catalog_name}")

    keep = {Path(catalog_name).name}
    prev_catalog_name = prev_manifest.get("catalog")
    if prev_catalog_name and KEEP_OLD_CATALOGS:
        keep.add(Path(prev_catalog_name).name)
    gc_catalogs(catalogs_root, keep)
    referenced, unreadable_catalogs = collect_referenced_derivatives(
        [catalogs_root / name for name in keep], source)
    if unreadable_catalogs:
        log("  ! GC: skipping derivative cleanup — unreadable retained catalog(s): "
            f"{', '.join(unreadable_catalogs)}")
    else:
        gc_orphan_derivatives(referenced, thumbs_root)

    log("")
    log(f"Done in {time.time() - started:.1f}s — {updated} row(s) updated, "
        f"{date_changed} capture date(s) corrected, {refiled} file(s) re-filed, "
        f"unreadable={unreadable} missing={missing}")
    log("  capture dates: " + " · ".join(
        f"{src} {n}" for src, n in sorted(date_sources.items(), key=lambda kv: -kv[1])))
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Re-read metadata for already-indexed media and republish the catalog. "
                    "Never regenerates thumbnails, previews or video proxies.")
    p.add_argument("--source", "-s", required=True, help="Library root (the mounted SMB share).")
    p.add_argument("--media", choices=("video", "photo", "all"), default="all",
                   help="Which media to re-read (default: all). Use 'video' after an "
                        "iCloud-download date problem — photos keep EXIF far more reliably.")
    p.add_argument("--only-suspect", action="store_true",
                   help="Only rows whose capture date was guessed (source 'mtime' or unrecorded).")
    p.add_argument("--path-prefix", default=None,
                   help="Restrict to a subtree, relative to source (e.g. '2026/07').")
    p.add_argument("--limit", type=int, default=0, help="Process at most N rows (0 = no limit).")
    p.add_argument("--refile", action="store_true",
                   help="Also MOVE originals into the YYYY/MM folder implied by the corrected "
                        "date. Off by default: it rewrites your library layout, not just the "
                        "catalog. Combine with --dry-run first.")
    p.add_argument("--dry-run", action="store_true",
                   help="Report what would change; write nothing and publish nothing.")
    p.add_argument("--show", type=int, default=20,
                   help="Print at most N per-file date corrections (default: 20).")
    p.add_argument("--verbose", "-v", action="store_true", help="Print every correction.")
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
