"""Link info and format choices, the same picks the Android app offers.

Everything here works on yt-dlp's info dict, so it can be tested without the network.
"""
from __future__ import annotations

from typing import Any

QUALITY_NAMES = {4320: "8K", 2160: "4K", 1440: "2K", 1080: "Full HD", 720: "HD"}
CONTAINERS = ("mp4", "mkv", "webm")
AUDIO_FORMATS = ("mp3", "m4a", "opus")
MP3_BITRATES = (128, 192, 256, 320)

# Codec preference per container, best first.
_VIDEO_PREF = {
    "mp4": ("avc1", "av01", "vp09", "vp9", "hev1", "hvc1"),
    "webm": ("vp09", "vp9", "av01"),
    "mkv": ("av01", "vp09", "vp9", "avc1", "hev1", "hvc1"),
}
_AUDIO_PREF = {"mp4": ("mp4a",), "webm": ("opus",), "mkv": ("opus", "mp4a")}

_CODEC_NAMES = {
    "avc1": "AVC", "h264": "AVC", "av01": "AV1", "vp09": "VP9", "vp9": "VP9",
    "hev1": "HEVC", "hvc1": "HEVC", "mp4a": "AAC", "opus": "Opus", "vorbis": "Vorbis",
    "mp3": "MP3", "flac": "FLAC",
}


def codec_name(codec: str | None) -> str:
    if not codec or codec == "none":
        return ""
    key = codec.split(".")[0].lower()
    return _CODEC_NAMES.get(key, key.upper())


def _has(v: Any) -> bool:
    return bool(v) and v != "none"


def is_video_only(f: dict) -> bool:
    return _has(f.get("vcodec")) and not _has(f.get("acodec"))


def is_audio_only(f: dict) -> bool:
    return _has(f.get("acodec")) and not _has(f.get("vcodec"))


def is_combined(f: dict) -> bool:
    return _has(f.get("vcodec")) and _has(f.get("acodec"))


def _usable(f: dict) -> bool:
    # Storyboards and other image tracks are not media.
    return f.get("ext") not in ("mhtml",) and f.get("protocol") != "mhtml"


def fmt_size(f: dict, duration: float | None) -> int | None:
    size = f.get("filesize") or f.get("filesize_approx")
    if size:
        return int(size)
    tbr = f.get("tbr")
    if tbr and duration:
        return int(tbr * 1000 / 8 * duration)
    return None


def _rate(f: dict) -> float:
    return f.get("abr") or f.get("tbr") or 0


def height_label(h: int) -> str:
    name = QUALITY_NAMES.get(h)
    return f"{h}p · {name}" if name else f"{h}p"


def _pref_index(codec: str | None, prefs: tuple[str, ...]) -> int:
    c = (codec or "").lower()
    for i, p in enumerate(prefs):
        if c.startswith(p):
            return i
    return len(prefs)


def pick_video(cands: list[dict], container: str) -> dict | None:
    if not cands:
        return None
    prefs = _VIDEO_PREF.get(container, _VIDEO_PREF["mkv"])
    return min(cands, key=lambda f: (_pref_index(f.get("vcodec"), prefs), -(f.get("fps") or 0), -(f.get("tbr") or 0)))


def audio_languages(info: dict) -> list[dict]:
    """Audio tracks in more than one language (YouTube multi-audio, dubbed videos)."""
    seen: dict[str, dict] = {}
    for f in info.get("formats") or []:
        if not (is_audio_only(f) or is_combined(f)):
            continue
        lang = f.get("language")
        if not lang or lang in seen:
            continue
        note = f.get("format_note") or ""
        # YouTube notes look like "English (US) original (default), medium".
        name = note.split(",")[0] if "," in note else ""
        name = name.replace("(default)", "").replace("original", "").strip()
        seen[lang] = {
            "code": lang,
            "name": name or lang,
            "original": "original" in note.lower() or (f.get("language_preference") or 0) >= 10,
        }
    langs = list(seen.values())
    return langs if len(langs) > 1 else []


def _audio_pool(info: dict, language: str | None) -> list[dict]:
    pool = [f for f in info.get("formats") or [] if is_audio_only(f) and _usable(f)]
    if language:
        same = [f for f in pool if f.get("language") == language]
        if same:
            return same
    # No language asked: prefer the original track when tracks are labeled.
    originals = [f for f in pool if (f.get("language_preference") or 0) >= 10]
    return originals or pool


def best_audio_for(info: dict, container: str, language: str | None = None) -> dict | None:
    pool = _audio_pool(info, language)
    if not pool:
        return None
    prefs = _AUDIO_PREF.get(container, _AUDIO_PREF["mkv"])
    return min(pool, key=lambda f: (_pref_index(f.get("acodec"), prefs), -_rate(f)))


def _spec(f: dict) -> str:
    parts = []
    if f.get("fps"):
        parts.append(f"{round(f['fps'])}fps")
    if f.get("dynamic_range") and f["dynamic_range"] != "SDR":
        parts.append(f["dynamic_range"])
    return " · ".join(p for p in parts if p)


def _lang_filter(language: str | None) -> str:
    return f"[language={language}]" if language else ""


def video_choices(info: dict, container: str = "mp4", language: str | None = None) -> list[dict]:
    d = info.get("duration")
    formats = [f for f in info.get("formats") or [] if _usable(f)]
    video_only = [f for f in formats if is_video_only(f) and (f.get("height") or 0) > 0]
    combined = [f for f in formats if is_combined(f) and (f.get("height") or 0) > 0]
    audio = best_audio_for(info, container, language)
    heights = sorted({f["height"] for f in video_only + combined}, reverse=True)
    lf = _lang_filter(language)
    out = []
    for h in heights:
        pick = pick_video([f for f in video_only if f["height"] == h], container)
        if pick is not None:
            a_size = fmt_size(audio, d) if audio else 0
            v_size = fmt_size(pick, d)
            sel = pick["format_id"]
            selector = (f"{sel}+{audio['format_id']}/" if audio else "") + \
                f"{sel}+bestaudio{lf}/{sel}+bestaudio/best[height<={h}]/best"
            codecs = [codec_name(pick.get("vcodec")), codec_name(audio.get("acodec")) if audio else ""]
            out.append({
                "height": h,
                "label": height_label(h),
                "spec": _spec(pick),
                "size": (v_size + (a_size or 0)) if v_size else None,
                "selector": selector,
                "codecs": " + ".join(c for c in codecs if c),
            })
        else:
            c = pick_video([f for f in combined if f["height"] == h], container)
            if c is None:
                continue
            out.append({
                "height": h,
                "label": height_label(h),
                "spec": _spec(c),
                "size": fmt_size(c, d),
                "selector": f"{c['format_id']}/best[height<={h}]/best",
                "codecs": " + ".join(x for x in (codec_name(c.get("vcodec")), codec_name(c.get("acodec"))) if x),
            })
    if not out and _maybe_video(info):
        # Direct file links and some sites don't say what's inside; let yt-dlp pick.
        out.append({"height": 0, "label": "Best available", "spec": "Quality not listed by the site",
                    "size": None, "selector": "bv*+ba/b", "codecs": ""})
    return out


def _maybe_video(info: dict) -> bool:
    formats = info.get("formats") or [info]
    return any(_has(f.get("vcodec")) or f.get("vcodec") is None for f in formats if f.get("url") or f.get("format_id"))


def recommended_height(choices: list[dict]) -> int | None:
    hs = [c["height"] for c in choices]
    if not hs:
        return None
    if 1080 in hs:
        return 1080
    below = [h for h in hs if h <= 1080]
    return max(below) if below else max(hs)


def _kbps(f: dict) -> int:
    return int(round(_rate(f)))


def audio_choices(info: dict, language: str | None = None, mp3_bitrate: int = 192) -> list[dict]:
    d = info.get("duration")
    pool = _audio_pool(info, language)
    lf = _lang_filter(language)
    out = [{
        "format": "mp3",
        "label": "MP3",
        "spec": "Plays everywhere · re-encoded",
        "size": int(d * mp3_bitrate * 1000 / 8) if d else None,
        "selector": f"bestaudio{lf}/bestaudio/best",
        "badge": None,
    }]
    m4a = max((f for f in pool if f.get("ext") == "m4a" or (f.get("acodec") or "").startswith("mp4a")), key=_rate, default=None)
    if m4a:
        k = _kbps(m4a)
        out.append({
            "format": "m4a",
            "label": "M4A",
            "spec": f"AAC {k}k · original, no re-encode" if k else "AAC · original, no re-encode",
            "size": fmt_size(m4a, d),
            "selector": f"{m4a['format_id']}/bestaudio[ext=m4a]{lf}/bestaudio[ext=m4a]/bestaudio",
            "badge": "Fastest",
        })
    opus = max((f for f in pool if (f.get("acodec") or "").startswith("opus")), key=_rate, default=None)
    if opus:
        k = _kbps(opus)
        out.append({
            "format": "opus",
            "label": "Opus",
            "spec": f"{k}k · original, smallest" if k else "Original, smallest",
            "size": fmt_size(opus, d),
            "selector": f"{opus['format_id']}/bestaudio[acodec=opus]{lf}/bestaudio",
            "badge": None,
        })
    return out


def summarize(info: dict, container: str = "mp4", language: str | None = None) -> dict:
    """The public shape of /api/info."""
    if info.get("_type") == "playlist" or info.get("entries") is not None:
        entries = []
        for e in info.get("entries") or []:
            if not e:
                continue
            entries.append({
                "index": len(entries) + 1,
                "id": e.get("id"),
                "title": e.get("title"),
                "url": e.get("url") or e.get("webpage_url"),
                "duration": e.get("duration"),
                "uploader": e.get("uploader") or e.get("channel"),
                "thumbnail": _thumb(e),
            })
        return {
            "kind": "playlist",
            "id": info.get("id"),
            "title": info.get("title"),
            "uploader": info.get("uploader") or info.get("channel"),
            "webpage_url": info.get("webpage_url"),
            "extractor": info.get("extractor_key") or info.get("extractor"),
            "count": len(entries),
            "entries": entries,
        }
    video = video_choices(info, container, language)
    return {
        "kind": "media",
        "id": info.get("id"),
        "title": info.get("title"),
        "uploader": info.get("uploader") or info.get("channel") or info.get("creator"),
        "duration": info.get("duration"),
        "thumbnail": _thumb(info),
        "webpage_url": info.get("webpage_url") or info.get("original_url"),
        "extractor": info.get("extractor_key") or info.get("extractor"),
        "is_live": bool(info.get("is_live")),
        "container": container,
        "language": language,
        "audio_languages": audio_languages(info),
        "video": video,
        "recommended_height": recommended_height(video),
        "audio": audio_choices(info, language),
        "subtitles": subtitle_choices(info),
        "stats": {
            "views": info.get("view_count"),
            "likes": info.get("like_count"),
            "comments": info.get("comment_count"),
            "followers": info.get("channel_follower_count"),
            "uploaded": info.get("upload_date"),
        },
        "channel_url": info.get("channel_url") or info.get("uploader_url"),
        "description": (info.get("description") or "")[:500],
        "chapters": [{"title": c.get("title"), "start": c.get("start_time"), "end": c.get("end_time")}
                     for c in (info.get("chapters") or [])][:100],
    }


def subtitle_choices(info: dict) -> list[dict]:
    """Uploaded subtitles first, then YouTube's automatic captions in the original language."""
    out = []
    subs = info.get("subtitles") or {}
    for code, tracks in subs.items():
        if code == "live_chat":
            continue
        name = (tracks[0].get("name") if tracks else None) or code
        out.append({"code": code, "name": name, "auto": False})
    auto = info.get("automatic_captions") or {}
    have = {s["code"] for s in out}
    # Auto captions come in every translated language; only offer the spoken ones.
    for code, tracks in auto.items():
        if code in have or "-" in code and not code.endswith("-orig"):
            continue
        if code.endswith("-orig") or code == info.get("language"):
            name = (tracks[0].get("name") if tracks else None) or code
            out.append({"code": code, "name": name, "auto": True})
    return sorted(out, key=lambda s: (s["auto"], s["name"].lower()))


def direct_links(info: dict) -> list[dict]:
    """Formats a browser can fetch straight from the site, without going through this server.

    Only single files with both sound and picture, or sound alone. Many sites (YouTube
    included) tie these links to the server's IP, so they don't always open elsewhere.
    """
    d = info.get("duration")
    out = []
    for f in info.get("formats") or []:
        url = f.get("url") or ""
        proto = (f.get("protocol") or "").lower()
        if not url or "m3u8" in proto or "dash" in proto or ".m3u8" in url or not _usable(f):
            continue
        if is_combined(f):
            kind = "video"
        elif is_audio_only(f):
            kind = "audio"
        else:
            continue
        out.append({
            "kind": kind,
            "format_id": f.get("format_id"),
            "ext": f.get("ext"),
            "height": f.get("height"),
            "label": height_label(f["height"]) if kind == "video" and f.get("height") else
                     f"{codec_name(f.get('acodec'))} {_kbps(f)}k".strip(),
            "language": f.get("language"),
            "size": fmt_size(f, d),
            "url": url,
        })
    out.sort(key=lambda x: (x["kind"] != "video", -(x["height"] or 0), -(x["size"] or 0)))
    return out


def _thumb(info: dict) -> str | None:
    if info.get("thumbnail"):
        return info["thumbnail"]
    thumbs = info.get("thumbnails") or []
    return thumbs[-1].get("url") if thumbs else None


def choose_selector(info: dict, kind: str, *, height: int | None, container: str,
                    audio_format: str, language: str | None) -> tuple[str, str]:
    """Selector and a short label for a job request."""
    if kind == "audio":
        for c in audio_choices(info, language):
            if c["format"] == audio_format:
                return c["selector"], c["label"]
        lf = _lang_filter(language)
        return f"bestaudio{lf}/bestaudio/best", audio_format.upper()
    choices = video_choices(info, container, language)
    if not choices:
        return "bv*+ba/b", "Best"
    if height is None:
        height = recommended_height(choices)
    exact = [c for c in choices if c["height"] == height]
    if not exact:
        below = [c for c in choices if c["height"] <= height]
        exact = [max(below, key=lambda c: c["height"])] if below else [min(choices, key=lambda c: c["height"])]
    c = exact[0]
    return c["selector"], (f"{c['height']}p " if c["height"] else "Best ") + container.upper()


_SORT = {
    "mp4": ["vcodec:h264", "acodec:aac"],
    "webm": ["vcodec:vp9", "acodec:opus"],
    "mkv": [],
}


def generic_selector(kind: str, *, height: int | None, audio_format: str, language: str | None) -> str:
    """A selector that works for any video, for playlists where each item has its own formats."""
    lf = _lang_filter(language)
    if kind == "audio":
        if audio_format == "m4a":
            return f"bestaudio[ext=m4a]{lf}/bestaudio[ext=m4a]/bestaudio{lf}/bestaudio/best"
        if audio_format == "opus":
            return f"bestaudio[acodec=opus]{lf}/bestaudio[acodec=opus]/bestaudio{lf}/bestaudio/best"
        return f"bestaudio{lf}/bestaudio/best"
    h = f"[height<={height}]" if height else ""
    return f"bv*{h}+ba{lf}/bv*{h}+ba/b{h}/bv*+ba/b"


def format_sort(container: str) -> list[str]:
    return _SORT.get(container, [])
