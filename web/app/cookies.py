"""The server's cookies.txt: upload, check, and a private copy for each yt-dlp run.

yt-dlp writes cookies back to the file when it closes, so concurrent runs would clobber
one shared file. Each run gets its own copy instead.
"""
from __future__ import annotations

import os
import shutil
import tempfile
import threading
import time
from pathlib import Path

from . import config

_lock = threading.Lock()
_status: dict = {"checked": None, "ok": None, "message": None}

# Cookies that show a YouTube/Google session is signed in.
_SESSION_NAMES = {"SID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "SAPISID", "__Secure-3PAPISID"}


class BadCookies(ValueError):
    pass


def path() -> Path:
    return config.DATA_DIR / "cookies.txt"


def init() -> None:
    """Seed the managed file from HAUL_COOKIES_FILE the first time."""
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    if config.COOKIES_FILE and not path().exists() and Path(config.COOKIES_FILE).is_file():
        shutil.copyfile(config.COOKIES_FILE, path())
        _chmod(path())


def _chmod(p: Path) -> None:
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass


def parse(text: str) -> list[dict]:
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#HttpOnly_"):
            line = line[len("#HttpOnly_"):]
        elif not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 7:
            raise BadCookies("This doesn't look like a cookies.txt file. Export it with a "
                             "\"Get cookies.txt\" browser extension (Netscape format).")
        domain, _, _, _, expires, name, _value = parts
        try:
            exp = int(float(expires))
        except ValueError:
            raise BadCookies(f"A cookie has a bad expiry date: {name}")
        rows.append({"domain": domain.lstrip("."), "name": name, "expires": exp})
    if not rows:
        raise BadCookies("The file has no cookies in it.")
    return rows


def summary() -> dict:
    p = path()
    if not p.exists():
        return {"present": False, **_status}
    try:
        rows = parse(p.read_text(encoding="utf-8", errors="replace"))
    except BadCookies as e:
        return {"present": True, "valid": False, "error": str(e), **_status}
    now = time.time()
    yt = [r for r in rows if r["domain"].endswith(("youtube.com", "google.com"))]
    session = [r for r in yt if r["name"] in _SESSION_NAMES]
    expiring = [r["expires"] for r in session if r["expires"] > 0]
    domains = sorted({r["domain"] for r in rows})
    return {
        "present": True,
        "valid": True,
        "count": len(rows),
        "domains": domains[:30],
        "youtube_signed_in": bool(session),
        "expires": min(expiring) if expiring else None,
        "expired": bool(expiring) and min(expiring) < now,
        "updated": p.stat().st_mtime,
        **_status,
    }


def save(text: str) -> dict:
    text = text.replace("\r\n", "\n").strip() + "\n"
    if len(text) > 1_000_000:
        raise BadCookies("That file is too big for a cookies.txt.")
    parse(text)
    if not text.startswith("# Netscape") and not text.startswith("# HTTP Cookie File"):
        text = "# Netscape HTTP Cookie File\n" + text
    with _lock:
        tmp = path().with_suffix(".tmp")
        tmp.write_text(text, encoding="utf-8")
        _chmod(tmp)
        tmp.replace(path())
    set_status(None, None)
    return summary()


def delete() -> None:
    with _lock:
        path().unlink(missing_ok=True)
    set_status(None, None)


def set_status(ok: bool | None, message: str | None) -> None:
    _status.update(checked=time.time() if message else None, ok=ok, message=message)


def note_failure(message: str) -> None:
    """Called when a run fails in a way that fresh cookies would fix."""
    _status.update(checked=time.time(), ok=False, message=message)


def private_copy() -> str | None:
    """A throwaway copy for one yt-dlp run, or None when there are no cookies."""
    with _lock:
        if not path().exists():
            return None
        fd, tmp = tempfile.mkstemp(prefix="haul-cookies-", suffix=".txt")
        os.close(fd)
        shutil.copyfile(path(), tmp)
    _chmod(Path(tmp))
    return tmp
