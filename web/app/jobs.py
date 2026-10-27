"""Download jobs: a small in-memory queue run on a thread pool.

Files live in DATA_DIR/jobs/<id>/ and are removed FILE_TTL_MIN minutes after they finish.
Jobs are not persisted; a restart forgets them and clears the folder.
"""
from __future__ import annotations

import secrets
import shutil
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from . import config, engine, media


@dataclass
class Job:
    id: str
    url: str
    client: str
    kind: str                # video | audio
    label: str
    selector: str
    container: str
    audio_format: str
    audio_bitrate: int
    embed_cover: bool
    title: str | None = None
    thumbnail: str | None = None
    status: str = "queued"   # queued | running | done | error | cancelled
    stage: str = "queued"    # queued | downloading | merging | converting | tagging | processing | done
    downloaded: int = 0
    total: int | None = None
    speed: float | None = None
    eta: int | None = None
    error: str | None = None
    file: Path | None = None
    created: float = field(default_factory=time.time)
    finished: float | None = None
    version: int = 0
    cancel: threading.Event = field(default_factory=threading.Event)
    # yt-dlp reports each part (video, then audio) from zero; keep earlier parts' bytes.
    _parts: dict = field(default_factory=dict)

    @property
    def percent(self) -> float | None:
        if self.status == "done":
            return 100.0
        if self.total:
            return round(min(self.downloaded / self.total * 100, 99.9), 1)
        return None

    def public(self) -> dict:
        return {
            "id": self.id,
            "url": self.url,
            "kind": self.kind,
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
            "filename": self.file.name if self.file else None,
            "size": self.file.stat().st_size if self.file and self.file.exists() else None,
            "file_url": f"/api/jobs/{self.id}/file" if self.status == "done" else None,
            "created": self.created,
            "finished": self.finished,
            "expires": (self.finished + config.FILE_TTL_MIN * 60) if self.finished and self.status == "done" else None,
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
        return sum(1 for j in self.jobs.values() if j.client == client and j.status in ("queued", "running"))

    def create(self, *, url: str, client: str, kind: str, info: dict | None, height: int | None,
               container: str, audio_format: str, audio_bitrate: int, language: str | None,
               embed_cover: bool) -> Job:
        with self.lock:
            if self.active_for(client) >= config.MAX_ACTIVE_PER_CLIENT:
                raise LimitReached()
            if info is not None:
                selector, label = media.choose_selector(info, kind, height=height, container=container,
                                                        audio_format=audio_format, language=language)
            else:
                lf = f"[language={language}]" if language else ""
                if kind == "audio":
                    selector, label = f"bestaudio{lf}/bestaudio/best", audio_format.upper()
                else:
                    h = f"[height<={height}]" if height else ""
                    selector = f"bv*{h}+ba{lf}/bv*{h}+ba/b{h}/b"
                    label = f"{height}p {container.upper()}" if height else f"Best {container.upper()}"
            if kind == "audio" and audio_format == "mp3":
                label = f"MP3 {audio_bitrate}k"
            job = Job(
                id=secrets.token_urlsafe(12), url=url, client=client, kind=kind, label=label,
                selector=selector, container=container, audio_format=audio_format,
                audio_bitrate=audio_bitrate, embed_cover=embed_cover,
                title=(info or {}).get("title"), thumbnail=(info or {}).get("thumbnail"),
            )
            self.jobs[job.id] = job
        self.pool.submit(self._run, job)
        return job

    def get(self, job_id: str) -> Job | None:
        return self.jobs.get(job_id)

    def cancel(self, job: Job) -> None:
        job.cancel.set()
        if job.status in ("done", "error", "cancelled"):
            self._remove(job)
        elif job.status == "queued":
            self._finish(job, "cancelled")

    def _bump(self, job: Job, **changes) -> None:
        for k, v in changes.items():
            setattr(job, k, v)
        job.version += 1

    def _finish(self, job: Job, status: str, **changes) -> None:
        self._bump(job, status=status, stage=status if status == "done" else job.stage,
                   finished=time.time(), speed=None, eta=None, **changes)

    def _run(self, job: Job) -> None:
        if job.cancel.is_set():
            return
        self._bump(job, status="running", stage="downloading")
        workdir = self.root / job.id

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

        try:
            path = engine.download(
                job.url, workdir, kind=job.kind, selector=job.selector, container=job.container,
                audio_format=job.audio_format, audio_bitrate=job.audio_bitrate,
                embed_cover=job.embed_cover, on_progress=on_progress, cancel=job.cancel,
            )
            size = path.stat().st_size
            self._finish(job, "done", file=path, downloaded=size, total=size)
        except engine.Cancelled:
            self._finish(job, "cancelled")
            shutil.rmtree(workdir, ignore_errors=True)
        except engine.EngineError as e:
            self._finish(job, "error", error=str(e))
            shutil.rmtree(workdir, ignore_errors=True)
        except Exception as e:  # never leave a job stuck on "running"
            self._finish(job, "error", error=engine.friendly_error(e))
            shutil.rmtree(workdir, ignore_errors=True)

    def _remove(self, job: Job) -> None:
        with self.lock:
            self.jobs.pop(job.id, None)
        shutil.rmtree(self.root / job.id, ignore_errors=True)

    def sweep(self) -> None:
        cutoff = time.time() - config.FILE_TTL_MIN * 60
        for job in list(self.jobs.values()):
            if job.finished and job.finished < cutoff:
                self._remove(job)

    def _sweeper(self) -> None:
        while not self._stop.wait(60):
            self.sweep()

    def shutdown(self) -> None:
        self._stop.set()
        for j in self.jobs.values():
            j.cancel.set()
        self.pool.shutdown(wait=False, cancel_futures=True)
