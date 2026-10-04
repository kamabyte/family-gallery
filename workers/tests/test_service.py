"""Tests for the unattended service loop (service.py) and the Imports settle window.

Temp dirs and generated fixtures only — no NAS, no real media, no network.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from PIL import Image  # noqa: E402

import indexer  # noqa: E402
import service  # noqa: E402


def make_jpeg(path: Path, age_s: float = 0.0) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (32, 32), (200, 40, 40)).save(path, "JPEG")
    if age_s:
        # mtime can be backdated, ctime cannot — so "settled" is tested via a long window
        # rather than an old file.
        t = time.time() - age_s
        os.utime(path, (t, t))


class SettleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_zero_window_settles_everything(self):
        f = self.root / "a.jpg"
        make_jpeg(f)
        self.assertTrue(indexer.is_settled(f, 0))

    def test_fresh_write_is_not_settled_even_with_backdated_mtime(self):
        # Finder preserves the original mtime on a copy in progress; ctime still moves.
        f = self.root / "a.jpg"
        make_jpeg(f, age_s=86400)
        self.assertFalse(indexer.is_settled(f, 300))
        self.assertTrue(indexer.is_settled(f, 300, now=time.time() + 301))

    def test_ingest_leaves_unsettled_files_in_imports(self):
        src = self.root / "Imports" / "IMG_1.jpg"
        make_jpeg(src)
        stats = indexer.Stats()
        indexer.run_ingest(self.root, "Imports", set(), stats, settle_s=300)
        self.assertTrue(src.exists())
        self.assertEqual((stats.imported, stats.import_waiting), (0, 1))

        stats = indexer.Stats()
        indexer.run_ingest(self.root, "Imports", set(), stats, settle_s=0)
        self.assertFalse(src.exists())
        self.assertEqual(stats.imported, 1)


class ImportsSignatureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_imports_dir_is_empty(self):
        self.assertEqual(service.imports_signature(self.root, "Imports", 0), frozenset())

    def test_ignores_duplicates_hidden_and_non_media(self):
        imports = self.root / "Imports"
        make_jpeg(imports / "keep.jpg")
        make_jpeg(imports / indexer.DUPLICATES_DIRNAME / "dupe.jpg")
        make_jpeg(imports / "._keep.jpg")
        (imports / "notes.txt").write_text("x")
        sig = service.imports_signature(self.root, "Imports", 0)
        self.assertEqual({p for p, _, _ in sig}, {"keep.jpg"})

    def test_unsettled_files_do_not_trigger(self):
        make_jpeg(self.root / "Imports" / "copying.jpg")
        self.assertEqual(service.imports_signature(self.root, "Imports", 300), frozenset())

    def test_signature_changes_when_a_file_changes(self):
        f = self.root / "Imports" / "a.jpg"
        make_jpeg(f)
        before = service.imports_signature(self.root, "Imports", 0)
        t = time.time() - 10
        os.utime(f, (t, t))
        self.assertNotEqual(before, service.imports_signature(self.root, "Imports", 0))


class HealthTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.status = Path(self.tmp.name) / "status.json"

    def tearDown(self):
        self.tmp.cleanup()

    def check(self, status):
        service.write_status(self.status, status)
        return service.health(self.status)[0]

    def test_missing_status_is_unhealthy(self):
        self.assertFalse(service.health(self.status)[0])

    def test_outcomes(self):
        self.assertTrue(self.check({"running": False, "last_exit": None}))
        self.assertTrue(self.check({"running": False, "last_exit": 0}))
        self.assertTrue(self.check({"running": False, "last_exit": service.EXIT_LOCKED}))
        self.assertFalse(self.check({"running": False, "last_exit": 1}))
        self.assertFalse(self.check({"running": False, "last_exit": 2}))
        # A long first pass after a failure is healthy while it runs.
        self.assertTrue(self.check({"running": True, "last_exit": 1}))

    def test_status_file_is_valid_json(self):
        service.write_status(self.status, {"running": True})
        self.assertEqual(json.loads(self.status.read_text()), {"running": True})


class CommandTests(unittest.TestCase):
    def test_command_passes_settle_workers_and_nice(self):
        env = {"GALLERY_SOURCE": "/photos", "IMPORT_SETTLE": "120", "WORKERS": "3", "NICE": "5"}
        old = {k: os.environ.get(k) for k in env}
        os.environ.update(env)
        try:
            cmd = service.Config().command()
        finally:
            for k, v in old.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
        self.assertEqual(cmd[:3], ["nice", "-n", "5"])
        self.assertIn("--import-settle", cmd)
        self.assertEqual(cmd[cmd.index("--import-settle") + 1], "120")
        self.assertEqual(cmd[cmd.index("--workers") + 1], "3")
        self.assertEqual(cmd[cmd.index("--source") + 1], "/photos")


if __name__ == "__main__":
    unittest.main()
