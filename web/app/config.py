"""Settings, read once from environment variables (see README)."""
import os
from pathlib import Path


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


def _list(name: str, default: str = "") -> list[str]:
    return [v.strip() for v in os.environ.get(name, default).split(",") if v.strip()]


DATA_DIR = Path(os.environ.get("HAUL_DATA_DIR", "./data")).resolve()
API_KEYS = set(_list("HAUL_API_KEYS"))
CORS_ORIGINS = _list("HAUL_CORS_ORIGINS", "*")
MAX_CONCURRENT = _int("HAUL_MAX_CONCURRENT", 3)
MAX_ACTIVE_PER_CLIENT = _int("HAUL_MAX_ACTIVE_PER_CLIENT", 3)
FILE_TTL_MIN = _int("HAUL_FILE_TTL_MIN", 30)
# Delete a finished file soon after it has been downloaded once, instead of waiting for the TTL.
# The grace period lets a browser retry or resume a download that was cut off.
DELETE_AFTER_SEND = os.environ.get("HAUL_DELETE_AFTER_SEND", "1") == "1"
SEND_GRACE_MIN = _int("HAUL_SEND_GRACE_MIN", 5)
# Haul extension IDs the website asks for the "download on this PC" helper (store, unpacked).
EXTENSION_IDS = _list("HAUL_EXTENSION_IDS", "hnicogbjbfkjlkkeajepgjkkhojceeak,pjolfkooklpnndljgomfbnnhigaoecoe")
MAX_FILESIZE_MB = _int("HAUL_MAX_FILESIZE_MB", 2048)
MAX_DURATION_MIN = _int("HAUL_MAX_DURATION_MIN", 240)
INFO_CACHE_MIN = _int("HAUL_INFO_CACHE_MIN", 10)
COOKIES_FILE = os.environ.get("HAUL_COOKIES_FILE") or None
PROXY = os.environ.get("HAUL_PROXY") or None
# Lets yt-dlp fetch links on private/loopback addresses. Off by default so the server
# can't be used to reach your internal network. Only turn on for local use and tests.
ALLOW_PRIVATE = os.environ.get("HAUL_ALLOW_PRIVATE", "") == "1"
TRUST_PROXY_HEADERS = os.environ.get("HAUL_TRUST_PROXY", "") == "1"
# Admin page (/admin): cookies, live jobs, files, request log, yt-dlp updates. Off until set.
# The placeholder from docker-compose.yml never works, so a copied file can't leave it open.
ADMIN_PASSWORD = os.environ.get("HAUL_ADMIN_PASSWORD") if os.environ.get("HAUL_ADMIN_PASSWORD") not in (None, "", "change-me") else None
MAX_PLAYLIST_ITEMS = _int("HAUL_MAX_PLAYLIST_ITEMS", 50)
LOG_DAYS = _int("HAUL_LOG_DAYS", 30)
# Keeps the old /api/download, /api/progress and /downloads/... routes so the published
# "Universal Media Downloader" extension keeps working against this server.
LEGACY_API = os.environ.get("HAUL_LEGACY_API", "1") == "1"
# Player clients yt-dlp tries for YouTube when the default fails, in order.
YOUTUBE_FALLBACK_CLIENTS = _list("HAUL_YOUTUBE_CLIENTS", "tv,web_safari,mweb,android")
# Uploads on the admin page (app, extension, helper, other files).
AUTO_UPDATE = os.environ.get("HAUL_AUTO_UPDATE", "1") == "1"
AUTO_UPDATE_HOUR = _int("HAUL_AUTO_UPDATE_HOUR", 4) % 24
AUTO_RESTART = os.environ.get("HAUL_AUTO_RESTART", "1") == "1"
CONTACT_EMAIL = (os.environ.get("HAUL_CONTACT_EMAIL") or "").strip() or None
MAX_UPLOAD_MB = _int("HAUL_MAX_UPLOAD_MB", 1024)
# The site's public address, used in the app's update file. Taken from each request when unset.
PUBLIC_URL = (os.environ.get("HAUL_PUBLIC_URL") or "").rstrip("/") or None
