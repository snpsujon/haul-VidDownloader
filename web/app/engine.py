"""yt-dlp calls: link info, downloads, cookie checks and updates."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

import yt_dlp
from yt_dlp.utils import DownloadError, ExtractorError, download_range_func

from . import config, cookies

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")


class EngineError(Exception):
    """An error with a message that is safe to show to people."""

    def __init__(self, message: str, cookie_problem: bool = False):
        super().__init__(message)
        self.cookie_problem = cookie_problem


class Cancelled(Exception):
    pass


class _QuietLogger:
    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg): pass
    def error(self, msg): pass


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
        "http_headers": {"User-Agent": UA},
        "logger": _QuietLogger(),
    }
    if config.PROXY:
        opts["proxy"] = config.PROXY
    return opts


# Errors ------------------------------------------------------------------------------------

_COOKIE_HINTS = ("sign in to confirm", "confirm you’re not a bot", "confirm you're not a bot",
                 "use --cookies", "cookies for the authentication", "login required",
                 "this video may be inappropriate", "confirm your age")


def is_cookie_problem(msg: str) -> bool:
    low = msg.lower()
    return any(h in low for h in _COOKIE_HINTS)


def friendly_error(e: Exception | str) -> str:
    """Plain-language messages for the errors people actually hit."""
    msg = str(e)
    low = msg.lower()
    if "unsupported url" in low:
        return "This site isn't supported."
    if "private video" in low or "members-only" in low or "join this channel" in low:
        return "This video is private or members-only."
    if "confirm your age" in low or ("age" in low and "restricted" in low):
        return "This video is age-restricted, so it can't be downloaded here right now."
    if is_cookie_problem(msg):
        return "YouTube is blocking downloads here right now. Try again in a while."
    if "not available in your country" in low or ("geo" in low and "restrict" in low):
        return "This video isn't available in the server's country."
    if "video unavailable" in low or "has been removed" in low or "http error 404" in low:
        return "This video is unavailable or was removed."
    if "is live" in low or "live event" in low:
        return "Live streams can't be downloaded until they end."
    if "max_filesize" in low or "larger than max-filesize" in low or "file is larger" in low:
        return f"This file is bigger than the server limit ({config.MAX_FILESIZE_MB} MB)."
    if "requested format is not available" in low:
        return "That quality isn't available for this video any more. Look the link up again."
    if "timed out" in low or "connection" in low or "unable to download" in low:
        return "The site didn't respond. Try again."
    if "ffmpeg" in low:
        return "Converting the file failed."
    first = msg.replace("ERROR: ", "").splitlines()[0] if msg else "Something went wrong."
    return first[:300]


# YouTube client fallback -------------------------------------------------------------------

_preferred_client: dict = {"name": None}


def _is_youtube(url: str) -> bool:
    host = (urlparse(url).hostname or "").lower()
    return host.endswith(("youtube.com", "youtu.be", "youtube-nocookie.com"))


def _attempts(url: str) -> list[str | None]:
    """None means yt-dlp's own default clients."""
    if not _is_youtube(url):
        return [None]
    order: list[str | None] = [None] + list(config.YOUTUBE_FALLBACK_CLIENTS)
    pref = _preferred_client["name"]
    if pref in order:
        order.remove(pref)
        order.insert(0, pref)
    return order


def _retryable(msg: str) -> bool:
    low = msg.lower()
    return is_cookie_problem(msg) or any(s in low for s in (
        "requested format is not available", "http error 403", "page needs to be reloaded",
        "failed to extract any player response", "only images are available", "po token"))


def _run(url: str, opts: dict, action: Callable[[yt_dlp.YoutubeDL], dict], cancel: threading.Event | None = None) -> dict:
    """Runs action with each YouTube player client in turn until one works."""
    cookie_copy = cookies.private_copy()
    last: Exception | None = None
    try:
        for client in _attempts(url):
            o = dict(opts)
            if cookie_copy:
                o["cookiefile"] = cookie_copy
            if client:
                o["extractor_args"] = {"youtube": {"player_client": [client]}}
            try:
                with yt_dlp.YoutubeDL(o) as ydl:
                    result = action(ydl)
                if client != _preferred_client["name"] and _is_youtube(url):
                    _preferred_client["name"] = client
                return result
            except Cancelled:
                raise
            except (DownloadError, ExtractorError) as e:
                if cancel is not None and cancel.is_set():
                    raise Cancelled() from e
                last = e
                if not _retryable(str(e)):
                    break
        msg = str(last) if last else "Something went wrong."
        problem = is_cookie_problem(msg)
        if problem:
            cookies.note_failure(friendly_error(msg))
        raise EngineError(friendly_error(msg), cookie_problem=problem) from last
    finally:
        if cookie_copy:
            Path(cookie_copy).unlink(missing_ok=True)


# Info --------------------------------------------------------------------------------------

def extract_info(url: str, playlist: bool = True) -> dict:
    opts = _base_opts() | {"skip_download": True, "noplaylist": not playlist}
    if playlist:
        opts["extract_flat"] = "in_playlist"
        opts["playlistend"] = 500
    return _run(url, opts, lambda ydl: ydl.sanitize_info(ydl.extract_info(url, download=False)))


# Downloads ---------------------------------------------------------------------------------

@dataclass
class Options:
    kind: str = "video"              # video | audio
    selector: str = "bv*+ba/b"
    container: str = "mp4"
    audio_format: str = "mp3"
    audio_bitrate: int = 192
    embed_cover: bool = True
    subtitles: list[str] = field(default_factory=list)   # language codes to embed
    start: float | None = None        # trim, seconds
    end: float | None = None
    format_sort: list[str] = field(default_factory=list)


def download(url: str, workdir: Path, o: Options, *, on_progress: Callable[[dict], None],
             cancel: threading.Event) -> Path:
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
                     "EmbedThumbnail": "tagging", "FFmpegMetadata": "tagging",
                     "FFmpegEmbedSubtitle": "subtitles", "FFmpegSubtitlesConvertor": "subtitles"}
            on_progress({"stage": names.get(d.get("postprocessor"), "processing")})

    pps: list[dict] = []
    opts = _base_opts() | {
        "format": o.selector,
        "paths": {"home": str(workdir), "temp": str(workdir / ".tmp")},
        "outtmpl": "%(title).150B [%(id)s].%(ext)s",
        "windowsfilenames": True,
        "progress_hooks": [hook],
        "postprocessor_hooks": [pp_hook],
        "max_filesize": config.MAX_FILESIZE_MB * 1024 * 1024,
        "updatetime": False,
        "overwrites": True,
    }
    if o.format_sort:
        opts["format_sort"] = o.format_sort
    if o.start is not None or o.end is not None:
        opts["download_ranges"] = download_range_func(None, [(o.start or 0, o.end if o.end is not None else float("inf"))])
        opts["force_keyframes_at_cuts"] = True
    if o.kind == "video":
        opts["merge_output_format"] = o.container
        if o.container == "mkv":
            # MKV holds any codec, so single-file downloads can always be rewrapped.
            pps.append({"key": "FFmpegVideoRemuxer", "preferedformat": "mkv"})
        if o.subtitles:
            opts.update(writesubtitles=True, writeautomaticsub=True, subtitleslangs=o.subtitles)
            # WebM only takes WebVTT; MP4 and MKV take SRT.
            fmt = "vtt" if o.container == "webm" else "srt"
            pps.append({"key": "FFmpegSubtitlesConvertor", "format": fmt, "when": "before_dl"})
            pps.append({"key": "FFmpegEmbedSubtitle", "already_have_subtitle": False})
    else:
        pps.append({
            "key": "FFmpegExtractAudio",
            "preferredcodec": o.audio_format,
            "preferredquality": str(o.audio_bitrate) if o.audio_format == "mp3" else "0",
        })
        if o.embed_cover:
            opts["writethumbnail"] = True
            pps.append({"key": "FFmpegThumbnailsConvertor", "format": "jpg", "when": "before_dl"})
    pps.append({"key": "FFmpegMetadata", "add_metadata": True})
    if o.kind == "audio" and o.embed_cover:
        pps.append({"key": "EmbedThumbnail", "already_have_thumbnail": False})
    opts["postprocessors"] = pps

    info = _run(url, opts, lambda ydl: ydl.extract_info(url, download=True), cancel)

    if info and info.get("requested_downloads"):
        p = Path(info["requested_downloads"][-1].get("filepath") or "")
        if p.is_file():
            return p
    skip = (".part", ".ytdl", ".jpg", ".webp", ".png", ".srt", ".vtt")
    files = [f for f in workdir.iterdir() if f.is_file() and not f.name.endswith(skip)]
    if not files:
        raise EngineError("The download finished but no file was made.")
    return max(files, key=lambda f: f.stat().st_mtime)


# Admin helpers -----------------------------------------------------------------------------

def check_cookies() -> dict:
    """Signs-in check: YouTube's watch history only opens for a signed-in account."""
    if not cookies.path().exists():
        cookies.set_status(False, "No cookies are saved.")
        return cookies.summary()
    opts = _base_opts() | {"skip_download": True, "extract_flat": True, "playlistend": 1}
    try:
        _run("https://www.youtube.com/feed/history", opts,
             lambda ydl: ydl.extract_info("https://www.youtube.com/feed/history", download=False))
        cookies.set_status(True, "Signed in to YouTube.")
    except EngineError as e:
        if e.cookie_problem or "sign in" in str(e).lower() or "login" in str(e).lower():
            cookies.set_status(False, "YouTube didn't accept these cookies. Export fresh ones while signed in.")
        else:
            # Network trouble says nothing about the cookies; keep the last result.
            prev = cookies.summary()
            cookies.set_status(prev.get("ok"), "Couldn't reach YouTube to check: " + str(e))
    return cookies.summary()


def update_ytdlp() -> dict:
    """pip-installs the newest yt-dlp. The server picks it up after a restart."""
    before = yt_dlp.version.__version__
    proc = subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-U", "--no-cache-dir", "yt-dlp"],
                          capture_output=True, text=True, timeout=300)
    if proc.returncode != 0:
        raise EngineError("Updating yt-dlp failed: " + (proc.stderr.strip().splitlines() or ["unknown error"])[-1])
    out = subprocess.run([sys.executable, "-c", "import yt_dlp;print(yt_dlp.version.__version__)"],
                         capture_output=True, text=True, timeout=60).stdout.strip()
    return {"before": before, "installed": out or before, "restart_needed": bool(out) and out != before}


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


def restart_soon() -> None:
    """Exits so Docker's restart policy (or systemd) starts a fresh process."""
    threading.Timer(1.0, lambda: os._exit(3)).start()
