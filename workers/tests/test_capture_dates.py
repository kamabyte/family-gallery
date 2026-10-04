"""Tests for capture-date extraction and the standalone metadata refresh tool.

The fixtures here are real ``ffprobe`` output shapes taken from iCloud-downloaded videos: the
container header carries the *download* time while the true capture instant survives only in
``com.apple.quicktime.creationdate``. Reading those two in the wrong order silently dated a whole
library to the day it was indexed, so the ordering is pinned by test.

Run from the workers/ directory with the project venv:

    .venv/bin/python -m unittest discover -s tests -v
"""
from __future__ import annotations

import sqlite3
import sys
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import indexer  # noqa: E402
import refresh_metadata  # noqa: E402


def epoch_ms(*args) -> int:
    """Local-time epoch ms, matching how the indexer interprets naive timestamps."""
    return int(datetime(*args).timestamp() * 1000)


# Verbatim format-tag block from IMG_2988.mov after an iCloud "optimized" download.
ICLOUD_TRANSCODED_TAGS = {
    "major_brand": "qt  ",
    "minor_version": "0",
    "compatible_brands": "qt  ",
    "creation_time": "2026-07-24T06:39:34.000000Z",           # <- the download, not the capture
    "com.apple.quicktime.location.accuracy.horizontal": "165.000000",
    "com.apple.quicktime.make": "Apple",
    "com.apple.quicktime.model": "iPhone 12 Pro",
    "com.apple.quicktime.software": "14.4.2",
    "com.apple.quicktime.keywords": "",
    "com.apple.quicktime.location.ISO6709": "+55.4870+052.0354+000.000/",
    "com.apple.quicktime.creationdate": "2021-05-29T19:27:13+03:00",   # <- the real capture
}


def icloud_probe(tags=None, duration="12.5", width=1920, height=1080) -> dict:
    """An ffprobe result shaped like the real thing, including rewritten stream headers."""
    return {
        "format": {"duration": duration, "tags": dict(ICLOUD_TRANSCODED_TAGS if tags is None
                                                      else tags)},
        "streams": [
            {"codec_type": "video", "width": width, "height": height,
             "avg_frame_rate": "30/1", "r_frame_rate": "30/1",
             # The transcoder rewrites per-stream headers too — they are never a rescue.
             "tags": {"creation_time": "2026-07-24T06:39:34.000000Z"}},
            {"codec_type": "audio", "tags": {"creation_time": "2026-07-24T06:39:34.000000Z"}},
        ],
    }


class IsoParsingTests(unittest.TestCase):
    def test_colon_offset(self):
        self.assertEqual(indexer.iso_to_epoch_ms("2021-05-29T19:27:13+03:00"), 1622305633000)

    def test_colonless_offset_is_normalized(self):
        """Python 3.9's fromisoformat rejects '+0300'; returning None would fall back to mtime."""
        self.assertEqual(
            indexer.iso_to_epoch_ms("2021-05-29T19:27:13+0300"),
            indexer.iso_to_epoch_ms("2021-05-29T19:27:13+03:00"),
        )

    def test_negative_colonless_offset(self):
        self.assertEqual(
            indexer.iso_to_epoch_ms("2021-05-29T19:27:13-0430"),
            indexer.iso_to_epoch_ms("2021-05-29T19:27:13-04:30"),
        )

    def test_zulu_and_fractional_seconds(self):
        self.assertEqual(indexer.iso_to_epoch_ms("2026-07-24T06:39:34.000000Z"), 1784875174000)

    def test_naive_is_local_time(self):
        self.assertEqual(indexer.iso_to_epoch_ms("2021-05-29T19:27:13"),
                         epoch_ms(2021, 5, 29, 19, 27, 13))

    def test_garbage_returns_none(self):
        for bad in ("", "not a date", None, "0000-00-00T00:00:00"):
            self.assertIsNone(indexer.iso_to_epoch_ms(bad), bad)


class FilenameDateTests(unittest.TestCase):
    def test_compact_datetime(self):
        self.assertEqual(indexer.filename_to_epoch_ms("VID_20181201_141857.mov"),
                         epoch_ms(2018, 12, 1, 14, 18, 57))

    def test_leading_date(self):
        self.assertEqual(indexer.filename_to_epoch_ms("20140501_175850.mov"),
                         epoch_ms(2014, 5, 1, 17, 58, 50))

    def test_screen_recording_form(self):
        self.assertEqual(
            indexer.filename_to_epoch_ms("Screen Recording 2021-05-29 at 19.27.13.mov"),
            epoch_ms(2021, 5, 29, 19, 27, 13))

    def test_date_only(self):
        self.assertEqual(indexer.filename_to_epoch_ms("trip_2016-06-11.mp4"),
                         epoch_ms(2016, 6, 11))

    def test_impossible_calendar_values_rejected(self):
        self.assertIsNone(indexer.filename_to_epoch_ms("IMG_20219932_999999.mov"))

    def test_future_dates_rejected(self):
        future = datetime.fromtimestamp(time.time()).year + 3
        self.assertIsNone(indexer.filename_to_epoch_ms(f"VID_{future}0101_120000.mov"))

    def test_plain_icloud_name_has_no_date(self):
        self.assertIsNone(indexer.filename_to_epoch_ms("IMG_2988.mov"))


class VideoCaptureSourceTests(unittest.TestCase):
    def test_apple_tag_beats_rewritten_header(self):
        """The regression: a transcoded container must not be dated by its mvhd header."""
        ms, source = indexer.capture_ms_from_video_tags(ICLOUD_TRANSCODED_TAGS)
        self.assertEqual(source, indexer.CAPTURE_SOURCE_APPLE)
        self.assertEqual(datetime.fromtimestamp(ms / 1000).year, 2021)

    def test_header_used_when_no_apple_tag(self):
        tags = {k: v for k, v in ICLOUD_TRANSCODED_TAGS.items()
                if k != "com.apple.quicktime.creationdate"}
        ms, source = indexer.capture_ms_from_video_tags(tags)
        self.assertEqual(source, indexer.CAPTURE_SOURCE_QUICKTIME)
        self.assertEqual(ms, 1784875174000)

    def test_unparsable_apple_tag_falls_through_to_header(self):
        tags = dict(ICLOUD_TRANSCODED_TAGS,
                    **{"com.apple.quicktime.creationdate": "garbage"})
        ms, source = indexer.capture_ms_from_video_tags(tags)
        self.assertEqual(source, indexer.CAPTURE_SOURCE_QUICKTIME)

    def test_empty_apple_tag_is_ignored(self):
        tags = dict(ICLOUD_TRANSCODED_TAGS, **{"com.apple.quicktime.creationdate": "   "})
        _, source = indexer.capture_ms_from_video_tags(tags)
        self.assertEqual(source, indexer.CAPTURE_SOURCE_QUICKTIME)

    def test_no_date_tags_at_all(self):
        ms, source = indexer.capture_ms_from_video_tags({"major_brand": "qt  "})
        self.assertIsNone(ms)
        self.assertIsNone(source)


class VideoProbeMetadataTests(unittest.TestCase):
    def test_full_extraction_from_icloud_download(self):
        meta = indexer.video_metadata_from_probe(icloud_probe(), Path("IMG_2988.mov"))
        self.assertEqual(meta["capture_date_source"], indexer.CAPTURE_SOURCE_APPLE)
        self.assertEqual(datetime.fromtimestamp(meta["capture_date"] / 1000).year, 2021)
        self.assertEqual(meta["camera_make"], "Apple")
        self.assertEqual(meta["camera_model"], "iPhone 12 Pro")
        self.assertAlmostEqual(meta["gps_lat"], 55.487, places=3)
        self.assertAlmostEqual(meta["gps_lon"], 52.0354, places=3)
        self.assertEqual(meta["duration_ms"], 12500)
        self.assertEqual((meta["width"], meta["height"]), (1920, 1080))
        self.assertAlmostEqual(meta["source_fps"], 30.0)

    def test_falls_back_to_filename_before_mtime(self):
        probe = icloud_probe(tags={"major_brand": "qt  "})
        mtime_ns = int(time.time() * 1_000_000_000)
        meta = indexer.video_metadata_from_probe(
            probe, Path("VID_20181201_141857.mov"), mtime_ns)
        self.assertEqual(meta["capture_date_source"], indexer.CAPTURE_SOURCE_FILENAME)
        self.assertEqual(meta["capture_date"], epoch_ms(2018, 12, 1, 14, 18, 57))

    def test_mtime_is_last_resort_and_marked_suspect(self):
        probe = icloud_probe(tags={"major_brand": "qt  "})
        meta = indexer.video_metadata_from_probe(
            probe, Path("IMG_2988.mov"), 1_700_000_000_000_000_000)
        self.assertEqual(meta["capture_date_source"], indexer.CAPTURE_SOURCE_MTIME)
        self.assertIn(meta["capture_date_source"], indexer.CAPTURE_SOURCES_SUSPECT)

    def test_no_mtime_leaves_date_unset(self):
        """The refresh tool relies on this so a failed probe can't overwrite a stored date."""
        probe = icloud_probe(tags={"major_brand": "qt  "})
        meta = indexer.video_metadata_from_probe(probe, Path("IMG_2988.mov"))
        self.assertIsNone(meta["capture_date"])
        self.assertIsNone(meta["capture_date_source"])


class AdditiveMigrationTests(unittest.TestCase):
    def test_column_added_to_existing_current_version_db(self):
        """A work DB already at SCHEMA_VERSION is never dropped, so it needs an explicit ALTER.

        This is the exact state of a library indexed before provenance tracking existed: the
        recorded schema_version already equals SCHEMA_VERSION, so `open_db`'s rebuild branch is
        skipped and `CREATE TABLE IF NOT EXISTS` is a no-op. Without the ALTER, every later
        write would fail on the missing column.
        """
        with tempfile.TemporaryDirectory() as td:
            db = Path(td) / "index.work.db"
            legacy = sqlite3.connect(str(db))
            legacy.executescript(
                """
                CREATE TABLE photos (
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
                    camera_model   TEXT
                );
                CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
                """
            )
            legacy.execute(
                "INSERT INTO photos (relative_path, filename, capture_date, thumb_path, "
                "preview_path) VALUES ('2026/07/IMG_2988.mov','IMG_2988.mov',1784875174000,"
                "'t','p')")
            legacy.execute("INSERT INTO meta(key,value) VALUES ('schema_version',?)",
                           (str(indexer.SCHEMA_VERSION),))
            legacy.commit()
            legacy.close()

            conn = indexer.open_db(db)
            cols = {r[1] for r in conn.execute("PRAGMA table_info(photos)")}
            self.assertIn("capture_date_source", cols)
            # The migration must be additive: the existing row is preserved, its provenance NULL
            # (which --only-suspect treats as unproven and therefore re-probes).
            row = conn.execute(
                "SELECT relative_path, capture_date, capture_date_source FROM photos").fetchone()
            self.assertEqual(row, ("2026/07/IMG_2988.mov", 1784875174000, None))
            conn.close()

    def test_migration_is_idempotent(self):
        with tempfile.TemporaryDirectory() as td:
            conn = indexer.open_db(Path(td) / "index.work.db")
            self.assertEqual(indexer.migrate_additive_columns(conn), [])
            conn.close()


class RefreshSelectionTests(unittest.TestCase):
    def _db(self, td: Path):
        conn = indexer.open_db(td / "index.work.db")
        rows = [
            ("2026/07/IMG_2988.mov", "video", indexer.CAPTURE_SOURCE_MTIME),
            ("2026/07/IMG_3022.mov", "video", None),               # pre-provenance row
            ("2021/05/IMG_0001.jpg", "photo", indexer.CAPTURE_SOURCE_EXIF),
            ("2021/05/IMG_0002.jpg", "photo", indexer.CAPTURE_SOURCE_MTIME),
        ]
        for i, (rel, kind, src) in enumerate(rows):
            conn.execute(
                "INSERT INTO photos (relative_path, filename, media_type, capture_date, "
                "thumb_path, preview_path, capture_date_source) VALUES (?,?,?,?,?,?,?)",
                (rel, Path(rel).name, kind, 1_700_000_000_000 + i, "t", "p", src))
        conn.commit()
        return conn

    def test_media_filter(self):
        with tempfile.TemporaryDirectory() as td:
            conn = self._db(Path(td))
            rows = refresh_metadata.select_rows(conn, "video", False, None, 0)
            self.assertEqual([r["media_type"] for r in rows], ["video", "video"])
            conn.close()

    def test_only_suspect_includes_unrecorded_provenance(self):
        with tempfile.TemporaryDirectory() as td:
            conn = self._db(Path(td))
            rows = refresh_metadata.select_rows(conn, "all", True, None, 0)
            self.assertEqual(
                sorted(r["relative_path"] for r in rows),
                ["2021/05/IMG_0002.jpg", "2026/07/IMG_2988.mov", "2026/07/IMG_3022.mov"])
            conn.close()

    def test_path_prefix_scopes_to_subtree(self):
        with tempfile.TemporaryDirectory() as td:
            conn = self._db(Path(td))
            rows = refresh_metadata.select_rows(conn, "all", False, "2026/07", 0)
            self.assertEqual(len(rows), 2)
            conn.close()


class RefreshChangeTests(unittest.TestCase):
    def test_detects_corrected_date(self):
        row = {"capture_date": 1784875174000,
               "capture_date_source": indexer.CAPTURE_SOURCE_QUICKTIME}
        meta = indexer.video_metadata_from_probe(icloud_probe(), Path("IMG_2988.mov"))
        changes = refresh_metadata.compute_changes(row, meta)
        self.assertEqual(changes["capture_date"], 1622305633000)
        self.assertEqual(changes["capture_date_source"], indexer.CAPTURE_SOURCE_APPLE)

    def test_no_changes_when_already_correct(self):
        meta = indexer.video_metadata_from_probe(icloud_probe(), Path("IMG_2988.mov"))
        row = {c: meta.get(c) for c in refresh_metadata.REFRESHABLE}
        self.assertEqual(refresh_metadata.compute_changes(row, meta), {})

    def test_probe_returning_nothing_never_clears_stored_values(self):
        """A partial read must not erase good metadata that is already in the catalog."""
        row = {"capture_date": 1622305633000,
               "capture_date_source": indexer.CAPTURE_SOURCE_APPLE,
               "gps_lat": 55.487, "gps_lon": 52.0354,
               "camera_make": "Apple", "camera_model": "iPhone 12 Pro",
               "duration_ms": 12500, "width": 1920, "height": 1080, "orientation": 1}
        empty = indexer.video_metadata_from_probe(
            {"format": {"tags": {}}, "streams": []}, Path("IMG_2988.mov"))
        self.assertEqual(refresh_metadata.compute_changes(row, empty), {})

    def test_zero_dimensions_are_not_written(self):
        row = {"width": 1920, "height": 1080}
        self.assertEqual(
            refresh_metadata.compute_changes(row, {"width": 0, "height": 0}), {})


class RefileDestinationTests(unittest.TestCase):
    def test_wrongly_filed_video_moves_to_capture_month(self):
        dest = refresh_metadata.refile_destination(
            Path("/lib"), {"relative_path": "2026/07/IMG_2988.mov"},
            epoch_ms(2021, 5, 29, 19, 27, 13))
        self.assertEqual(dest, Path("/lib/2021/05/IMG_2988.mov"))

    def test_already_correct_returns_none(self):
        self.assertIsNone(refresh_metadata.refile_destination(
            Path("/lib"), {"relative_path": "2021/05/IMG_2988.mov"},
            epoch_ms(2021, 5, 29, 19, 27, 13)))


class ProcessVideoRecordTests(unittest.TestCase):
    def test_record_carries_apple_date_and_provenance(self):
        """End-to-end through process_video with ffmpeg/ffprobe stubbed out."""
        import unittest.mock as mock
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            gallery = root / ".gallery"
            thumbs = gallery / "thumbs"
            thumbs.mkdir(parents=True)
            src = root / "IMG_2988.mov"
            src.write_bytes(b"not really a movie")

            from PIL import Image
            def fake_run(cmd, *a, **k):
                # The poster-frame extraction is the only ffmpeg call we need to satisfy.
                out = Path(cmd[-1])
                Image.new("RGB", (16, 9), (1, 2, 3)).save(out, "PNG")
                return mock.Mock(returncode=0, stderr="", stdout="")

            with mock.patch.object(indexer, "ffprobe_info", return_value=icloud_probe()), \
                 mock.patch.object(indexer, "subprocess") as sp, \
                 mock.patch.object(indexer, "write_video_proxy", return_value="v.mp4"):
                sp.run.side_effect = fake_run
                rec = indexer.process_video(
                    src, "2026/07/IMG_2988.mov", 18, 1_784_875_211_000_000_000,
                    "d" * 64, thumbs, gallery, 320, 1600)

        self.assertEqual(rec.capture_date_source, indexer.CAPTURE_SOURCE_APPLE)
        self.assertEqual(datetime.fromtimestamp(rec.capture_date / 1000).year, 2021)
        self.assertEqual(rec.camera_model, "iPhone 12 Pro")
        self.assertEqual(rec.duration_ms, 12500)


class RefreshFullRunTests(unittest.TestCase):
    """End-to-end over a real (photo-only) library — no ffmpeg needed."""

    @staticmethod
    def _index(source: Path) -> int:
        args = indexer.build_parser().parse_args(
            ["--source", str(source), "--no-import", "--workers", "1"])
        return indexer.run(args)

    @staticmethod
    def _refresh(source: Path, *extra) -> int:
        args = refresh_metadata.build_parser().parse_args(
            ["--source", str(source), *extra])
        return refresh_metadata.run(args)

    @staticmethod
    def _manifest(source: Path) -> dict:
        return indexer.read_manifest(
            source / indexer.GALLERY_DIRNAME / indexer.MANIFEST_NAME)

    @classmethod
    def _publish_broken_dates(cls, source: Path) -> dict:
        """Reproduce a library indexed BEFORE the fix: dates wrong, and published that way.

        Corrupting only the work DB would not reproduce it — the manifest would still carry the
        correct fingerprint, and the refresh tool would (rightly) decide the published catalog
        already matches and skip republication.
        """
        gallery = source / indexer.GALLERY_DIRNAME
        conn = indexer.open_db(gallery / indexer.WORK_DB_NAME)
        conn.execute("UPDATE photos SET capture_date = file_mtime_ns/1000000, "
                     "capture_date_source = NULL")
        indexer.build_albums(conn)
        fingerprint = indexer.catalog_fingerprint(conn)
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('content_fingerprint',?)",
                     (fingerprint,))
        conn.commit()
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.close()

        manifest = dict(cls._manifest(source))
        revision = int(manifest["revision"]) + 1
        name = (f"{indexer.CATALOGS_DIRNAME}/{indexer.CATALOG_PREFIX}"
                f"{revision:06d}-{fingerprint[:12]}.db")
        manifest.update({"revision": revision, "catalog": name,
                         "content_fingerprint": fingerprint})
        indexer.publish_catalog(gallery / indexer.WORK_DB_NAME,
                                gallery / indexer.CATALOGS_DIRNAME, Path(name).name,
                                gallery / indexer.MANIFEST_NAME, manifest)
        return manifest

    # Deliberately mis-filed under the "download month", the state the bug leaves behind: the
    # EXIF says 2018, the folder says 2026.
    MISFILED_DIR = "2026/07"
    EXIF_CAPTURES = {"one.jpg": "2018:03:14 09:30:00", "two.jpg": "2019:11:02 21:05:00"}

    def _library(self, d: Path) -> None:
        from PIL import Image
        for i, (name, taken) in enumerate(self.EXIF_CAPTURES.items()):
            p = d / self.MISFILED_DIR / name
            p.parent.mkdir(parents=True, exist_ok=True)
            exif = Image.Exif()
            exif[0x0132] = taken                       # DateTime
            exif[0x8769] = {0x9003: taken}             # Exif IFD → DateTimeOriginal
            exif[0x010F], exif[0x0110] = "TestMake", "TestModel"
            Image.new("RGB", (32, 32), (200 - i * 40, 30 + i * 40, 30)).save(
                p, "JPEG", quality=90, exif=exif)

    def test_refile_is_standalone_and_idempotent(self):
        """--refile must key off the stored date, not off "the date changed in this run".

        Fixing dates in one run and re-filing in a later one is the natural way to use these
        tools; if re-filing only triggered on a same-run date change, that second run would
        silently do nothing and leave every original in the wrong folder.
        """
        with tempfile.TemporaryDirectory() as td:
            src = Path(td)
            self._library(src)
            self.assertEqual(self._index(src), 0)

            # Nothing about the metadata is wrong here — only the folders are.
            self.assertEqual(self._refresh(src, "--refile"), 0)
            moved = sorted(p.relative_to(src).as_posix()
                           for p in src.rglob("*.jpg"))
            self.assertTrue(all(not p.startswith("2026/07/") for p in moved), moved)

            conn = sqlite3.connect(str(src / indexer.GALLERY_DIRNAME / indexer.WORK_DB_NAME))
            rows = conn.execute(
                "SELECT relative_path, capture_date FROM photos ORDER BY relative_path").fetchall()
            conn.close()
            for rel, capture_ms in rows:
                dt = datetime.fromtimestamp(capture_ms / 1000)
                self.assertEqual(Path(rel).parent.as_posix(), f"{dt.year:04d}/{dt.month:02d}")
                self.assertTrue((src / rel).exists(), rel)

            # Second pass has nothing left to do and must not publish another revision.
            revision = self._manifest(src)["revision"]
            self.assertEqual(self._refresh(src, "--refile"), 0)
            self.assertEqual(self._manifest(src)["revision"], revision)

    def test_refresh_preserves_derivative_generation(self):
        """Metadata-only republication must not invalidate the app's image cache."""
        with tempfile.TemporaryDirectory() as td:
            src = Path(td)
            self._library(src)
            self.assertEqual(self._index(src), 0)

            thumbs = src / indexer.GALLERY_DIRNAME / indexer.THUMBS_DIRNAME
            derivatives = {p: p.read_bytes() for p in thumbs.rglob("*.webp")}
            self.assertTrue(derivatives)

            before = self._publish_broken_dates(src)
            self.assertEqual(self._refresh(src), 0)

            after = self._manifest(src)
            self.assertEqual(after["revision"], before["revision"] + 1)
            self.assertEqual(after["derivative_generation"], before["derivative_generation"])
            self.assertEqual(after["derivative_params"], before["derivative_params"])
            for path, data in derivatives.items():
                self.assertEqual(path.read_bytes(), data, f"{path.name} was regenerated")

            # The repaired catalog must hold the EXIF dates again, with provenance recorded.
            conn = sqlite3.connect(str(src / indexer.GALLERY_DIRNAME / after["catalog"]))
            rows = dict(conn.execute("SELECT filename, capture_date_source FROM photos"))
            years = {datetime.fromtimestamp(ms / 1000).year for (ms,) in
                     conn.execute("SELECT capture_date FROM photos")}
            conn.close()
            self.assertEqual(set(rows.values()), {indexer.CAPTURE_SOURCE_EXIF})
            self.assertEqual(years, {2018, 2019})

    def test_published_catalog_stays_valid(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td)
            self._library(src)
            self.assertEqual(self._index(src), 0)
            self._publish_broken_dates(src)
            self.assertEqual(self._refresh(src), 0)

            manifest = self._manifest(src)
            catalog = src / indexer.GALLERY_DIRNAME / manifest["catalog"]
            ok, reason = indexer.validate_catalog_file(catalog)
            self.assertTrue(ok, reason)
            # The manifest's fingerprint must describe the file it points at, or the app rejects
            # the swap as a half-published catalog.
            conn = sqlite3.connect(str(catalog))
            stored = conn.execute(
                "SELECT value FROM meta WHERE key='content_fingerprint'").fetchone()[0]
            conn.close()
            self.assertEqual(stored, manifest["content_fingerprint"])

    def test_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td)
            self._library(src)
            self.assertEqual(self._index(src), 0)
            before = self._manifest(src)

            conn = sqlite3.connect(str(src / indexer.GALLERY_DIRNAME / indexer.WORK_DB_NAME))
            conn.execute("UPDATE photos SET capture_date = 1")
            conn.commit()
            conn.close()

            self.assertEqual(self._refresh(src, "--refile", "--dry-run"), 0)
            self.assertEqual(self._manifest(src)["revision"], before["revision"])
            self.assertTrue(sorted(src.rglob("2026/07/*.jpg")), "originals must not move")
            conn = sqlite3.connect(str(src / indexer.GALLERY_DIRNAME / indexer.WORK_DB_NAME))
            dates = {r[0] for r in conn.execute("SELECT capture_date FROM photos")}
            conn.close()
            self.assertEqual(dates, {1}, "dry run must not write the corrected date")

    def test_refuses_when_library_lock_is_held(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td)
            self._library(src)
            self.assertEqual(self._index(src), 0)
            with indexer.LibraryLock(src / indexer.GALLERY_DIRNAME):
                self.assertEqual(self._refresh(src), 3)


if __name__ == "__main__":
    unittest.main()
