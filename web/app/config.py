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
FILE_TTL_MIN = _int("HAUL_FILE_TTL_MIN", 60)
MAX_FILESIZE_MB = _int("HAUL_MAX_FILESIZE_MB", 2048)
MAX_DURATION_MIN = _int("HAUL_MAX_DURATION_MIN", 240)
INFO_CACHE_MIN = _int("HAUL_INFO_CACHE_MIN", 10)
COOKIES_FILE = os.environ.get("HAUL_COOKIES_FILE") or None
PROXY = os.environ.get("HAUL_PROXY") or None
# Lets yt-dlp fetch links on private/loopback addresses. Off by default so the server
# can't be used to reach your internal network. Only turn on for local use and tests.
ALLOW_PRIVATE = os.environ.get("HAUL_ALLOW_PRIVATE", "") == "1"
TRUST_PROXY_HEADERS = os.environ.get("HAUL_TRUST_PROXY", "") == "1"
