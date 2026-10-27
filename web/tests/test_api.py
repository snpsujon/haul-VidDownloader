import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import config, engine, main
from tests.fake_info import youtube_info

URL = "https://www.youtube.com/watch?v=abc"


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(main.security, "check_url", lambda u: u.strip())
    calls = {"info": 0}

    def fake_info(url, playlist=True):
        calls["info"] += 1
        if "bad" in url:
            raise engine.EngineError("This video is unavailable or was removed.")
        return youtube_info()

    def fake_download(url, workdir: Path, *, kind, selector, container, audio_format, audio_bitrate, embed_cover, on_progress, cancel):
        calls["selector"] = selector
        workdir.mkdir(parents=True, exist_ok=True)
        for i in range(3):
            if cancel.is_set():
                raise engine.Cancelled()
            on_progress({"stage": "downloading", "downloaded": i * 10, "total": 30, "speed": 10, "eta": 3 - i, "part": "137"})
            time.sleep(0.05)
        on_progress({"stage": "merging"})
        p = workdir / f"Test video [abc].{container if kind == 'video' else audio_format}"
        p.write_bytes(b"x" * 30)
        return p

    monkeypatch.setattr(engine, "extract_info", fake_info)
    monkeypatch.setattr(engine, "download", fake_download)
    with TestClient(main.app) as c:
        c.calls = calls
        yield c


def wait_done(client, job_id):
    for _ in range(100):
        j = client.get(f"/api/jobs/{job_id}").json()
        if j["status"] not in ("queued", "running"):
            return j
        time.sleep(0.05)
    raise AssertionError("job never finished")


def test_site_and_docs_served(client):
    assert "haul" in client.get("/").text
    assert client.get("/api/openapi.json").json()["info"]["title"] == "Haul API"


def test_info_then_job_reuses_cached_info(client):
    r = client.post("/api/info", json={"url": URL})
    assert r.status_code == 200
    body = r.json()
    assert body["recommended_height"] == 1080 and body["audio_languages"]
    r = client.post("/api/jobs", json={"url": URL, "kind": "video", "height": 1080})
    assert r.status_code == 201
    job = wait_done(client, r.json()["id"])
    assert client.calls["info"] == 1
    assert client.calls["selector"].startswith("137+140-0/")
    assert job["status"] == "done" and job["filename"] == "Test video [abc].mp4"
    f = client.get(job["file_url"])
    assert f.status_code == 200 and f.content == b"x" * 30
    assert "attachment" in f.headers["content-disposition"]


def test_events_stream_ends_when_done(client):
    r = client.post("/api/jobs", json={"url": URL, "kind": "audio", "audio_format": "m4a", "language": "es"})
    text = client.get(f"/api/jobs/{r.json()['id']}/events").text
    assert text.count("event: progress") >= 2 and '"status": "done"' in text
    assert client.calls["selector"].startswith("140-1/")


def test_errors_are_plain(client):
    r = client.post("/api/info", json={"url": "https://www.youtube.com/watch?v=bad"})
    assert r.status_code == 422 and r.json()["detail"] == "This video is unavailable or was removed."
    assert client.get("/api/jobs/nope").status_code == 404


def test_delete_removes_file(client):
    job = wait_done(client, client.post("/api/jobs", json={"url": URL}).json()["id"])
    assert client.delete(f"/api/jobs/{job['id']}").json() == {"ok": True}
    assert client.get(f"/api/jobs/{job['id']}").status_code == 404


def test_api_keys(client, monkeypatch):
    monkeypatch.setattr(config, "API_KEYS", {"secret"})
    assert client.post("/api/info", json={"url": URL}).status_code == 401
    assert client.post("/api/info", json={"url": URL}, headers={"X-API-Key": "secret"}).status_code == 200
    assert client.get("/api/health").status_code == 200


def test_per_client_limit(client, monkeypatch):
    monkeypatch.setattr(config, "MAX_ACTIVE_PER_CLIENT", 1)
    first = client.post("/api/jobs", json={"url": URL})
    second = client.post("/api/jobs", json={"url": URL})
    assert first.status_code == 201 and second.status_code == 429
