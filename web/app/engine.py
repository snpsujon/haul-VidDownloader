"""yt-dlp calls: fetching link info and running one download."""
from __future__ import annotations

import shutil
import subprocess
import threading
from pathlib import Path
from typing import Callable

import yt_dlp
from yt_dlp.utils import DownloadError, ExtractorError

from . import config


class EngineError(Exception):
    """An error with a message that is safe to show to people."""


class Cancelled(Exception):
    pass


def _base_opts() -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "socket_timeout": 20,
        "retries": 5,
        "fragment_retries": 10,
        "concurrent_fragment_downloads": 4,
        "noplaylist": True,
        "logger": _QuietLogger(),
    }
    if config.COOKIES_FILE:
        opts["cookiefile"] = config.COOKIES_FILE
    if config.PROXY:
        opts["proxy"] = config.PROXY
    return opts


class _QuietLogger:
    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg): pass
    def error(self, msg): pass


def friendly_error(e: Exception) -> str:
    """Plain-language messages for the errors people actually hit."""
    msg = str(e)
    low = msg.lower()
    if "unsupported url" in low:
        return "This site isn't supported."
    if "private video" in low or "members-only" in low or "join this channel" in low:
        return "This video is private or members-only."
    if "sign in to confirm" in low or "confirm you’re not a bot" in low or "confirm you're not a bot" in low:
        return "The site is asking the server to sign in. Add cookies on the server (HAUL_COOKIES_FILE)."
    if "age" in low and ("restricted" in low or "confirm your age" in low):
        return "This video is age-restricted. The server needs cookies from a signed-in account."
    if "not available in your country" in low or "geo" in low and "restrict" in low:
        return "This video isn't available in the server's country."
    if "video unavailable" in low or "has been removed" in low or "404" in low:
        return "This video is unavailable or was removed."
    if "is live" in low or "live event" in low:
        return "Live streams can't be downloaded until they end."
    if "max_filesize" in low or "larger than max-filesize" in low or "file is larger" in low:
        return f"This file is bigger than the server limit ({config.MAX_FILESIZE_MB} MB)."
    if "timed out" in low or "connection" in low:
        return "The site didn't respond. Try again."
    if "ffmpeg" in low:
        return "Converting the file failed."
    first = msg.replace("ERROR: ", "").splitlines()[0] if msg else "Something went wrong."
    return first[:300]


def extract_info(url: str, playlist: bool = True) -> dict:
    opts = _base_opts() | {"skip_download": True, "noplaylist": not playlist}
    if playlist:
        opts["extract_flat"] = "in_playlist"
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            return ydl.sanitize_info(info)
    except (DownloadError, ExtractorError) as e:
        raise EngineError(friendly_error(e)) from e


def download(
    url: str,
    workdir: Path,
    *,
    kind: str,
    selector: str,
    container: str,
    audio_format: str,
    audio_bitrate: int,
    embed_cover: bool,
    on_progress: Callable[[dict], None],
    cancel: threading.Event,
) -> Path:
    workdir.mkdir(parents=True, exist_ok=True)

    def hook(d: dict) -> None:
        if cancel.is_set():
            raise Cancelled()
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            on_progress({
                "stage": "downloading",
                "downloaded": d.get("downloaded_bytes") or 0,
                "total": int(total) if total else None,
                "speed": d.get("speed"),
                "eta": d.get("eta"),
                "part": (d.get("info_dict") or {}).get("format_id"),
            })
        elif d["status"] == "finished":
            on_progress({"stage": "processing"})

    def pp_hook(d: dict) -> None:
        if cancel.is_set():
            raise Cancelled()
        if d["status"] == "started":
            names = {"FFmpegMerger": "merging", "FFmpegExtractAudio": "converting",
                     "EmbedThumbnail": "tagging", "FFmpegMetadata": "tagging"}
            on_progress({"stage": names.get(d.get("postprocessor"), "processing")})

    pps: list[dict] = []
    opts = _base_opts() | {
        "format": selector,
        "paths": {"home": str(workdir), "temp": str(workdir / ".tmp")},
        "outtmpl": "%(title).150B [%(id)s].%(ext)s",
        "windowsfilenames": True,
        "progress_hooks": [hook],
        "postprocessor_hooks": [pp_hook],
        "max_filesize": config.MAX_FILESIZE_MB * 1024 * 1024,
        "updatetime": False,
        "overwrites": True,
    }
    if kind == "video":
        opts["merge_output_format"] = container
        if container == "mkv":
            # MKV holds any codec, so single-file downloads can always be rewrapped.
            pps.append({"key": "FFmpegVideoRemuxer", "preferedformat": "mkv"})
    else:
        codec = {"mp3": "mp3", "m4a": "m4a", "opus": "opus"}[audio_format]
        pps.append({
            "key": "FFmpegExtractAudio",
            "preferredcodec": codec,
            "preferredquality": str(audio_bitrate) if audio_format == "mp3" else "0",
        })
        if embed_cover:
            opts["writethumbnail"] = True
            pps.append({"key": "FFmpegThumbnailsConvertor", "format": "jpg", "when": "before_dl"})
    pps.append({"key": "FFmpegMetadata", "add_metadata": True})
    if kind == "audio" and embed_cover:
        pps.append({"key": "EmbedThumbnail", "already_have_thumbnail": False})
    opts["postprocessors"] = pps

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
    except Cancelled:
        raise
    except (DownloadError, ExtractorError) as e:
        if cancel.is_set() or isinstance(getattr(e, "exc_info", [None, None])[1], Cancelled):
            raise Cancelled() from e
        raise EngineError(friendly_error(e)) from e

    if info and info.get("requested_downloads"):
        p = Path(info["requested_downloads"][-1].get("filepath") or "")
        if p.is_file():
            return p
    files = [f for f in workdir.iterdir() if f.is_file() and not f.name.endswith((".part", ".ytdl", ".jpg", ".webp", ".png"))]
    if not files:
        raise EngineError("The download finished but no file was made.")
    return max(files, key=lambda f: f.stat().st_size)


def versions() -> dict:
    ff = shutil.which("ffmpeg")
    ff_ver = None
    if ff:
        try:
            out = subprocess.run([ff, "-version"], capture_output=True, text=True, timeout=5).stdout
            ff_ver = out.split("\n")[0].split(" ")[2] if out else None
        except Exception:
            ff_ver = "unknown"
    return {"yt_dlp": yt_dlp.version.__version__, "ffmpeg": ff_ver}
