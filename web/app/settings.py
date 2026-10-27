"""Settings changed on the admin page, kept in DATA_DIR/settings.json: the site's name,
announcement banner and contact email, blocked IPs and the daily download limit, and where
alerts are sent (Telegram, email). Environment variables give the starting values."""
from __future__ import annotations

import ipaddress
import json
import threading
import time

from . import config

_lock = threading.Lock()
_cache: tuple[float, dict] | None = None

DEFAULTS = {
    "site_name": "Haul",
    "banner": "",
    "banner_level": "info",        # info, warn or bad
    "contact_email": None,         # None: use HAUL_CONTACT_EMAIL
    "daily_limit": 0,              # downloads per visitor per 24 hours, 0 = no limit
    "banned": [],                  # [{"ip", "note", "at"}]; an entry may be a range like 1.2.3.0/24
    "alerts": {
        "telegram_token": "", "telegram_chat": "",
        "smtp_host": "", "smtp_port": 587, "smtp_security": "starttls",  # starttls, ssl or none
        "smtp_user": "", "smtp_password": "", "email_from": "", "email_name": "", "email_to": "",
        "cookies": True, "disk": True, "failures": True, "updates": True,
    },
}
# Never sent back to the browser in full.
SECRETS = ("telegram_token", "smtp_password")


def _path():
    return config.DATA_DIR / "settings.json"


def load() -> dict:
    global _cache
    p = _path()
    try:
        mtime = p.stat().st_mtime
    except OSError:
        mtime = 0.0
    if _cache and _cache[0] == mtime:
        return _cache[1]
    try:
        data = json.loads(p.read_text(encoding="utf-8")) if mtime else {}
    except (OSError, ValueError):
        data = {}
    out = {**DEFAULTS, **{k: v for k, v in data.items() if k in DEFAULTS}}
    saved = dict(data.get("alerts") or {})
    if "smtp_ssl" in saved:  # the first version had a single SSL checkbox
        saved.setdefault("smtp_security", "ssl" if saved.pop("smtp_ssl") else "starttls")
    out["alerts"] = {**DEFAULTS["alerts"], **saved}
    _cache = (mtime, out)
    return out


def save(changes: dict) -> dict:
    global _cache
    with _lock:
        cur = load()
        new = {**cur, **{k: v for k, v in changes.items() if k in DEFAULTS and k != "alerts"}}
        if "alerts" in changes:
            new["alerts"] = {**cur["alerts"], **changes["alerts"]}
        config.DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = _path().with_suffix(".tmp")
        tmp.write_text(json.dumps(new, indent=2), encoding="utf-8")
        tmp.replace(_path())
        _cache = None
    return load()


def contact_email() -> str | None:
    return load()["contact_email"] or config.CONTACT_EMAIL


def public() -> dict:
    s = load()
    return {"site_name": s["site_name"] or "Haul", "banner": s["banner"] or None,
            "banner_level": s["banner_level"], "contact_email": contact_email()}


def for_admin() -> dict:
    s = load()
    a = dict(s["alerts"])
    for k in SECRETS:
        v = a.get(k) or ""
        # Only whether it's set, and its last few characters so you can tell which one it is.
        a[k] = bool(v)
        a[k + "_hint"] = ("***" + v[-4:]) if len(v) >= 8 else ("***" if v else "")
    return {**s, "alerts": a, "contact_email": s["contact_email"] or "",
            "contact_email_env": config.CONTACT_EMAIL, "trust_proxy": config.TRUST_PROXY_HEADERS}


# Blocking ----------------------------------------------------------------------------------

def parse_ip(text: str) -> str:
    """A single address or a range, normalised; ValueError if it's neither."""
    text = (text or "").strip()
    if "/" in text:
        return str(ipaddress.ip_network(text, strict=False))
    return str(ipaddress.ip_address(text))


def banned(ip: str | None) -> dict | None:
    """The ban entry that covers ip, if any."""
    if not ip:
        return None
    entries = load()["banned"]
    if not entries:
        return None
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return next((b for b in entries if b["ip"] == ip), None)
    for b in entries:
        try:
            if "/" in b["ip"] and addr in ipaddress.ip_network(b["ip"], strict=False) or b["ip"] == str(addr):
                return b
        except ValueError:
            continue
    return None


def ban(ip: str, note: str = "") -> dict:
    ip = parse_ip(ip)
    entries = [b for b in load()["banned"] if b["ip"] != ip]
    entries.insert(0, {"ip": ip, "note": (note or "").strip()[:200], "at": time.time()})
    save({"banned": entries[:5000]})
    return entries[0]


def unban(ip: str) -> bool:
    entries = load()["banned"]
    left = [b for b in entries if b["ip"] != ip]
    if len(left) == len(entries):
        return False
    save({"banned": left})
    return True
