#!/usr/bin/env python3
"""
Unattended indexer: the long-running process of the NAS container.

Runs indexer.py as a child process:

  * at start, then every INDEX_INTERVAL seconds (catches anything changed outside Imports);
  * as soon as Imports holds settled media it has not already tried — dropping photos into
    Imports over SMB is the whole workflow, so it should not wait hours for the next pass.

Runs never overlap (one child at a time, plus the indexer's own library lock), and a failed
run backs off for RETRY_INTERVAL instead of retrying every poll. Each run's outcome goes to
STATUS_FILE; `service.py --health` turns it into the container healthcheck, so a failing
indexer shows up as an unhealthy container rather than only as a line in the logs.

Configuration (environment):

  GALLERY_SOURCE   library root inside the container           /photos
  IMPORT_DIR       dropbox folder, relative to the source      Imports
  IMPORT_SETTLE    seconds an Imports file must be untouched   300
  INDEX_INTERVAL   seconds between full passes                 21600 (6 h)
  POLL_INTERVAL    seconds between Imports checks              60
  RETRY_INTERVAL   seconds to wait after a failed run          900
  WORKERS          indexer worker processes                    2
  NICE             CPU niceness of the indexer                 10
  STATUS_FILE      where the last outcome is recorded          /tmp/indexer-status.json
"""
from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import FrozenSet, Optional, Tuple

import indexer

HERE = Path(__file__).resolve().parent

# indexer.run() exit codes: 0 ok, 2 source missing, 3 library lock held. A held lock means
# someone is running the indexer by hand (docker exec) — not a failure of this service.
EXIT_LOCKED = 3
OK_EXITS = (0, EXIT_LOCKED)

ImportsSignature = FrozenSet[Tuple[str, int, int]]


def env_int(name: str, default: int) -> int:
    value = os.environ.get(name, "").strip()
    return int(value) if value else default


class Config:
    def __init__(self) -> None:
        self.source = Path(os.environ.get("GALLERY_SOURCE", "/photos"))
        self.import_dir = os.environ.get("IMPORT_DIR", indexer.DEFAULT_IMPORT_DIR)
        self.import_settle = env_int("IMPORT_SETTLE", 300)
        self.index_interval = env_int("INDEX_INTERVAL", 6 * 3600)
        self.poll_interval = env_int("POLL_INTERVAL", 60)
        self.retry_interval = env_int("RETRY_INTERVAL", 900)
        self.workers = env_int("WORKERS", 2)
        self.nice = env_int("NICE", 10)
        self.status_file = Path(os.environ.get("STATUS_FILE", "/tmp/indexer-status.json"))

    def command(self) -> list:
        cmd = [sys.executable, str(HERE / "indexer.py"),
               "--source", str(self.source),
               "--import-dir", self.import_dir,
               "--import-settle", str(self.import_settle),
               "--workers", str(self.workers)]
        return (["nice", "-n", str(self.nice)] + cmd) if self.nice else cmd


def imports_signature(source: Path, import_dir: str, settle_s: float) -> ImportsSignature:
    """Settled media files waiting in Imports, as (path, size, mtime) triples.

    Compared against the signature of the last run, so a file the indexer could not ingest
    (it stays in Imports) does not re-trigger a full pass every poll — only new or changed
    files do.
    """
    idir = source / import_dir
    if not idir.is_dir():
        return frozenset()
    found = set()
    for path in indexer.iter_media(idir, skip_names={indexer.DUPLICATES_DIRNAME}):
        try:
            if not indexer.is_settled(path, settle_s):
                continue
            st = path.stat()
        except OSError:
            continue  # moved or deleted between listing and stat
        found.add((path.relative_to(idir).as_posix(), st.st_size, st.st_mtime_ns))
    return frozenset(found)


def write_status(path: Path, status: dict) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(status))
    os.replace(tmp, path)


def health(status_file: Path) -> Tuple[bool, str]:
    """(healthy, reason) for the container healthcheck."""
    try:
        status = json.loads(status_file.read_text())
    except (OSError, ValueError) as e:
        return False, f"no status ({e})"
    code = status.get("last_exit")
    if status.get("running"):
        return True, "indexing"
    if code is None:
        return True, "waiting for the first run"
    if code in OK_EXITS:
        return True, f"last run ok (exit {code})"
    return False, f"last run failed (exit {code}) at {status.get('last_finished')}"


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


class Service:
    def __init__(self, config: Config) -> None:
        self.config = config
        self.stop = threading.Event()
        self.child: Optional[subprocess.Popen] = None
        self.status: dict = {"running": False, "last_exit": None}

    def save(self, **changes) -> None:
        self.status.update(changes)
        write_status(self.config.status_file, self.status)

    def run_indexer(self) -> int:
        self.save(running=True, last_started=now_iso())
        # Own process group: on shutdown the interrupt reaches the pool workers and ffmpeg
        # too, not just the indexer's main process.
        self.child = subprocess.Popen(self.config.command(), start_new_session=True)
        code = self.child.wait()
        self.child = None
        self.save(running=False, last_exit=code, last_finished=now_iso())
        return code

    def shutdown(self, *_):
        self.stop.set()
        child = self.child
        if child is not None and child.poll() is None:
            # SIGINT is what the indexer handles: it stops before publishing, so the
            # previous catalog stays the published one.
            try:
                os.killpg(child.pid, signal.SIGINT)
            except ProcessLookupError:
                pass

    def loop(self) -> None:
        cfg = self.config
        indexer.log(f"service: watching {cfg.source / cfg.import_dir} every {cfg.poll_interval}s, "
                    f"full pass every {cfg.index_interval}s, TZ={os.environ.get('TZ', 'unset')}")
        self.save()
        next_full = time.monotonic()           # first pass right away
        not_before = 0.0                       # backoff after a failure
        last_imports: ImportsSignature = frozenset()

        while not self.stop.is_set():
            now = time.monotonic()
            imports = imports_signature(cfg.source, cfg.import_dir, cfg.import_settle)
            new_imports = bool(imports) and imports != last_imports
            if now >= not_before and (now >= next_full or new_imports):
                reason = "new files in Imports" if new_imports and now < next_full else "scheduled pass"
                indexer.log(f"service: running indexer ({reason})")
                last_imports = imports
                code = self.run_indexer()
                if self.stop.is_set():
                    break
                done = time.monotonic()
                if code in OK_EXITS:
                    indexer.log(f"service: indexer finished (exit {code})")
                    next_full = done + cfg.index_interval
                else:
                    indexer.log(f"service: indexer FAILED (exit {code}); "
                                f"retrying in {cfg.retry_interval}s")
                    not_before = done + cfg.retry_interval
                    next_full = not_before
                    last_imports = frozenset()  # retry the same files after the backoff
            self.stop.wait(cfg.poll_interval)
        indexer.log("service: stopped")


def main() -> None:
    config = Config()
    if sys.argv[1:] == ["--health"]:
        ok, reason = health(config.status_file)
        print(reason)
        sys.exit(0 if ok else 1)
    service = Service(config)
    signal.signal(signal.SIGTERM, service.shutdown)
    signal.signal(signal.SIGINT, service.shutdown)
    service.loop()


if __name__ == "__main__":
    main()
