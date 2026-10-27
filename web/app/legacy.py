"""The old server's routes, so the published "Universal Media Downloader" extension keeps
working when its server address points here.

Old API: POST /api/download (form) -> {"session_id"}, GET /api/progress (X-Session-ID),
GET /downloads/<filename>, POST /api/cancel, GET|POST /api/direct-links.
These run without an API key (the old extension can't send one); limits per IP still apply.
Turn them off with HAUL_LEGACY_API=0.
"""
from __future__ import annotations

from collections import defaultdict

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from starlette.background import BackgroundTask

from . import engine, media
from .jobs import JobManager

router = APIRouter(include_in_schema=False)

_sessions: dict[str, str] = {}   # old session id -> job id


def _jobs() -> JobManager:
    from .main import app
    return app.state.jobs


def _old_status(job) -> dict:
    if job is None:
        return {"percent": "0%", "status": "Idle", "filename": None, "size": None}
    pct = job.percent or 0
    status = {
        "queued": "Downloading", "running": "Processing..." if job.stage not in ("downloading", "queued") else "Downloading",
        "done": "Completed", "cancelled": "Cancelled",
    }.get(job.status, f"Error: {job.error}")
    size = job.file.stat().st_size if job.file and job.file.exists() else None
    return {
        "percent": f"{pct:.1f}%",
        "status": status,
        "filename": f"{job.id}/{job.file.name}" if job.status == "done" and job.file else None,
        "size": f"{round(size / 1048576, 2)} MB" if size else None,
    }


@router.post("/api/download")
async def old_download(request: Request, url: str = Form(...), format: str = Form("video"),
                       quality: str = Form("best"), playlist: str = Form("off")):
    from .main import JobRequest, client_ip, start_job
    urls = [u.strip() for u in url.splitlines() if u.strip()]
    if not urls:
        return JSONResponse({"success": False, "error": "URL is required"}, 400)
    audio = format in ("audio", "mp3")
    height = {"18": 360, "22": 720, "worst": 144}.get(quality)
    container = format if format in ("mp4", "mkv", "webm") else "mp4"
    who = client_ip(request)
    try:
        if len(urls) == 1:
            req = JobRequest(url=urls[0], kind="audio" if audio else "video", height=height,
                             container=container, playlist=playlist == "on")
            job = await start_job(request, req, who, source="legacy-extension" if request.headers.get("x-extension-request") == "true" else "legacy-web")
        else:
            # Several links in one request: one job that zips them, like the old server.
            items = [{"url": u, "title": None} for u in urls[:20]]
            opts = engine.Options(
                kind="audio" if audio else "video", container=container,
                selector=media.generic_selector("audio" if audio else "video", height=height, audio_format="mp3", language=None),
                format_sort=[] if audio else media.format_sort(container),
            )
            from . import security
            for it in items:
                try:
                    security.check_url(it["url"])
                except security.BadLink as e:
                    return JSONResponse({"success": False, "error": str(e)}, 400)
            from .main import _create
            job = _create(request, who, urls[0], f"{len(items)} links", opts, {"title": f"{len(items)} downloads"},
                          items, "legacy-extension", request.headers.get("x-extension-version"))
    except HTTPException as e:
        detail = e.detail["message"] if isinstance(e.detail, dict) else e.detail
        return JSONResponse({"success": False, "error": detail}, e.status_code)
    sid = request.headers.get("x-session-id") or job.id
    _sessions[sid] = job.id
    if len(_sessions) > 5000:
        for k in list(_sessions)[:1000]:
            _sessions.pop(k, None)
    return {"success": True, "session_id": sid}


@router.get("/api/progress")
async def old_progress(request: Request):
    sid = request.headers.get("x-session-id") or request.query_params.get("session_id") or ""
    return _old_status(_jobs().get(_sessions.get(sid, sid)))


@router.post("/api/cancel")
async def old_cancel(request: Request):
    data = await request.json()
    sid = (data or {}).get("session_id")
    job = _jobs().get(_sessions.get(sid, sid)) if sid else None
    if not job:
        return JSONResponse({"success": False, "error": "Invalid session ID"}, 400)
    _jobs().cancel(job)
    return {"success": True}


@router.get("/downloads/{job_id}/{name}")
async def old_file(job_id: str, name: str):
    job = _jobs().get(job_id)
    if not job or job.status != "done" or not job.file or not job.file.exists():
        return JSONResponse("File not found", 404)
    return FileResponse(job.file, filename=job.file.name, media_type="application/octet-stream",
                        background=BackgroundTask(_jobs().mark_sent, job))


@router.get("/delete-server-downloaded-file")
async def old_cleanup():
    _jobs().sweep()
    return {"success": True, "deleted_files": []}


def _human(n):
    if not n:
        return None
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f} {unit}"
        n /= 1024
    return f"{n:.2f} PB"


@router.api_route("/api/direct-links", methods=["POST"])
async def old_direct_links_post(request: Request):
    form = await request.form()
    return await _old_direct(form.get("url"), form.get("format"))


async def _old_direct(url: str | None, format_type: str | None):
    """The old response shape: links grouped by file extension."""
    from .main import get_info
    if not url:
        return JSONResponse({"error": "URL is required"}, 400)
    if format_type not in ("video", "audio"):
        return JSONResponse({"error": "Format must be 'video' or 'audio'"}, 400)
    try:
        info = await get_info(url, False)
    except HTTPException as e:
        detail = e.detail["message"] if isinstance(e.detail, dict) else e.detail
        return JSONResponse({"success": False, "error": detail}, 500)
    grouped = defaultdict(list)
    for link in media.direct_links(info):
        if link["kind"] != format_type:
            continue
        grouped[link["ext"]].append({
            "format_id": link["format_id"],
            "language_code": link["language"] or "Default",
            "language": link["language"] or "Default",
            "ext": link["ext"],
            "resolution": f"{link['height']}p" if link["height"] else "",
            "url": link["url"],
            "downloadQuality": link["height"] or link["format_id"],
            "filesize_bytes": link["size"],
            "filesize_readable": _human(link["size"]),
            "format_note": link["label"],
        })
    if not grouped:
        return JSONResponse({"success": False, "error": "No direct download formats available. Please use the main downloader instead."}, 404)
    return {
        "success": True,
        "info": {
            "title": info.get("title", "Unknown Title"), "channel": info.get("channel", ""),
            "duration": info.get("duration_string", ""), "thumbnail": info.get("thumbnail", ""),
            "uploader": info.get("uploader", ""), "platform": info.get("extractor_key", ""),
            "view_count": info.get("view_count", ""), "like_count": info.get("like_count", ""),
            "upload_date": info.get("upload_date", ""), "video_id": info.get("display_id", ""),
        },
        "links": grouped,
    }
