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
from starlette.background import BackgroundTask
from pydantic import BaseModel, Field, model_validator

from . import config, cookies, engine, media, security, store, upkeep
from .jobs import FINAL, JobManager, LimitReached

STATIC = Path(__file__).resolve().parent.parent / "static"


@asynccontextmanager
async def lifespan(app: FastAPI):
    cookies.init()
    store.init()
    app.state.jobs = JobManager(config.DATA_DIR)
    app.state.info_cache = {}
    upkeep.start(app.state.jobs)
    yield
    upkeep.stop()
    app.state.jobs.shutdown()
    store.close()
    pair.close()


app = FastAPI(
    title="Haul API",
    version="2.0.0",
    summary="Fetch link info and download video or audio with yt-dlp and FFmpeg.",
    description=(
        "Send a link to `/api/info` to get its title and format choices, start a download with "
        "`POST /api/jobs`, follow it with `GET /api/jobs/{id}` or the `/events` stream, then fetch "
        "the file from `/api/jobs/{id}/file`. When the server has API keys set, send one in the "
        "`X-API-Key` header (or `?key=` for links and event streams). Send `X-Haul-Client: "
        "app/1.2.0` (or `extension/…`) so the admin log shows where requests come from."
    ),
    lifespan=lifespan,
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    openapi_url="/api/openapi.json",
)
@app.middleware("http")
async def revalidate_pages(request: Request, call_next):
    """Browsers check for a newer page, script or style each time, so updates show at once."""
    response = await call_next(request)
    if not request.url.path.startswith("/api/") and request.url.path.rsplit(".", 1)[-1] in ("html", "js", "css", "webmanifest", "/") \
            or response.headers.get("content-type", "").startswith("text/html"):
        response.headers.setdefault("Cache-Control", "no-cache")
    return response


app.add_middleware(
    CORSMiddleware,
    # The Haul extension's pages are always allowed, even when the list is narrowed.
    allow_origins=config.CORS_ORIGINS if "*" in config.CORS_ORIGINS
    else config.CORS_ORIGINS + [f"chrome-extension://{i}" for i in config.EXTENSION_IDS],
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Content-Type", "X-API-Key", "X-Admin-Token", "X-Haul-Client", "X-Session-ID",
                   "X-Extension-Request", "X-Extension-Version", "X-Platform", "X-Language"],
    expose_headers=["Content-Disposition", "Content-Length"],
)


# Auth and client id ------------------------------------------------------------------------

def client_ip(request: Request) -> str:
    if config.TRUST_PROXY_HEADERS:
        fwd = request.headers.get("x-forwarded-for")
        if fwd:
            return fwd.split(",")[0].strip()
        real = request.headers.get("x-real-ip")
        if real:
            return real.strip()
    return request.client.host if request.client else "unknown"


def client_source(request: Request) -> tuple[str, str | None]:
    """Where a request comes from: web, app, extension, legacy-extension or api."""
    tag = request.headers.get("x-haul-client")
    if tag:
        name, _, ver = tag.partition("/")
        return name.strip().lower()[:20] or "api", ver.strip()[:20] or None
    if request.headers.get("x-extension-request") == "true":
        return "legacy-extension", request.headers.get("x-extension-version")
    return "api", None


def require_key(request: Request, key: str | None = Query(None, description="API key, for links and event streams")) -> str:
    """Returns the caller's id: their API key when keys are on, otherwise their IP."""
    if not config.API_KEYS:
        return client_ip(request)
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
    subtitles: list[str] = Field(default_factory=list, max_length=5,
                                 description="Video only: subtitle codes from `/api/info` to embed, e.g. `[\"en\"]`")
    start: float | None = Field(None, ge=0, description="Trim: start time in seconds")
    end: float | None = Field(None, gt=0, description="Trim: end time in seconds")
    playlist: bool = Field(False, description="Download every video in a playlist link and zip them")
    playlist_items: list[int] | None = Field(None, description="Only these playlist positions (1-based)")

    @model_validator(mode="after")
    def _check(self):
        if self.start is not None and self.end is not None and self.end <= self.start:
            raise ValueError("The end time has to be after the start time.")
        return self


class BatchRequest(BaseModel):
    urls: list[str] = Field(..., min_length=1, max_length=20, description="Up to 20 links; each one becomes its own job")
    options: JobRequest | None = Field(None, description="Settings for every link (its `url` is ignored)")


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


async def get_info(url: str, playlist: bool) -> dict:
    try:
        url = await run_in_threadpool(security.check_url, url)
    except security.BadLink as e:
        raise HTTPException(400, str(e))
    info = _cache_get(url, playlist)
    if info is None:
        try:
            info = await run_in_threadpool(engine.extract_info, url, playlist)
        except engine.EngineError as e:
            raise HTTPException(422, {"message": str(e), "cookie_problem": e.cookie_problem} if e.cookie_problem else str(e))
        _cache_put(url, playlist, info)
    return info


def _error_detail(detail) -> str:
    return detail["message"] if isinstance(detail, dict) else str(detail)


# Routes ------------------------------------------------------------------------------------

@app.get("/api/health", tags=["server"], summary="Server status and tool versions")
async def health():
    c = cookies.summary()
    return {
        "ok": True,
        **engine.versions(),
        "auth": bool(config.API_KEYS),
        "admin": bool(config.ADMIN_PASSWORD),
        "cookies": {"present": c.get("present", False), "ok": c.get("ok")},
        "extension_ids": config.EXTENSION_IDS,
        "contact_email": config.CONTACT_EMAIL,
        "limits": {
            "max_filesize_mb": config.MAX_FILESIZE_MB,
            "max_duration_min": config.MAX_DURATION_MIN,
            "max_playlist_items": config.MAX_PLAYLIST_ITEMS,
            "file_ttl_min": config.FILE_TTL_MIN,
            "delete_after_send": config.DELETE_AFTER_SEND,
            "send_grace_min": config.SEND_GRACE_MIN,
            "max_active_per_client": config.MAX_ACTIVE_PER_CLIENT,
        },
    }


@app.post("/api/info", tags=["media"], summary="Title, thumbnail and format choices for a link")
async def info(body: InfoRequest, _: str = Depends(require_key)):
    data = await get_info(body.url, body.playlist)
    return media.summarize(data, body.container, body.language)


@app.get("/api/info", tags=["media"], summary="Same as POST /api/info, with query parameters")
async def info_get(url: str, container: Container = "mp4", language: str | None = None,
                   playlist: bool = True, _: str = Depends(require_key)):
    data = await get_info(url, playlist)
    return media.summarize(data, container, language)


@app.get("/api/direct-links", tags=["media"], summary="Links a browser can download straight from the site")
async def direct_links(url: str, request: Request, format: str | None = Query(None, include_in_schema=False)):
    """No server bandwidth is used, but many sites (YouTube included) tie these links to the
    server's IP address, so they may not open on another device. They expire after a few hours."""
    if format and config.LEGACY_API:
        # The old server's ?format=video|audio shape.
        from .legacy import _old_direct
        return await _old_direct(url, format)
    require_key(request, request.query_params.get("key"))
    data = await get_info(url, False)
    if data.get("_type") == "playlist":
        raise HTTPException(400, "That's a playlist. Ask for one video at a time.")
    links = media.direct_links(data)
    if not links:
        raise HTTPException(404, "This site only streams in pieces, so there's no direct link. Use a normal download.")
    return {"title": data.get("title"), "thumbnail": data.get("thumbnail"), "links": links}


async def start_job(request: Request, body: JobRequest, who: str, source: str | None = None):
    src, ver = client_source(request)
    source = source or src
    lang = body.language or None
    trimmed = body.start is not None or body.end is not None
    if body.playlist:
        data = await get_info(body.url, True)
        if data.get("_type") != "playlist" and data.get("entries") is None:
            body.playlist = False
        else:
            entries = [e for e in (data.get("entries") or []) if e and (e.get("url") or e.get("webpage_url"))]
            if body.playlist_items:
                wanted = set(body.playlist_items)
                entries = [e for i, e in enumerate(entries, start=1) if i in wanted]
            if not entries:
                raise HTTPException(422, "This playlist is empty.")
            entries = entries[:config.MAX_PLAYLIST_ITEMS]
            items = [{"url": e.get("url") or e.get("webpage_url"), "title": e.get("title"),
                      "thumbnail": media._thumb(e)} for e in entries]
            for it in items:
                try:
                    await run_in_threadpool(security.check_url, it["url"])
                except security.BadLink as e:
                    raise HTTPException(400, str(e))
            opts = engine.Options(
                kind=body.kind, container=body.container, audio_format=body.audio_format,
                audio_bitrate=body.audio_bitrate, embed_cover=body.embed_cover,
                subtitles=body.subtitles if body.kind == "video" else [],
                selector=media.generic_selector(body.kind, height=body.height, audio_format=body.audio_format, language=lang),
                format_sort=media.format_sort(body.container) if body.kind == "video" else [],
            )
            label = (f"{body.height}p {body.container.upper()}" if body.height else f"Best {body.container.upper()}") \
                if body.kind == "video" else _audio_label(body)
            label += f" · {len(items)} videos"
            return _create(request, who, body.url, label, opts, data, items, source, ver)

    # Reuse the info the website just fetched when the link wasn't a playlist.
    data = _cache_get(body.url.strip(), True)
    if data is None or data.get("_type") == "playlist":
        data = await get_info(body.url, False)
    if data.get("_type") == "playlist":
        raise HTTPException(400, "That's a playlist. Send \"playlist\": true to download all of it.")
    if data.get("is_live"):
        raise HTTPException(422, "Live streams can't be downloaded until they end.")
    dur = data.get("duration")
    span = ((body.end or dur or 0) - (body.start or 0)) if trimmed else dur
    if span and span > config.MAX_DURATION_MIN * 60:
        raise HTTPException(422, f"This is longer than the server limit ({config.MAX_DURATION_MIN} minutes).")
    if trimmed and dur and body.start is not None and body.start >= dur:
        raise HTTPException(422, "The start time is past the end of the video.")
    selector, label = media.choose_selector(data, body.kind, height=body.height, container=body.container,
                                            audio_format=body.audio_format, language=lang)
    if body.kind == "audio":
        label = _audio_label(body)
    if trimmed:
        label += f" · {_clock(body.start or 0)}–{_clock(body.end) if body.end else 'end'}"
    if body.subtitles and body.kind == "video":
        label += " · subs " + ",".join(body.subtitles)
    opts = engine.Options(
        kind=body.kind, selector=selector, container=body.container, audio_format=body.audio_format,
        audio_bitrate=body.audio_bitrate, embed_cover=body.embed_cover,
        subtitles=body.subtitles if body.kind == "video" else [], start=body.start, end=body.end,
    )
    return _create(request, who, body.url.strip(), label, opts, data, None, source, ver)


def _create(request, who, url, label, opts, data, items, source, ver):
    try:
        return app.state.jobs.create(
            url=url, client=who, label=label, options=opts, info=data, items=items, source=source,
            ip=client_ip(request), user_agent=request.headers.get("user-agent"), client_version=ver,
        )
    except LimitReached:
        raise HTTPException(429, f"You already have {config.MAX_ACTIVE_PER_CLIENT} downloads running. Wait for one to finish.")


def _audio_label(body: JobRequest) -> str:
    return f"MP3 {body.audio_bitrate}k" if body.audio_format == "mp3" else body.audio_format.upper().replace("OPUS", "Opus")


def _clock(s: float) -> str:
    s = int(s)
    h, m, x = s // 3600, s % 3600 // 60, s % 60
    return f"{h}:{m:02d}:{x:02d}" if h else f"{m}:{x:02d}"


@app.post("/api/jobs", tags=["jobs"], status_code=201, summary="Start a download")
async def create_job(body: JobRequest, request: Request, who: str = Depends(require_key)):
    job = await start_job(request, body, who)
    return job.public()


@app.post("/api/jobs/batch", tags=["jobs"], status_code=201, summary="Start downloads for several links")
async def create_batch(body: BatchRequest, request: Request, who: str = Depends(require_key)):
    """Each link becomes its own job. Links that fail come back with an `error` instead of a job."""
    base = body.options or JobRequest(url="x")
    out = []
    for url in dict.fromkeys(u.strip() for u in body.urls if u.strip()):
        req = base.model_copy(update={"url": url})
        try:
            out.append({"url": url, "job": (await start_job(request, req, who)).public()})
        except HTTPException as e:
            out.append({"url": url, "error": _error_detail(e.detail), "status": e.status_code})
    return {"results": out}


def get_job_for(job_id: str, who: str):
    job = app.state.jobs.get(job_id)
    # Job ids are unguessable; with keys on, a job also belongs to the key that made it.
    if job is None or (config.API_KEYS and job.client != who):
        raise HTTPException(404, "No such download. Finished files are removed after a while.")
    return job


@app.get("/api/jobs/{job_id}", tags=["jobs"], summary="A download's status and progress")
async def get_job(job_id: str, who: str = Depends(require_key)):
    return get_job_for(job_id, who).public()


@app.get("/api/jobs/{job_id}/events", tags=["jobs"], summary="Server-sent events with progress until the job ends")
async def job_events(job_id: str, request: Request, who: str = Depends(require_key)):
    job = get_job_for(job_id, who)

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
                if job.status in FINAL:
                    return
            elif time.time() - last_send > 15:
                last_send = time.time()
                yield ": keep-alive\n\n"
            await asyncio.sleep(0.4)

    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.get("/api/jobs/{job_id}/file", tags=["jobs"], summary="Download the finished file")
async def job_file(job_id: str, inline: bool = False, who: str = Depends(require_key)):
    job = get_job_for(job_id, who)
    if job.status != "done" or not job.file or not job.file.exists():
        raise HTTPException(409, "The file isn't ready yet.")
    if inline:
        # For the in-page preview player.
        return FileResponse(job.file, filename=job.file.name, content_disposition_type="inline")
    return FileResponse(job.file, filename=job.file.name, media_type="application/octet-stream",
                        background=BackgroundTask(app.state.jobs.mark_sent, job))


@app.delete("/api/jobs/{job_id}", tags=["jobs"], summary="Cancel a download, or delete its finished file")
async def delete_job(job_id: str, who: str = Depends(require_key)):
    job = get_job_for(job_id, who)
    app.state.jobs.cancel(job)
    return {"ok": True}


# Other routers and the website --------------------------------------------------------------

from . import admin, legacy, pair, releases, seo  # noqa: E402  (they import names from this module)

app.include_router(admin.router)
app.include_router(releases.admin_router(admin.require_admin))
app.include_router(releases.public_router)
app.include_router(pair.router)
app.include_router(seo.router)
if config.LEGACY_API:
    app.include_router(legacy.router)


def _page(name: str):
    async def serve():
        return FileResponse(STATIC / f"{name}.html")
    return serve


for _name in ("helper", "about", "privacy", "terms", "copyright", "contact"):
    app.add_api_route(f"/{_name}", _page(_name), methods=["GET"], include_in_schema=False)


@app.get("/admin", include_in_schema=False)
async def admin_page():
    return FileResponse(STATIC / "admin.html")


app.mount("/", StaticFiles(directory=STATIC, html=True), name="site")
