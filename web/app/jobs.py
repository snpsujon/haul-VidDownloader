"""Download jobs: a small in-memory queue run on a thread pool.

Files live in DATA_DIR/jobs/<id>/ only until they're fetched: SEND_GRACE_MIN minutes after the
first complete download (DELETE_AFTER_SEND), or FILE_TTL_MIN minutes after they finish.
Jobs are not persisted; a restart forgets them and clears the folder. The request log
(store.py) keeps a record of each one.
"""
from __future__ import annotations

import secrets
import shutil
import threading
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from . import config, engine, store

FINAL = ("done", "error", "cancelled")


@dataclass
class Job:
    id: str
    url: str
    client: str
    label: str
    options: engine.Options
    source: str = "api"
    title: str | None = None
    thumbnail: str | None = None
    site: str | None = None
    # Playlist jobs: [{"url", "title"}]; the files are zipped at the end.
    items: list[dict] | None = None
    item: int = 0
    failed_items: list[dict] = field(default_factory=list)
    status: str = "queued"   # queued | running | done | error | cancelled
    stage: str = "queued"    # queued | downloading | merging | converting | subtitles | tagging | processing | zipping | done
    downloaded: int = 0
    total: int | None = None
    speed: float | None = None
    eta: int | None = None
    error: str | None = None
    cookie_problem: bool = False
    file: Path | None = None
    created: float = field(default_factory=time.time)
    started: float | None = None
    finished: float | None = None
    sent: float | None = None
    version: int = 0
    cancel: threading.Event = field(default_factory=threading.Event)
    # yt-dlp reports each part (video, then audio) from zero; keep earlier parts' bytes.
    _parts: dict = field(default_factory=dict)

    @property
    def kind(self) -> str:
        return self.options.kind

    @property
    def percent(self) -> float | None:
        if self.status == "done":
            return 100.0
        if self.items:
            n = len(self.items)
            inner = (self.downloaded / self.total) if self.total else 0
            return round(min((max(self.item - 1, 0) + min(inner, 1)) / n * 100, 99.9), 1)
        if self.total:
            return round(min(self.downloaded / self.total * 100, 99.9), 1)
        return None

    @property
    def expires(self) -> float | None:
        if not self.finished:
            return None
        ttl = self.finished + config.FILE_TTL_MIN * 60
        if self.sent and config.DELETE_AFTER_SEND:
            return min(ttl, self.sent + config.SEND_GRACE_MIN * 60)
        return ttl

    def public(self) -> dict:
        o = self.options
        return {
            "id": self.id,
            "url": self.url,
            "kind": o.kind,
            "label": self.label,
            "title": self.title,
            "thumbnail": self.thumbnail,
            "status": self.status,
            "stage": self.stage,
            "percent": self.percent,
            "downloaded": self.downloaded,
            "total": self.total,
            "speed": self.speed,
            "eta": self.eta,
            "error": self.error,
            "cookie_problem": self.cookie_problem,
            "playlist": {"item": self.item, "count": len(self.items), "failed": self.failed_items} if self.items else None,
            "trim": {"start": o.start, "end": o.end} if o.start is not None or o.end is not None else None,
            "subtitles": o.subtitles or None,
            "filename": self.file.name if self.file else None,
            "size": self.file.stat().st_size if self.file and self.file.exists() else None,
            "file_url": f"/api/jobs/{self.id}/file" if self.status == "done" else None,
            "created": self.created,
            "finished": self.finished,
            "expires": self.expires if self.status == "done" else None,
        }


class LimitReached(Exception):
    pass


class JobManager:
    def __init__(self, data_dir: Path) -> None:
        self.root = data_dir / "jobs"
        shutil.rmtree(self.root, ignore_errors=True)
        self.root.mkdir(parents=True, exist_ok=True)
        self.jobs: dict[str, Job] = {}
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=config.MAX_CONCURRENT, thread_name_prefix="dl")
        self._stop = threading.Event()
        threading.Thread(target=self._sweeper, daemon=True).start()

    def active_for(self, client: str) -> int:
        return sum(1 for j in self.jobs.values() if j.client == client and j.status not in FINAL)

    def active(self) -> int:
        return sum(1 for j in self.jobs.values() if j.status not in FINAL)

    def create(self, *, url: str, client: str, label: str, options: engine.Options, info: dict | None,
               items: list[dict] | None = None, source: str = "api", ip: str | None = None,
               user_agent: str | None = None, client_version: str | None = None) -> Job:
        info = info or {}
        with self.lock:
            if self.active_for(client) >= config.MAX_ACTIVE_PER_CLIENT:
                raise LimitReached()
            job = Job(
                id=secrets.token_urlsafe(12), url=url, client=client, label=label, options=options,
                source=source, title=info.get("title"), thumbnail=info.get("thumbnail") or _first_thumb(items),
                site=info.get("extractor_key") or info.get("extractor"), items=items,
            )
            self.jobs[job.id] = job
        store.log(job=job.id, ip=ip, user_agent=(user_agent or "")[:300], source=source,
                  client_version=client_version, url=url, title=job.title, site=job.site,
                  kind=options.kind + (" playlist" if items else ""), label=label, status="queued")
        self.pool.submit(self._run, job)
        return job

    def get(self, job_id: str) -> Job | None:
        return self.jobs.get(job_id)

    def cancel(self, job: Job) -> None:
        job.cancel.set()
        if job.status in FINAL:
            self.remove(job)
        elif job.status == "queued":
            self._finish(job, "cancelled")

    def _bump(self, job: Job, **changes) -> None:
        for k, v in changes.items():
            setattr(job, k, v)
        job.version += 1

    def _finish(self, job: Job, status: str, **changes) -> None:
        self._bump(job, status=status, stage=status if status == "done" else job.stage,
                   finished=time.time(), speed=None, eta=None, **changes)
        size = job.file.stat().st_size if job.file and job.file.exists() else None
        store.finish(job.id, status=status, error=job.error, size=size, title=job.title,
                     seconds=round(job.finished - (job.started or job.created), 1))

    def _progress(self, job: Job):
        def on_progress(p: dict) -> None:
            if p["stage"] == "downloading":
                part = p.get("part") or "0"
                job._parts[part] = (p["downloaded"], p["total"])
                done = sum(d for d, _ in job._parts.values())
                totals = [t for _, t in job._parts.values()]
                total = sum(t or 0 for t in totals) if all(totals) else None
                self._bump(job, stage="downloading", downloaded=done, total=total,
                           speed=p.get("speed"), eta=p.get("eta"))
            else:
                self._bump(job, stage=p["stage"], speed=None, eta=None)
        return on_progress

    def _run(self, job: Job) -> None:
        if job.cancel.is_set():
            return
        self._bump(job, status="running", stage="downloading", started=time.time())
        workdir = self.root / job.id
        try:
            if job.items:
                path = self._run_playlist(job, workdir)
            else:
                path = engine.download(job.url, workdir, job.options,
                                       on_progress=self._progress(job), cancel=job.cancel)
            size = path.stat().st_size
            self._finish(job, "done", file=path, downloaded=size, total=size)
        except engine.Cancelled:
            self._finish(job, "cancelled")
            shutil.rmtree(workdir, ignore_errors=True)
        except engine.EngineError as e:
            self._finish(job, "error", error=str(e), cookie_problem=e.cookie_problem)
            shutil.rmtree(workdir, ignore_errors=True)
        except Exception as e:  # never leave a job stuck on "running"
            self._finish(job, "error", error=engine.friendly_error(e))
            shutil.rmtree(workdir, ignore_errors=True)

    def _run_playlist(self, job: Job, workdir: Path) -> Path:
        files: list[Path] = []
        for i, entry in enumerate(job.items, start=1):
            if job.cancel.is_set():
                raise engine.Cancelled()
            job._parts = {}
            self._bump(job, item=i, stage="downloading", downloaded=0, total=None)
            try:
                files.append(engine.download(entry["url"], workdir / f"{i:03d}", job.options,
                                             on_progress=self._progress(job), cancel=job.cancel))
            except engine.EngineError as e:
                job.failed_items.append({"index": i, "title": entry.get("title"), "error": str(e)})
                if e.cookie_problem:
                    job.cookie_problem = True
        if not files:
            raise engine.EngineError("None of the videos in this playlist could be downloaded.",
                                     cookie_problem=job.cookie_problem)
        if len(files) == 1:
            return files[0]
        self._bump(job, stage="zipping", speed=None, eta=None)
        name = _safe_name(job.title or "playlist") + ".zip"
        zpath = workdir / name
        # Media is already compressed; storing is as small and much faster.
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
            for i, f in enumerate(files, start=1):
                if job.cancel.is_set():
                    raise engine.Cancelled()
                z.write(f, f"{i:03d} {f.name}")
                f.unlink(missing_ok=True)
        return zpath

    def remove(self, job: Job) -> None:
        with self.lock:
            self.jobs.pop(job.id, None)
        shutil.rmtree(self.root / job.id, ignore_errors=True)

    def mark_sent(self, job: Job) -> None:
        """Called once a download of the file has completed."""
        if job.sent is None:
            self._bump(job, sent=time.time())

    def sweep(self) -> None:
        now = time.time()
        for job in list(self.jobs.values()):
            if job.expires and job.expires < now:
                self.remove(job)

    def disk_usage(self) -> int:
        return sum(f.stat().st_size for f in self.root.rglob("*") if f.is_file())

    def _sweeper(self) -> None:
        while not self._stop.wait(30):
            self.sweep()

    def shutdown(self) -> None:
        self._stop.set()
        for j in self.jobs.values():
            j.cancel.set()
        self.pool.shutdown(wait=False, cancel_futures=True)


def _first_thumb(items: list[dict] | None) -> str | None:
    for e in items or []:
        if e.get("thumbnail"):
            return e["thumbnail"]
    return None


def _safe_name(s: str) -> str:
    keep = "".join(c if c.isalnum() or c in " -_.()[]" else "_" for c in s).strip()
    return (keep or "playlist")[:120]
