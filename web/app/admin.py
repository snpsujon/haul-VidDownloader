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
from pydantic import BaseModel, Field

from . import config, cookies, engine, notify, settings, store, upkeep

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


@router.get("/jobs/{job_id}/file", dependencies=[Depends(require_admin)], summary="Play or open any finished file")
async def job_file(job_id: str):
    """Shown inline for the admin's player. Doesn't count as the visitor's download."""
    from fastapi.responses import FileResponse
    from .main import app
    job = app.state.jobs.get(job_id)
    if not job or job.status != "done" or not job.file or not job.file.exists():
        raise HTTPException(404, "That file isn't on the server any more.")
    return FileResponse(job.file, filename=job.file.name, content_disposition_type="inline")


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
    rows = store.recent(min(limit, 1000), offset, q)
    for r in rows:
        r["banned"] = bool(settings.banned(r.get("ip")))
    return {"requests": rows, "total": store.count(q)}


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
async def update_ytdlp(restart: bool = False):
    """With restart=true, the server also restarts to load it when it changed and nothing is downloading."""
    from .main import app
    try:
        r = await run_in_threadpool(engine.update_ytdlp)
    except engine.EngineError as e:
        raise HTTPException(500, str(e))
    if restart and r.get("restart_needed"):
        if app.state.jobs.active():
            r["restart"] = "waiting"
        else:
            engine.restart_soon()
            r["restart"] = "now"
    return r


@router.post("/restart", dependencies=[Depends(require_admin)], summary="Restart the server to load a new yt-dlp")
async def restart(force: bool = False):
    """Needs a process manager that starts it again (Docker's restart policy does)."""
    from .main import app
    if app.state.jobs.active() and not force:
        raise HTTPException(409, f"{app.state.jobs.active()} downloads are running. Wait, or restart anyway.")
    engine.restart_soon()
    return {"ok": True}


# Site settings -----------------------------------------------------------------------------

class SiteSettings(BaseModel):
    site_name: str = Field("Haul", max_length=40)
    banner: str = Field("", max_length=500)
    banner_level: str = Field("info", pattern="^(info|warn|bad)$")
    contact_email: str = Field("", max_length=200)
    daily_limit: int = Field(0, ge=0, le=100000)


@router.get("/settings", dependencies=[Depends(require_admin)], summary="Site settings, blocking and alerts")
async def get_settings():
    return settings.for_admin()


@router.put("/settings", dependencies=[Depends(require_admin)], summary="Save the site name, banner, contact email and daily limit")
async def put_settings(body: SiteSettings):
    mail = body.contact_email.strip()
    if mail and ("@" not in mail or " " in mail or "<" in mail):
        raise HTTPException(400, "That email address doesn't look right.")
    settings.save({"site_name": body.site_name.strip() or "Haul", "banner": body.banner.strip(),
                   "banner_level": body.banner_level, "contact_email": mail or None, "daily_limit": body.daily_limit})
    return settings.for_admin()


class AlertSettings(BaseModel):
    telegram_token: str | None = Field(None, max_length=200, description="Leave out to keep the saved one; empty removes it")
    telegram_chat: str = Field("", max_length=100)
    smtp_host: str = Field("", max_length=200)
    smtp_port: int = Field(587, ge=1, le=65535)
    smtp_user: str = Field("", max_length=200)
    smtp_password: str | None = Field(None, max_length=200)
    smtp_security: str = Field("starttls", pattern="^(starttls|ssl|none)$")
    email_from: str = Field("", max_length=200)
    email_name: str = Field("", max_length=100)
    email_to: str = Field("", max_length=500)
    cookies: bool = True
    disk: bool = True
    failures: bool = True
    updates: bool = True


@router.put("/settings/alerts", dependencies=[Depends(require_admin)], summary="Where alerts are sent")
async def put_alerts(body: AlertSettings):
    changes = {k: (v.strip() if isinstance(v, str) else v) for k, v in body.model_dump(exclude_unset=True).items()}
    for k in settings.SECRETS:
        if k in changes and changes[k] is None:
            changes.pop(k)
    settings.save({"alerts": changes})
    return settings.for_admin()


@router.post("/settings/alerts/test", dependencies=[Depends(require_admin)], summary="Send a test message (channel: telegram or email)")
async def test_alerts(channel: str | None = None):
    a = settings.load()["alerts"]
    if channel == "telegram" and not (a.get("telegram_token") and a.get("telegram_chat")):
        raise HTTPException(400, "Fill in the bot token and chat ID first.")
    if channel == "email" and not (a.get("smtp_host") and a.get("email_to")):
        raise HTTPException(400, "Fill in the SMTP host and the address to send to first.")
    if channel not in (None, "telegram", "email"):
        raise HTTPException(404, "Unknown channel.")
    if not notify.configured(a):
        raise HTTPException(400, "Fill in Telegram or email first, then save.")
    name = settings.load()["site_name"] or "Haul"
    results = await run_in_threadpool(notify.send, f"{name}: test alert. Alerts reach you here.", a, channel)
    return {"results": results, "current": upkeep.alerts()}


@router.post("/settings/alerts/send-now", dependencies=[Depends(require_admin)], summary="Check for problems and send alerts now")
async def send_alerts_now():
    return {"sent": await run_in_threadpool(upkeep.send_alerts)}


# Blocking ----------------------------------------------------------------------------------

class Ban(BaseModel):
    ip: str = Field(..., max_length=64)
    note: str = Field("", max_length=200)


@router.get("/bans", dependencies=[Depends(require_admin)], summary="Blocked addresses")
async def list_bans():
    return {"bans": settings.load()["banned"], "daily_limit": settings.load()["daily_limit"]}


@router.post("/bans", dependencies=[Depends(require_admin)], summary="Block an address or a range (like 1.2.3.0/24)")
async def add_ban(body: Ban, request: Request):
    from .main import client_ip
    try:
        ip = settings.parse_ip(body.ip)
    except ValueError:
        raise HTTPException(400, "That isn't an IP address or range.")
    import ipaddress
    try:
        mine = ipaddress.ip_address(client_ip(request)) in ipaddress.ip_network(ip, strict=False)
    except ValueError:
        mine = False
    if mine:
        # Behind a proxy without HAUL_TRUST_PROXY=1 every visitor has the proxy's address, yours too.
        raise HTTPException(400, "That's your own address, so it would block you too. If every visitor shows "
                                 "the same address, set HAUL_TRUST_PROXY=1 so the server sees real addresses.")
    return {"ok": True, "ban": settings.ban(ip, body.note)}


@router.delete("/bans/{ip:path}", dependencies=[Depends(require_admin)], summary="Unblock an address")
async def remove_ban(ip: str):
    if not settings.unban(ip):
        raise HTTPException(404, "That address isn't blocked.")
    return {"ok": True}


# Stats -------------------------------------------------------------------------------------

@router.get("/stats", dependencies=[Depends(require_admin)], summary="Top sites, countries, sources and installs")
async def stats(days: int = 30):
    return store.stats_report(max(1, min(days, 365)))


# Backup ------------------------------------------------------------------------------------

@router.get("/backup", dependencies=[Depends(require_admin)], summary="Download a backup of the server's data as a zip")
async def backup(releases: bool = False):
    """Settings, the request log, pairings, cookies and the releases list. With releases=true
    the published app, extension and helper files go in too (can be big)."""
    from fastapi.responses import FileResponse
    from starlette.background import BackgroundTask
    path = await run_in_threadpool(_make_backup, releases)
    name = time.strftime("haul-backup-%Y%m%d-%H%M.zip")
    return FileResponse(path, filename=name, media_type="application/zip",
                        background=BackgroundTask(lambda: path.unlink(missing_ok=True)))


def _make_backup(with_releases: bool):
    import sqlite3
    import tempfile
    import zipfile
    from pathlib import Path
    d = config.DATA_DIR
    out = Path(tempfile.mkstemp(prefix="haul-backup-", suffix=".zip", dir=d)[1])
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for db in ("haul.db", "pairs.db"):
            if (d / db).exists():
                # A consistent copy, even while the server writes to it.
                tmp = Path(tempfile.mkstemp(suffix=".db", dir=d)[1])
                try:
                    src, dst = sqlite3.connect(d / db), sqlite3.connect(tmp)
                    with dst:
                        src.backup(dst)
                    src.close()
                    dst.close()
                    z.write(tmp, db)
                finally:
                    tmp.unlink(missing_ok=True)
        for f in ("settings.json", "upkeep.json", "cookies.txt", "releases/releases.json"):
            if (d / f).is_file():
                z.write(d / f, f)
        if with_releases and (d / "releases").is_dir():
            for f in sorted((d / "releases").rglob("*")):
                rel = f.relative_to(d).as_posix()
                if f.is_file() and "/.uploads/" not in "/" + rel and ".incoming-" not in rel and rel != "releases/releases.json":
                    z.write(f, rel)
        z.writestr("README.txt", "Haul backup made " + time.strftime("%Y-%m-%d %H:%M") + ".\n"
                   "To restore, stop the server and put these files back in its data folder "
                   "(HAUL_DATA_DIR, ./data by default), then start it again.\n"
                   "cookies.txt signs in to YouTube, so keep this file private.\n")
    return out
