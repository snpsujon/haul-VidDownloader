import pytest
from yt_dlp.utils import DownloadError

from app import config, cookies, engine


class FakeYDL:
    seen = []

    def __init__(self, opts):
        self.opts = opts

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def extract_info(self, url, download=False):
        client = (self.opts.get("extractor_args") or {}).get("youtube", {}).get("player_client", [None])[0]
        FakeYDL.seen.append((client, self.opts.get("cookiefile")))
        if client != "tv":
            raise DownloadError("ERROR: [youtube] x: Sign in to confirm you're not a bot")
        return {"id": "x", "title": "ok"}


@pytest.fixture
def fake(monkeypatch, tmp_path):
    monkeypatch.setattr(config, "DATA_DIR", tmp_path)
    monkeypatch.setattr(engine.yt_dlp, "YoutubeDL", FakeYDL)
    engine._preferred_client["name"] = None
    FakeYDL.seen = []
    yield


def test_youtube_falls_back_to_other_clients_and_remembers(fake):
    run = lambda: engine._run("https://youtu.be/x", {}, lambda y: y.extract_info("u"))
    assert run()["title"] == "ok"
    assert [c for c, _ in FakeYDL.seen] == [None, "tv"]
    FakeYDL.seen = []
    run()
    assert [c for c, _ in FakeYDL.seen] == ["tv"]


def test_other_sites_get_one_try_and_cookie_errors_are_flagged(fake):
    with pytest.raises(engine.EngineError) as e:
        engine._run("https://vimeo.com/1", {}, lambda y: y.extract_info("u"))
    assert e.value.cookie_problem and len(FakeYDL.seen) == 1
    assert cookies.summary()["ok"] is False


def test_each_run_gets_its_own_cookie_copy(fake):
    cookies.save(".youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\tv\n")
    engine._run("https://youtu.be/x", {}, lambda y: y.extract_info("u"))
    files = {f for _, f in FakeYDL.seen}
    assert len(files) == 1 and str(cookies.path()) not in files
    assert not any(__import__("os").path.exists(f) for f in files)


def test_cookie_parsing():
    rows = cookies.parse("# Netscape HTTP Cookie File\n#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tv\n")
    assert rows == [{"domain": "youtube.com", "name": "SID", "expires": 0}]
    with pytest.raises(cookies.BadCookies):
        cookies.parse("SID=abc; HSID=def")
