from app import media
from tests.fake_info import youtube_info


def test_video_choices_prefer_container_codecs():
    info = youtube_info()
    mp4 = {c["height"]: c for c in media.video_choices(info, "mp4")}
    assert list(mp4) == [2160, 1080, 720, 360]
    assert mp4[1080]["selector"].startswith("137+140-0/")
    assert mp4[1080]["codecs"] == "AVC + AAC"
    assert mp4[1080]["size"] == (4000 + 129) * 1000 // 8 * 100
    webm = {c["height"]: c for c in media.video_choices(info, "webm")}
    assert webm[1080]["selector"].startswith("248+251-0/")
    assert mp4[360]["selector"].startswith("18/")
    assert mp4[2160]["label"] == "2160p · 4K" and mp4[2160]["spec"] == "60fps"


def test_language_changes_audio_track():
    info = youtube_info()
    langs = media.audio_languages(info)
    assert [(l["code"], l["name"], l["original"]) for l in langs] == [("en", "English", True), ("es", "Spanish", False)]
    es = {c["height"]: c for c in media.video_choices(info, "mp4", "es")}
    assert es[1080]["selector"].startswith("137+140-1/")
    audio = {c["format"]: c for c in media.audio_choices(info, "es")}
    assert audio["m4a"]["selector"].startswith("140-1/")
    assert "[language=es]" in audio["mp3"]["selector"]


def test_recommended_and_choose_selector():
    info = youtube_info()
    choices = media.video_choices(info, "mp4")
    assert media.recommended_height(choices) == 1080
    sel, label = media.choose_selector(info, "video", height=None, container="mp4", audio_format="mp3", language=None)
    assert sel.startswith("137+") and label == "1080p MP4"
    # A height that doesn't exist falls back to the closest one below.
    sel, label = media.choose_selector(info, "video", height=900, container="mp4", audio_format="mp3", language=None)
    assert label == "720p MP4"
    sel, label = media.choose_selector(info, "audio", height=None, container="mp4", audio_format="opus", language=None)
    assert sel.startswith("251-0/") and label == "Opus"


def test_direct_file_gets_best_available():
    info = {"id": "clip", "title": "clip", "url": "http://x/clip.mp4", "ext": "mp4", "formats": [{"format_id": "0", "url": "http://x/clip.mp4", "ext": "mp4"}]}
    choices = media.video_choices(info)
    assert [c["label"] for c in choices] == ["Best available"]


def test_playlist_summary():
    info = {"_type": "playlist", "id": "PL1", "title": "Mix", "entries": [
        {"id": "a", "title": "One", "url": "https://www.youtube.com/watch?v=a", "duration": 60}, None]}
    s = media.summarize(info)
    assert s["kind"] == "playlist" and s["count"] == 1 and s["entries"][0]["url"].endswith("v=a")
