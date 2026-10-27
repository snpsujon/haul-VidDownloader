"""Admin API behind HAUL_ADMIN_PASSWORD: cookies, live jobs, files, request log, updates."""
from __future__ import annotations

import asyncio
import csv
import io
import secrets
import shutil
import time

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from . import config, cookies, engine, store, upkeep

router = APIRouter(prefix="/api/admin", tags=["admin"])

_tokens: dict[str, float] = {}
_fails: dict[str, list[float]] = {}
TOKEN_HOURS = 12


def require_admin(request: Request) -> None:
    if not config.ADMIN_PASSWORD:
        raise HTTPException(404, "The admin page is off. Set HAUL_ADMIN_PASSWORD to turn it on.")
    token = request.headers.get("x-admin-token") or request.query_params.get("admin_token")
    exp = _tokens.get(token or "")
    if not exp or exp < time.time():
        _tokens.pop(token or "", None)
        raise HTTPException(401, "Sign in to the admin page again.")


class Login(BaseModel):
    password: str


@router.post("/login", summary="Get an admin token")
async def login(body: Login, request: Request):
    if not config.ADMIN_PASSWORD:
        raise HTTPException(404, "The admin page is off. Set HAUL_ADMIN_PASSWORD to turn it on.")
    from .main import client_ip
    ip = client_ip(request)
    now = time.time()
    recent = [t for t in _fails.get(ip, []) if t > now - 900]
    if len(recent) >= 10:
        raise HTTPException(429, "Too many wrong passwords. Try again in 15 minutes.")
    if not secrets.compare_digest(body.password.encode(), config.ADMIN_PASSWORD.encode()):
        _fails[ip] = recent + [now]
        await asyncio.sleep(1)
        raise HTTPException(401, "Wrong password.")
    _fails.pop(ip, None)
    token = secrets.token_urlsafe(32)
    _tokens[token] = now + TOKEN_HOURS * 3600
    return {"token": token, "expires": _tokens[token]}


@router.post("/logout", dependencies=[Depends(require_admin)])
async def logout(request: Request):
    _tokens.pop(request.headers.get("x-admin-token") or "", None)
    return {"ok": True}


# Overview ----------------------------------------------------------------------------------

@router.get("/overview", dependencies=[Depends(require_admin)], summary="Server, cookies, jobs and today's numbers")
async def overview():
    from .main import app
    jobs = app.state.jobs
    du = shutil.disk_usage(config.DATA_DIR)
    return {
        **engine.versions(),
        "cookies": cookies.summary(),
        "active_jobs": jobs.active(),
        "files_bytes": await run_in_threadpool(jobs.disk_usage),
        "disk": {"free": du.free, "total": du.total},
        "stats": store.stats(),
        "alerts": upkeep.alerts(),
        "upkeep": {"auto_update": config.AUTO_UPDATE, "hour": config.AUTO_UPDATE_HOUR,
                   "auto_restart": config.AUTO_RESTART, "last": upkeep.state().get("update")},
        "settings": {
            "max_concurrent": config.MAX_CONCURRENT,
            "max_active_per_client": config.MAX_ACTIVE_PER_CLIENT,
            "file_ttl_min": config.FILE_TTL_MIN,
            "delete_after_send": config.DELETE_AFTER_SEND,
            "send_grace_min": config.SEND_GRACE_MIN,
            "max_filesize_mb": config.MAX_FILESIZE_MB,
            "max_duration_min": config.MAX_DURATION_MIN,
            "max_playlist_items": config.MAX_PLAYLIST_ITEMS,
            "api_keys": len(config.API_KEYS),
            "legacy_api": config.LEGACY_API,
            "proxy": bool(config.PROXY),
            "youtube_clients": config.YOUTUBE_FALLBACK_CLIENTS,
        },
    }


@router.get("/charts", dependencies=[Depends(require_admin)], summary="Requests per day and per site")
async def charts(days: int = 14):
    days = max(1, min(days, 90))
    return {"daily": store.daily(days), "sites": store.site_health(24 * 7)}


@router.get("/jobs", dependencies=[Depends(require_admin)], summary="Every job the server knows about")
async def all_jobs():
    from .main import app
    out = []
    for j in sorted(app.state.jobs.jobs.values(), key=lambda j: j.created, reverse=True):
        p = j.public()
        p["client"] = j.client if not j.client.startswith("key:") else "key:" + j.client[4:8] + "…"
        p["source"] = j.source
        out.append(p)
    return {"jobs": out}


@router.delete("/jobs/{job_id}", dependencies=[Depends(require_admin)], summary="Cancel or delete any job")
async def kill_job(job_id: str):
    from .main import app
    job = app.state.jobs.get(job_id)
    if not job:
        raise HTTPException(404, "No such job.")
    app.state.jobs.cancel(job)
    return {"ok": True}


@router.post("/files/clear", dependencies=[Depends(require_admin)], summary="Delete every finished file now")
async def clear_files():
    from .main import app
    jobs = app.state.jobs
    gone = [j for j in list(jobs.jobs.values()) if j.status in ("done", "error", "cancelled")]
    for j in gone:
        jobs.remove(j)
    return {"deleted": len(gone)}


# Cookies -----------------------------------------------------------------------------------

class CookieText(BaseModel):
    text: str


@router.get("/cookies", dependencies=[Depends(require_admin)], summary="What's in the saved cookies.txt")
async def get_cookies():
    return cookies.summary()


@router.put("/cookies", dependencies=[Depends(require_admin)], summary="Replace cookies.txt, then check it")
async def put_cookies(body: CookieText):
    try:
        cookies.save(body.text)
    except cookies.BadCookies as e:
        raise HTTPException(400, str(e))
    return await run_in_threadpool(engine.check_cookies)


@router.post("/cookies/check", dependencies=[Depends(require_admin)], summary="Check the cookies still sign in to YouTube")
async def check_cookies():
    return await run_in_threadpool(engine.check_cookies)


@router.delete("/cookies", dependencies=[Depends(require_admin)], summary="Remove cookies.txt")
async def delete_cookies():
    cookies.delete()
    return cookies.summary()


# Request log -------------------------------------------------------------------------------

@router.get("/requests", dependencies=[Depends(require_admin)], summary="Recent downloads, newest first")
async def requests_log(limit: int = 200, offset: int = 0, q: str | None = None):
    return {"requests": store.recent(min(limit, 1000), offset, q)}


@router.get("/requests/export", dependencies=[Depends(require_admin)], summary="The whole log as CSV")
async def export_requests():
    rows = store.export()
    buf = io.StringIO()
    if rows:
        w = csv.DictWriter(buf, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return StreamingResponse(iter([buf.getvalue()]), media_type="text/csv",
                             headers={"Content-Disposition": 'attachment; filename="haul-requests.csv"'})


@router.delete("/requests", dependencies=[Depends(require_admin)], summary="Clear the request log")
async def clear_requests():
    return {"deleted": store.clear()}


# yt-dlp ------------------------------------------------------------------------------------

@router.post("/ytdlp/update", dependencies=[Depends(require_admin)], summary="Install the newest yt-dlp")
async def update_ytdlp():
    try:
        return await run_in_threadpool(engine.update_ytdlp)
    except engine.EngineError as e:
        raise HTTPException(500, str(e))


@router.post("/restart", dependencies=[Depends(require_admin)], summary="Restart the server to load a new yt-dlp")
async def restart(force: bool = False):
    """Needs a process manager that starts it again (Docker's restart policy does)."""
    from .main import app
    if app.state.jobs.active() and not force:
        raise HTTPException(409, f"{app.state.jobs.active()} downloads are running. Wait, or restart anyway.")
    engine.restart_soon()
    return {"ok": True}
