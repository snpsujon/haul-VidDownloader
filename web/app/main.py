"""Haul web: the website and its REST API, in one FastAPI app."""
from __future__ import annotations

import asyncio
import json
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import config, engine, media, security
from .jobs import JobManager, LimitReached

STATIC = Path(__file__).resolve().parent.parent / "static"


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.jobs = JobManager(config.DATA_DIR)
    app.state.info_cache = {}
    yield
    app.state.jobs.shutdown()


app = FastAPI(
    title="Haul API",
    version="1.0.0",
    summary="Fetch link info and download video or audio with yt-dlp and FFmpeg.",
    description=(
        "Send a link to `/api/info` to get its title and format choices, start a download with "
        "`POST /api/jobs`, follow it with `GET /api/jobs/{id}` or the `/events` stream, then fetch "
        "the file from `/api/jobs/{id}/file`. When the server has API keys set, send one in the "
        "`X-API-Key` header (or `?key=` for links and event streams)."
    ),
    lifespan=lifespan,
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    openapi_url="/api/openapi.json",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=config.CORS_ORIGINS,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Content-Type", "X-API-Key"],
    expose_headers=["Content-Disposition", "Content-Length"],
)


# Auth and client id ------------------------------------------------------------------------

def client_id(request: Request) -> str:
    if config.TRUST_PROXY_HEADERS:
        fwd = request.headers.get("x-forwarded-for")
        if fwd:
            return fwd.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def require_key(request: Request, key: str | None = Query(None, description="API key, for links and event streams")) -> str:
    """Returns the caller's id: their API key when keys are on, otherwise their IP."""
    if not config.API_KEYS:
        return client_id(request)
    given = request.headers.get("x-api-key") or key
    if given not in config.API_KEYS:
        raise HTTPException(401, "A valid API key is needed. Send it in the X-API-Key header.")
    return "key:" + given


# Models ------------------------------------------------------------------------------------

Container = Literal["mp4", "mkv", "webm"]
AudioFormat = Literal["mp3", "m4a", "opus"]


class InfoRequest(BaseModel):
    url: str = Field(..., examples=["https://www.youtube.com/watch?v=dQw4w9WgXcQ"])
    container: Container = "mp4"
    language: str | None = Field(None, description="Audio language code from `audio_languages`, e.g. `es`")
    playlist: bool = Field(True, description="List playlist entries instead of only the linked video")


class JobRequest(BaseModel):
    url: str
    kind: Literal["video", "audio"] = "video"
    height: int | None = Field(None, description="Video height, e.g. 1080. Leave out for the recommended one.")
    container: Container = "mp4"
    audio_format: AudioFormat = "mp3"
    audio_bitrate: Literal[128, 192, 256, 320] = 192
    language: str | None = Field(None, description="Audio language code, e.g. `es`")
    embed_cover: bool = Field(True, description="Audio only: embed the thumbnail as cover art")


# Info cache: the website fetches info, then starts a job for the same link right after.

def _cache_get(url: str, playlist: bool) -> dict | None:
    hit = app.state.info_cache.get((url, playlist))
    if hit and hit[0] > time.time():
        return hit[1]
    return None


def _cache_put(url: str, playlist: bool, info: dict) -> None:
    cache = app.state.info_cache
    if len(cache) > 500:
        now = time.time()
        for k in [k for k, v in cache.items() if v[0] < now] or list(cache)[:100]:
            cache.pop(k, None)
    cache[(url, playlist)] = (time.time() + config.INFO_CACHE_MIN * 60, info)


async def _info(url: str, playlist: bool) -> dict:
    try:
        url = await run_in_threadpool(security.check_url, url)
    except security.BadLink as e:
        raise HTTPException(400, str(e))
    info = _cache_get(url, playlist)
    if info is None:
        try:
            info = await run_in_threadpool(engine.extract_info, url, playlist)
        except engine.EngineError as e:
            raise HTTPException(422, str(e))
        _cache_put(url, playlist, info)
    return info


# Routes ------------------------------------------------------------------------------------

@app.get("/api/health", tags=["server"], summary="Server status and tool versions")
async def health():
    return {
        "ok": True,
        **engine.versions(),
        "auth": bool(config.API_KEYS),
        "limits": {
            "max_filesize_mb": config.MAX_FILESIZE_MB,
            "max_duration_min": config.MAX_DURATION_MIN,
            "file_ttl_min": config.FILE_TTL_MIN,
            "max_active_per_client": config.MAX_ACTIVE_PER_CLIENT,
        },
    }


@app.post("/api/info", tags=["media"], summary="Title, thumbnail and format choices for a link")
async def info(body: InfoRequest, _: str = Depends(require_key)):
    data = await _info(body.url, body.playlist)
    return media.summarize(data, body.container, body.language)


@app.get("/api/info", tags=["media"], summary="Same as POST /api/info, with query parameters")
async def info_get(url: str, container: Container = "mp4", language: str | None = None,
                   playlist: bool = True, _: str = Depends(require_key)):
    data = await _info(url, playlist)
    return media.summarize(data, container, language)


@app.post("/api/jobs", tags=["jobs"], status_code=201, summary="Start a download")
async def create_job(body: JobRequest, who: str = Depends(require_key)):
    # Reuse the info the website just fetched when the link wasn't a playlist.
    data = _cache_get(body.url.strip(), True)
    if data is None or data.get("_type") == "playlist":
        data = await _info(body.url, False)
    if data.get("_type") == "playlist":
        raise HTTPException(400, "That's a playlist. Start a job for each video in it.")
    if data.get("is_live"):
        raise HTTPException(422, "Live streams can't be downloaded until they end.")
    dur = data.get("duration")
    if dur and dur > config.MAX_DURATION_MIN * 60:
        raise HTTPException(422, f"This is longer than the server limit ({config.MAX_DURATION_MIN} minutes).")
    try:
        job = app.state.jobs.create(
            url=body.url, client=who, kind=body.kind, info=data, height=body.height,
            container=body.container, audio_format=body.audio_format,
            audio_bitrate=body.audio_bitrate, language=body.language, embed_cover=body.embed_cover,
        )
    except LimitReached:
        raise HTTPException(429, f"You already have {config.MAX_ACTIVE_PER_CLIENT} downloads running. Wait for one to finish.")
    return job.public()


def _job(job_id: str, who: str):
    job = app.state.jobs.get(job_id)
    # Job ids are unguessable; with keys on, a job also belongs to the key that made it.
    if job is None or (config.API_KEYS and job.client != who):
        raise HTTPException(404, "No such download. Finished files are removed after a while.")
    return job


@app.get("/api/jobs/{job_id}", tags=["jobs"], summary="A download's status and progress")
async def get_job(job_id: str, who: str = Depends(require_key)):
    return _job(job_id, who).public()


@app.get("/api/jobs/{job_id}/events", tags=["jobs"], summary="Server-sent events with progress until the job ends")
async def job_events(job_id: str, request: Request, who: str = Depends(require_key)):
    job = _job(job_id, who)

    async def stream():
        seen = -1
        last_send = 0.0
        while True:
            if await request.is_disconnected():
                return
            if job.version != seen:
                seen = job.version
                last_send = time.time()
                yield f"event: progress\ndata: {json.dumps(job.public())}\n\n"
                if job.status in ("done", "error", "cancelled"):
                    return
            elif time.time() - last_send > 15:
                last_send = time.time()
                yield ": keep-alive\n\n"
            await asyncio.sleep(0.4)

    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.get("/api/jobs/{job_id}/file", tags=["jobs"], summary="Download the finished file")
async def job_file(job_id: str, who: str = Depends(require_key)):
    job = _job(job_id, who)
    if job.status != "done" or not job.file or not job.file.exists():
        raise HTTPException(409, "The file isn't ready yet.")
    return FileResponse(job.file, filename=job.file.name, media_type="application/octet-stream")


@app.delete("/api/jobs/{job_id}", tags=["jobs"], summary="Cancel a download, or delete its finished file")
async def delete_job(job_id: str, who: str = Depends(require_key)):
    job = _job(job_id, who)
    app.state.jobs.cancel(job)
    return {"ok": True}


# Website -----------------------------------------------------------------------------------

app.mount("/", StaticFiles(directory=STATIC, html=True), name="site")
