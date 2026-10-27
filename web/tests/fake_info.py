"""A trimmed YouTube-style info dict with two audio languages."""


def youtube_info():
    def v(fid, h, vcodec, ext, tbr, fps=30):
        return {"format_id": fid, "height": h, "vcodec": vcodec, "acodec": "none", "ext": ext,
                "tbr": tbr, "fps": fps, "url": "x"}

    def a(fid, acodec, ext, abr, lang, pref, note):
        return {"format_id": fid, "vcodec": "none", "acodec": acodec, "ext": ext, "abr": abr,
                "tbr": abr, "language": lang, "language_preference": pref, "format_note": note, "url": "x"}

    return {
        "id": "abc", "title": "Test video", "uploader": "Someone", "duration": 100,
        "thumbnail": "https://i.ytimg.com/vi/abc/hq.jpg", "webpage_url": "https://www.youtube.com/watch?v=abc",
        "extractor_key": "Youtube",
        "formats": [
            {"format_id": "sb0", "ext": "mhtml", "protocol": "mhtml", "vcodec": "none", "acodec": "none"},
            a("140-0", "mp4a.40.2", "m4a", 129, "en", 10, "English original (default), medium"),
            a("251-0", "opus", "webm", 135, "en", 10, "English original (default), medium"),
            a("140-1", "mp4a.40.2", "m4a", 129, "es", -1, "Spanish, medium"),
            a("251-1", "opus", "webm", 133, "es", -1, "Spanish, medium"),
            v("137", 1080, "avc1.640028", "mp4", 4000),
            v("248", 1080, "vp9", "webm", 2500),
            v("399", 1080, "av01.0.08M.08", "mp4", 2000),
            v("136", 720, "avc1.4d401f", "mp4", 2000),
            v("315", 2160, "vp9", "webm", 15000, fps=60),
            {"format_id": "18", "height": 360, "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "ext": "mp4", "tbr": 500, "url": "x"},
        ],
    }
