"""Tests for the gallery indexer.

Run from the indexer/ directory with the project venv:

    .venv/bin/python -m unittest discover -s tests -v

All tests use temporary directories and generated fixtures only — they never touch a real
NAS, real media, or the network.
"""
from __future__ import annotations

import contextlib
import errno
import io
import os
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

# Make indexer.py importable when running from indexer/ or indexer/tests/.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from PIL import Image  # noqa: E402

import indexer  # noqa: E402


# --- fixtures ----------------------------------------------------------------


def make_jpeg(path: Path, color, size=(64, 64)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(path, "JPEG", quality=90)


def write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def run_indexer(source: Path, **overrides) -> int:
    argv = ["--source", str(source), "--no-import", "--workers", "1"]
    for k, v in overrides.items():
        argv += [f"--{k.replace('_', '-')}", str(v)]
    args = indexer.build_parser().parse_args(argv)
    return indexer.run(args)


def read_manifest(source: Path) -> dict:
    return indexer.read_manifest(source / indexer.GALLERY_DIRNAME / indexer.MANIFEST_NAME)


def catalog_path(source: Path) -> Path:
    m = read_manifest(source)
    return source / indexer.GALLERY_DIRNAME / m["catalog"]


# --- hash correctness --------------------------------------------------------


class ContentHashTests(unittest.TestCase):
    def test_middle_difference_changes_hash(self):
        """Equal size and equal first/last MiB, but different middle → different hashes.

        This is exactly the collision the old size+edges hash allowed.
        """
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            edge = b"A" * (1 << 20)          # identical first & last MiB
            middle_a = b"B" * (1 << 20)
            middle_b = b"C" * (1 << 20)      # only the middle differs
            a = d / "a.bin"
            b = d / "b.bin"
            write_bytes(a, edge + middle_a + edge)
            write_bytes(b, edge + middle_b + edge)

            self.assertEqual(a.stat().st_size, b.stat().st_size)  # equal size
            self.assertNotEqual(indexer.content_hash(a), indexer.content_hash(b))

    def test_identical_files_share_hash(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            data = os.urandom(3 << 20)
            a, b = d / "a.bin", d / "b.bin"
            write_bytes(a, data)
            write_bytes(b, data)
            self.assertEqual(indexer.content_hash(a), indexer.content_hash(b))

    def test_hash_is_sha256_length(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.bin"
            write_bytes(p, b"hello")
            self.assertEqual(len(indexer.content_hash(p)), 64)


# --- worker selection --------------------------------------------------------


class WorkerCountTests(unittest.TestCase):
    def test_memory_bounds_workers(self):
        # 4 GiB / 1.5 GiB budget = 2 workers even with many cores.
        self.assertEqual(indexer.default_worker_count(16, 4 * 1024**3), 2)

    def test_capped(self):
        self.assertEqual(
            indexer.default_worker_count(64, 1024 * 1024**3), indexer.MAX_AUTO_WORKERS)

    def test_cpu_bounds_workers(self):
        self.assertEqual(indexer.default_worker_count(2, 64 * 1024**3), 2)

    def test_at_least_one(self):
        self.assertGreaterEqual(indexer.default_worker_count(1, 1), 1)

    def test_unknown_ram_falls_back_to_cpu(self):
        self.assertEqual(indexer.default_worker_count(4, 0), 4)


# --- derivative generation ---------------------------------------------------


class DerivativeTests(unittest.TestCase):
    def test_concurrent_duplicate_writes_are_not_corrupted(self):
        """Two workers writing the same digest's derivatives must not corrupt them."""
        with tempfile.TemporaryDirectory() as d:
            gallery_root = Path(d) / ".gallery"
            thumbs_root = gallery_root / "thumbs"
            thumbs_root.mkdir(parents=True)
            img = Image.new("RGB", (400, 300), (10, 120, 200))
            digest = "d" * 64

            errors = []

            def worker():
                try:
                    for _ in range(4):
                        indexer.write_derivatives(
                            img, digest, thumbs_root, gallery_root, 320, 1600)
                except Exception as e:  # noqa: BLE001
                    errors.append(e)

            threads = [threading.Thread(target=worker) for _ in range(6)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()

            self.assertEqual(errors, [])
            shard = thumbs_root / digest[:2]
            # No temp files left behind, and both derivatives decode cleanly.
            self.assertFalse([p for p in shard.iterdir()
                              if p.name.startswith(indexer.CATALOG_TMP_PREFIX)])
            for suffix in ("_t", "_p"):
                with Image.open(shard / f"{digest}{suffix}.webp") as im:
                    im.verify()

    @unittest.skipUnless(indexer.have_ffmpeg(), "ffmpeg/ffprobe required")
    def test_video_proxy_is_seekable_compatible_and_fps_bounded(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source = root / "source.mp4"
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                 "testsrc=size=160x90:rate=60", "-t", "0.3", "-pix_fmt", "yuv420p",
                 str(source)],
                check=True,
            )
            gallery = root / indexer.GALLERY_DIRNAME
            thumbs = gallery / indexer.THUMBS_DIRNAME
            thumbs.mkdir(parents=True)
            stat = source.stat()
            record = indexer.process_video(
                source, "source.mp4", stat.st_size, stat.st_mtime_ns, "a" * 64,
                thumbs, gallery, thumb_px=320, preview_px=1600)
            self.assertIsNotNone(record.video_path)
            proxy = root / record.video_path
            stream = next(s for s in indexer.ffprobe_info(proxy)["streams"]
                          if s.get("codec_type") == "video")
            self.assertEqual(stream["codec_name"], "h264")
            self.assertLessEqual(indexer._frame_rate(stream["avg_frame_rate"]), 30.0)
            # faststart places MP4 metadata before media payload for prompt SMB range playback.
            data = proxy.read_bytes()
            self.assertLess(data.find(b"moov"), data.find(b"mdat"))


# --- place identity & invalidation -------------------------------------------


def _mem_db():
    conn = sqlite3.connect(":memory:")
    conn.executescript(indexer.SCHEMA)
    return conn


def _insert_photo(conn, rel, capture_ms=0, city=None, country=None,
                  gps=(None, None), model=None):
    conn.execute(
        "INSERT INTO photos (relative_path, filename, media_type, capture_date, "
        "thumb_path, preview_path, place_city, place_country, gps_lat, gps_lon, "
        "camera_model) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        (rel, Path(rel).name, "photo", capture_ms, f"{rel}_t", f"{rel}_p",
         city, country, gps[0], gps[1], model),
    )


class PlaceAlbumTests(unittest.TestCase):
    def test_same_city_different_country_not_merged(self):
        conn = _mem_db()
        _insert_photo(conn, "a.jpg", city="Springfield", country="United States")
        _insert_photo(conn, "b.jpg", city="Springfield", country="Australia")
        indexer.build_albums(conn)
        places = conn.execute(
            "SELECT name, album_key FROM albums WHERE type='place' ORDER BY name").fetchall()
        self.assertEqual(len(places), 2, "identically named cities must not merge")
        # Display names disambiguated by country when a city name recurs.
        self.assertTrue(all("Springfield" in name for name, _ in places))
        self.assertNotEqual(places[0][1], places[1][1])

    def test_album_ids_stable_across_rebuilds(self):
        conn = _mem_db()
        _insert_photo(conn, "a.jpg", capture_ms=0, city="Paris", country="France")
        indexer.build_albums(conn)
        first = dict(conn.execute("SELECT album_key, id FROM albums WHERE type='place'"))
        # Add another photo to the same place; the existing album must keep its id.
        _insert_photo(conn, "b.jpg", capture_ms=0, city="Paris", country="France")
        indexer.build_albums(conn)
        second = dict(conn.execute("SELECT album_key, id FROM albums WHERE type='place'"))
        self.assertEqual(first, second, "album id churned for an unchanged album")

    def test_place_invalidated_when_gps_disappears(self):
        conn = _mem_db()
        _insert_photo(conn, "a.jpg", city="Paris", country="France", gps=(None, None))
        n = indexer.invalidate_stale_places(conn)
        self.assertEqual(n, 1)
        row = conn.execute(
            "SELECT place_city, place_country FROM photos WHERE relative_path='a.jpg'"
        ).fetchone()
        self.assertEqual(row, (None, None))

    def test_place_kept_when_gps_present(self):
        conn = _mem_db()
        _insert_photo(conn, "a.jpg", city="Paris", country="France", gps=(48.8, 2.3))
        indexer.invalidate_stale_places(conn)
        row = conn.execute(
            "SELECT place_city FROM photos WHERE relative_path='a.jpg'").fetchone()
        self.assertEqual(row[0], "Paris")


# --- trip albums -------------------------------------------------------------


class TripAlbumTests(unittest.TestCase):
    DAY = 24 * 60 * 60 * 1000
    BASE = 1_600_000_000_000  # ~2020, away from the epoch/tz boundary

    def _home_photos(self, conn, n=20):
        # Home is simply the most-photographed place, so make one clearly dominant.
        for i in range(n):
            _insert_photo(conn, f"home{i}.jpg", capture_ms=self.BASE + i * self.DAY,
                          city="Novosibirsk", country="Russia")

    def _add_trip(self, conn, prefix, start_day, count=6):
        start = self.BASE + start_day * self.DAY
        for i in range(count):
            # i % 2 spreads photos over two calendar days; +i keeps them ordered within a day.
            _insert_photo(conn, f"{prefix}{i}.jpg",
                          capture_ms=start + (i % 2) * self.DAY + i * 1000,
                          city="Saint Petersburg", country="Russia")

    def test_qualifying_trip_created(self):
        conn = _mem_db()
        self._home_photos(conn)
        self._add_trip(conn, "trip", start_day=200, count=6)
        indexer.build_albums(conn)
        trips = conn.execute(
            "SELECT name, photo_count FROM albums WHERE type='trip'").fetchall()
        self.assertEqual(len(trips), 1)
        name, count = trips[0]
        self.assertIn("Saint Petersburg", name)
        self.assertEqual(count, 6)

    def test_single_day_out_of_town_is_not_a_trip(self):
        conn = _mem_db()
        self._home_photos(conn)
        start = self.BASE + 200 * self.DAY
        for i in range(6):  # plenty of photos, but all on one calendar day
            _insert_photo(conn, f"day{i}.jpg", capture_ms=start + i * 1000,
                          city="Saint Petersburg", country="Russia")
        indexer.build_albums(conn)
        self.assertEqual(
            conn.execute("SELECT COUNT(*) FROM albums WHERE type='trip'").fetchone()[0], 0)

    def test_too_few_photos_is_not_a_trip(self):
        conn = _mem_db()
        self._home_photos(conn)
        self._add_trip(conn, "tiny", start_day=200, count=4)  # < TRIP_MIN_PHOTOS
        indexer.build_albums(conn)
        self.assertEqual(
            conn.execute("SELECT COUNT(*) FROM albums WHERE type='trip'").fetchone()[0], 0)

    def test_time_gap_splits_into_two_trips(self):
        conn = _mem_db()
        self._home_photos(conn)
        self._add_trip(conn, "a", start_day=200)
        self._add_trip(conn, "b", start_day=260)  # ~2 months later → separate trip
        indexer.build_albums(conn)
        self.assertEqual(
            conn.execute("SELECT COUNT(*) FROM albums WHERE type='trip'").fetchone()[0], 2)

    def test_trip_ids_stable_across_rebuilds(self):
        conn = _mem_db()
        self._home_photos(conn)
        self._add_trip(conn, "trip", start_day=200)
        indexer.build_albums(conn)
        first = dict(conn.execute("SELECT album_key, id FROM albums WHERE type='trip'"))
        indexer.build_albums(conn)
        second = dict(conn.execute("SELECT album_key, id FROM albums WHERE type='trip'"))
        self.assertEqual(len(first), 1)
        self.assertEqual(first, second, "trip album id churned across an unchanged rebuild")


# --- catalog validation ------------------------------------------------------


class ValidationTests(unittest.TestCase):
    def test_garbage_file_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "bad.db"
            p.write_bytes(b"not a database")
            ok, _ = indexer.validate_catalog_file(p)
            self.assertFalse(ok)

    def test_missing_tables_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "empty.db"
            sqlite3.connect(str(p)).close()
            ok, reason = indexer.validate_catalog_file(p)
            self.assertFalse(ok)

    def test_invalid_snapshot_does_not_replace_manifest(self):
        """A snapshot that fails validation must not flip the manifest."""
        with tempfile.TemporaryDirectory() as d:
            gallery = Path(d) / ".gallery"
            catalogs = gallery / indexer.CATALOGS_DIRNAME
            catalogs.mkdir(parents=True)
            manifest_path = gallery / indexer.MANIFEST_NAME
            indexer.write_manifest_atomic(manifest_path, {"revision": 7, "catalog": "old.db"})

            # A structurally-valid sqlite DB but WITHOUT the required schema → validation fails.
            work = gallery / "broken.work.db"
            sqlite3.connect(str(work)).close()

            with self.assertRaises(RuntimeError):
                indexer.publish_catalog(work, catalogs, "index-000008-x.db",
                                        manifest_path, {"revision": 8})

            # Manifest untouched: still points at revision 7.
            self.assertEqual(read_manifest(Path(d))["revision"], 7)
            self.assertEqual(indexer.read_manifest(manifest_path)["revision"], 7)


# --- full run: publication, no-op, incremental, GC ---------------------------


class FullRunTests(unittest.TestCase):
    def _library(self, d: Path):
        make_jpeg(d / "2023" / "07" / "one.jpg", (200, 30, 30))
        make_jpeg(d / "2023" / "07" / "two.jpg", (30, 200, 30))
        make_jpeg(d / "2024" / "01" / "three.jpg", (30, 30, 200))

    def test_publish_produces_valid_versioned_catalog(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            self._library(src)
            self.assertEqual(run_indexer(src), 0)

            m = read_manifest(src)
            self.assertEqual(m["schema_version"], indexer.SCHEMA_VERSION)
            self.assertEqual(m["catalog_format_version"], indexer.CATALOG_FORMAT_VERSION)
            self.assertEqual(m["revision"], 1)
            self.assertTrue(m["catalog"].startswith(indexer.CATALOGS_DIRNAME + "/"))
            cat = catalog_path(src)
            self.assertTrue(cat.exists())
            ok, reason = indexer.validate_catalog_file(cat)
            self.assertTrue(ok, reason)
            with sqlite3.connect(str(cat)) as conn:
                self.assertEqual(conn.execute("PRAGMA journal_mode").fetchone()[0], "delete")
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM photos").fetchone()[0], 3)
                album_id = conn.execute("SELECT id FROM albums LIMIT 1").fetchone()[0]
                plan = " ".join(row[3] for row in conn.execute(
                    "EXPLAIN QUERY PLAN SELECT p.* FROM photos p "
                    "JOIN photo_albums pa ON pa.photo_id=p.id WHERE pa.album_id=? "
                    "ORDER BY pa.capture_date DESC, pa.photo_id DESC LIMIT 60 OFFSET 0",
                    (album_id,),
                ))
                self.assertIn("idx_photo_albums_album_capture", plan)
                self.assertNotIn("USE TEMP B-TREE", plan)

    def test_noop_run_does_not_publish_new_revision(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            self._library(src)
            run_indexer(src)
            first = read_manifest(src)
            cat1 = catalog_path(src)

            run_indexer(src)  # nothing changed
            second = read_manifest(src)
            self.assertEqual(second["revision"], first["revision"])
            self.assertEqual(second["catalog"], first["catalog"])
            self.assertEqual(second["content_fingerprint"], first["content_fingerprint"])
            # Exactly one catalog file on disk (no churn).
            catalogs = list((src / indexer.GALLERY_DIRNAME / indexer.CATALOGS_DIRNAME)
                            .glob(indexer.CATALOG_PREFIX + "*.db"))
            self.assertEqual(len(catalogs), 1)
            self.assertTrue(cat1.exists())

    def test_legacy_catalog_container_is_republished_once(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            self._library(src)
            run_indexer(src)
            old = read_manifest(src)
            old_revision = old["revision"]
            old.pop("catalog_format_version")
            indexer.write_manifest_atomic(
                src / indexer.GALLERY_DIRNAME / indexer.MANIFEST_NAME,
                old,
            )

            run_indexer(src)  # no media changes; container format alone forces publication
            upgraded = read_manifest(src)
            self.assertEqual(upgraded["revision"], old_revision + 1)
            self.assertEqual(
                upgraded["catalog_format_version"], indexer.CATALOG_FORMAT_VERSION)
            with sqlite3.connect(str(catalog_path(src))) as conn:
                self.assertEqual(conn.execute("PRAGMA journal_mode").fetchone()[0], "delete")

    def test_changed_file_publishes_new_revision(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            self._library(src)
            run_indexer(src)
            r1 = read_manifest(src)["revision"]

            # Change a file's contents (and therefore its size/mtime).
            make_jpeg(src / "2023" / "07" / "one.jpg", (123, 45, 67), size=(80, 80))
            run_indexer(src)
            r2 = read_manifest(src)["revision"]
            self.assertEqual(r2, r1 + 1)

    def test_deleted_file_pruned_but_derivative_kept_while_retained_catalog_refs_it(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            self._library(src)
            gone = src / "2023" / "07" / "two.jpg"
            digest = indexer.content_hash(gone)
            run_indexer(src)  # rev 1

            shard = (src / indexer.GALLERY_DIRNAME / indexer.THUMBS_DIRNAME / digest[:2])
            self.assertTrue((shard / f"{digest}_t.webp").exists())

            gone.unlink()
            run_indexer(src)  # rev 2 — rev 1 retained and STILL references two.jpg's derivative

            # Row pruned from the current catalog…
            cat = catalog_path(src)
            names = [r[0] for r in sqlite3.connect(str(cat)).execute(
                "SELECT filename FROM photos")]
            self.assertNotIn("two.jpg", names)
            # …but the derivative is preserved because the retained rev 1 still references it.
            self.assertTrue((shard / f"{digest}_t.webp").exists())
            self.assertTrue((shard / f"{digest}_p.webp").exists())

            # Once rev 1 drops out of the keep-set (another publish), the now fully-orphaned
            # derivative is GC'd.
            make_jpeg(src / "2023" / "07" / "one.jpg", (5, 6, 7), size=(48, 48))
            run_indexer(src)  # rev 3 — keep {3, 2}; rev 1 gone
            self.assertFalse((shard / f"{digest}_t.webp").exists())
            self.assertFalse((shard / f"{digest}_p.webp").exists())

    def test_previous_catalog_survives_until_gc_keeps_one(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            self._library(src)
            run_indexer(src)
            cat1 = catalog_path(src)
            make_jpeg(src / "2024" / "01" / "four.jpg", (9, 9, 9))
            run_indexer(src)
            # We retain the immediately previous catalog (KEEP_OLD_CATALOGS=1).
            self.assertTrue(cat1.exists())


class RetainedDerivativeGcTests(unittest.TestCase):
    """Finding 8: GC must keep derivatives referenced by EVERY retained catalog."""

    def _digest_of(self, path: Path) -> str:
        return indexer.content_hash(path)

    def _deriv_paths(self, src: Path, digest: str):
        shard = src / indexer.GALLERY_DIRNAME / indexer.THUMBS_DIRNAME / digest[:2]
        return shard / f"{digest}_t.webp", shard / f"{digest}_p.webp"

    def test_updated_photo_keeps_both_retained_catalogs_derivatives(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            photo = src / "2023" / "07" / "p.jpg"
            make_jpeg(photo, (10, 20, 30))
            make_jpeg(src / "2023" / "07" / "other.jpg", (200, 10, 10))
            digest_v1 = self._digest_of(photo)
            run_indexer(src)  # revision 1
            cat1 = catalog_path(src)

            # Update the photo's content → new content hash → new derivative path.
            make_jpeg(photo, (99, 88, 77), size=(80, 80))
            digest_v2 = self._digest_of(photo)
            self.assertNotEqual(digest_v1, digest_v2)
            run_indexer(src)  # revision 2 (rev 1 retained)

            self.assertEqual(read_manifest(src)["revision"], 2)
            self.assertTrue(cat1.exists(), "previous catalog must be retained")

            # Every derivative referenced by BOTH retained catalogs must still exist —
            # including revision 1's derivative for the now-updated photo.
            for digest in (digest_v1, digest_v2):
                t, p = self._deriv_paths(src, digest)
                self.assertTrue(t.exists(), f"missing thumb for {digest[:8]}")
                self.assertTrue(p.exists(), f"missing preview for {digest[:8]}")

    def test_derivatives_unreferenced_by_all_retained_catalogs_are_removed(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            photo = src / "2023" / "07" / "p.jpg"
            make_jpeg(src / "2023" / "07" / "keep.jpg", (1, 2, 3))

            make_jpeg(photo, (10, 20, 30))
            digest_v1 = self._digest_of(photo)
            run_indexer(src)  # rev 1

            make_jpeg(photo, (40, 50, 60), size=(70, 70))
            run_indexer(src)  # rev 2 (keep {2,1})

            make_jpeg(photo, (70, 80, 90), size=(90, 90))
            run_indexer(src)  # rev 3 (keep {3,2}); rev 1 drops out

            # rev 1 is no longer retained, so its unique derivative (digest_v1) is now
            # unreferenced by ALL retained catalogs and must be GC'd.
            t1, p1 = self._deriv_paths(src, digest_v1)
            self.assertFalse(t1.exists(), "orphaned v1 thumb should be removed")
            self.assertFalse(p1.exists(), "orphaned v1 preview should be removed")

    def test_full_run_with_unreadable_retained_catalog_skips_gc_and_publishes(self):
        # Full publish/GC orchestration: a retained catalog that has become unreadable must
        # not block publishing the new revision, must cause derivative GC to be skipped, must
        # leave orphan-candidate derivatives untouched, and must emit a warning.
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            gone = src / "2023" / "07" / "gone.jpg"
            make_jpeg(gone, (10, 20, 30))
            make_jpeg(src / "2023" / "07" / "keep.jpg", (1, 2, 3))
            gone_digest = self._digest_of(gone)
            run_indexer(src)  # rev 1

            # Change 'keep' so run 2 publishes a new revision, and delete 'gone' so its
            # derivative becomes an orphan candidate that GC would normally remove.
            make_jpeg(src / "2023" / "07" / "keep.jpg", (9, 8, 7), size=(50, 50))
            gone.unlink()
            gone_t, gone_p = self._deriv_paths(src, gone_digest)

            # Corrupt the retained rev-1 catalog right before run 2's GC reads it.
            cat1 = catalog_path(src)
            cat1.write_bytes(b"not a database")

            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                rc = run_indexer(src)  # rev 2 — keep = {rev2, corrupt rev1}
            log_text = out.getvalue()

            # Publishing the current catalog is NOT blocked by the unreadable retained one.
            self.assertEqual(rc, 0)
            self.assertEqual(read_manifest(src)["revision"], 2)
            ok, reason = indexer.validate_catalog_file(catalog_path(src))
            self.assertTrue(ok, reason)

            # Derivative GC was skipped, so the orphan-candidate files are untouched…
            self.assertTrue(gone_t.exists(), "orphan candidate thumb must be left untouched")
            self.assertTrue(gone_p.exists(), "orphan candidate preview must be left untouched")
            # …and the skip was reported.
            self.assertIn("skipping derivative cleanup", log_text)


class LibraryLockTests(unittest.TestCase):
    """Finding 9: exclusive library lock serializes runs; releases after success/failure."""

    def _gallery(self, d) -> Path:
        g = Path(d) / indexer.GALLERY_DIRNAME
        g.mkdir(parents=True, exist_ok=True)
        return g

    def test_second_acquire_fails_fast_while_held(self):
        with tempfile.TemporaryDirectory() as d:
            g = self._gallery(d)
            first = indexer.LibraryLock(g).acquire()
            try:
                with self.assertRaises(indexer.LockError):
                    indexer.LibraryLock(g).acquire()
            finally:
                first.release()

    def test_lock_reacquirable_after_release(self):
        with tempfile.TemporaryDirectory() as d:
            g = self._gallery(d)
            indexer.LibraryLock(g).acquire().release()
            # A fresh acquire must now succeed.
            lock = indexer.LibraryLock(g).acquire()
            lock.release()

    def test_lock_released_after_exception_in_context(self):
        with tempfile.TemporaryDirectory() as d:
            g = self._gallery(d)
            with self.assertRaises(RuntimeError):
                with indexer.LibraryLock(g):
                    raise RuntimeError("boom")
            # __exit__ released it; a new acquire must succeed.
            indexer.LibraryLock(g).acquire().release()

    def test_run_fails_fast_and_publishes_nothing_when_locked(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            make_jpeg(src / "2023" / "07" / "a.jpg", (1, 2, 3))
            g = self._gallery(d)
            held = indexer.LibraryLock(g).acquire()
            try:
                args = indexer.build_parser().parse_args(
                    ["--source", str(src), "--no-import", "--workers", "1"])
                self.assertEqual(indexer.run(args), 3)  # fast-fail exit code
                # The active run's files are untouched: nothing published.
                self.assertFalse((g / indexer.MANIFEST_NAME).exists())
            finally:
                held.release()

    def test_run_acquires_and_releases_on_success(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            make_jpeg(src / "2023" / "07" / "a.jpg", (1, 2, 3))
            self.assertEqual(run_indexer(src), 0)
            # Lock released after a normal run → a subsequent run can acquire it.
            g = src / indexer.GALLERY_DIRNAME
            indexer.LibraryLock(g).acquire().release()

    def test_contention_error_is_marked_contended(self):
        with tempfile.TemporaryDirectory() as d:
            g = self._gallery(d)
            first = indexer.LibraryLock(g).acquire()
            try:
                with self.assertRaises(indexer.LockError) as ctx:
                    indexer.LibraryLock(g).acquire()
                self.assertTrue(ctx.exception.contended)
                self.assertEqual(ctx.exception.kind, "contended")
            finally:
                first.release()


class LockErrnoClassificationTests(unittest.TestCase):
    """Finding 7: distinguish contention from unsupported/permission/other flock errors."""

    def test_would_block_is_contention(self):
        self.assertEqual(indexer.classify_lock_error(errno.EWOULDBLOCK), "contended")
        self.assertEqual(indexer.classify_lock_error(errno.EAGAIN), "contended")

    def test_enotsup_is_unsupported(self):
        self.assertEqual(indexer.classify_lock_error(errno.ENOTSUP), "unsupported")
        eopnotsupp = getattr(errno, "EOPNOTSUPP", errno.ENOTSUP)
        self.assertEqual(indexer.classify_lock_error(eopnotsupp), "unsupported")

    def test_permission_errors(self):
        self.assertEqual(indexer.classify_lock_error(errno.EACCES), "permission")
        self.assertEqual(indexer.classify_lock_error(errno.EPERM), "permission")

    def test_other_errno(self):
        self.assertEqual(indexer.classify_lock_error(errno.EIO), "other")

    def test_non_contention_lockerror_message_and_flag(self):
        # A non-contention failure classifies distinctly and is NOT reported as 'active run'.
        err = indexer.LockError("could not acquire library lock [unsupported] (ENOTSUP: x)",
                                kind="unsupported")
        self.assertFalse(err.contended)
        self.assertNotIn("another indexer run is active", str(err))

    def test_lock_file_open_permission_error_is_classified(self):
        with tempfile.TemporaryDirectory() as d:
            gallery = Path(d) / indexer.GALLERY_DIRNAME
            with mock.patch(
                "indexer.os.open",
                side_effect=PermissionError(errno.EACCES, "permission denied"),
            ):
                with self.assertRaises(indexer.LockError) as ctx:
                    indexer.LibraryLock(gallery).acquire()
        self.assertEqual(ctx.exception.kind, "permission")
        self.assertIn("EACCES", str(ctx.exception))
        self.assertNotIn("another indexer run is active", str(ctx.exception))

    def test_run_reports_lock_open_permission_error_without_traceback(self):
        with tempfile.TemporaryDirectory() as d:
            args = indexer.build_parser().parse_args(
                ["--source", d, "--no-import", "--workers", "1"])
            out = io.StringIO()
            with mock.patch(
                "indexer.os.open",
                side_effect=PermissionError(errno.EACCES, "permission denied"),
            ), contextlib.redirect_stdout(out):
                result = indexer.run(args)
        self.assertEqual(result, 3)
        self.assertIn("[permission]", out.getvalue())


class DerivativeGenerationTests(unittest.TestCase):
    """Finding 5: derivative generation advances only when cached bytes may be invalid."""

    def _run(self, src, *extra):
        argv = ["--source", str(src), "--no-import", "--workers", "1", *extra]
        return indexer.run(indexer.build_parser().parse_args(argv))

    def _gen(self, src) -> int:
        return int(read_manifest(src)["derivative_generation"])

    # --- pure helper ---
    def test_helper_first_publish_is_one(self):
        self.assertEqual(indexer.next_derivative_generation(0, None, "p", False), 1)

    def test_helper_stable_on_same_params(self):
        self.assertEqual(indexer.next_derivative_generation(5, "p", "p", False), 5)

    def test_helper_advances_on_rebuild(self):
        self.assertEqual(indexer.next_derivative_generation(5, "p", "p", True), 6)

    def test_helper_advances_on_param_change(self):
        self.assertEqual(indexer.next_derivative_generation(5, "p", "q", False), 6)

    def test_params_fingerprint_changes_with_dimensions(self):
        self.assertNotEqual(
            indexer.derivative_params_fingerprint(320, 1600),
            indexer.derivative_params_fingerprint(256, 1600),
        )

    # --- full-run behavior ---
    def test_ordinary_add_and_delete_keep_generation(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            make_jpeg(src / "2023" / "07" / "a.jpg", (1, 2, 3))
            self._run(src)
            gen1 = self._gen(src)
            self.assertEqual(read_manifest(src)["revision"], 1)

            # Add a photo → new revision, SAME derivative generation (cache stays valid).
            make_jpeg(src / "2023" / "07" / "b.jpg", (4, 5, 6))
            self._run(src)
            self.assertEqual(read_manifest(src)["revision"], 2)
            self.assertEqual(self._gen(src), gen1)

            # Delete a photo → new revision, still SAME generation.
            (src / "2023" / "07" / "b.jpg").unlink()
            self._run(src)
            self.assertEqual(read_manifest(src)["revision"], 3)
            self.assertEqual(self._gen(src), gen1)

    def test_rebuild_advances_generation(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            make_jpeg(src / "2023" / "07" / "a.jpg", (1, 2, 3))
            self._run(src)
            gen1 = self._gen(src)
            self._run(src, "--rebuild")
            self.assertEqual(self._gen(src), gen1 + 1)

    def test_dimension_change_automatically_rebuilds_and_advances_generation(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            make_jpeg(src / "2023" / "07" / "a.jpg", (1, 2, 3), size=(800, 600))
            self._run(src)
            gen1 = self._gen(src)
            params1 = read_manifest(src)["derivative_params"]
            first_catalog = catalog_path(src)
            with sqlite3.connect(str(first_catalog)) as conn:
                thumb_rel = conn.execute("SELECT thumb_path FROM photos").fetchone()[0]
            with Image.open(src / thumb_rel) as image:
                self.assertEqual(image.size, (320, 240))

            # No explicit --rebuild: the parameter change itself must force reprocessing.
            self._run(src, "--thumb", "64")
            self.assertGreater(self._gen(src), gen1)
            self.assertNotEqual(read_manifest(src)["derivative_params"], params1)
            with Image.open(src / thumb_rel) as image:
                self.assertEqual(image.size, (64, 48))

    def test_rebuild_prunes_deleted_rows(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            keep = src / "2023" / "07" / "keep.jpg"
            gone = src / "2023" / "07" / "gone.jpg"
            make_jpeg(keep, (1, 2, 3))
            make_jpeg(gone, (4, 5, 6))
            self._run(src)
            gone.unlink()
            self._run(src, "--rebuild")
            with sqlite3.connect(str(catalog_path(src))) as conn:
                paths = {row[0] for row in conn.execute("SELECT relative_path FROM photos")}
            self.assertEqual(paths, {"2023/07/keep.jpg"})


if __name__ == "__main__":
    unittest.main()
