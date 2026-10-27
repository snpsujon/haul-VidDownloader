import io
import json
import time
import zipfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import config, engine, main, releases
from tests.fake_info import youtube_info

URL = "https://www.youtube.com/watch?v=abc"


FIXTURES = Path(__file__).parent / "fixtures"


def make_apk(libs, manifest="release-manifest.bin"):
    """A tiny APK: the real Haul release manifest (version 0.1.0, code 100) plus lib/ folders."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", (FIXTURES / manifest).read_bytes())
        for abi in libs:
            z.writestr(f"lib/{abi}/libx.so", b"so")
    return buf.getvalue()


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(main.security, "check_url", lambda u: u.strip())
    calls = {"info": 0}

    def fake_info(url, playlist=True):
        calls["info"] += 1
        if "bad" in url:
            raise engine.EngineError("This video is unavailable or was removed.")
        if "bot" in url:
            raise engine.EngineError("YouTube is asking the server to sign in.", cookie_problem=True)
        if "list=" in url and playlist:
            return {"_type": "playlist", "id": "PL", "title": "My list", "entries": [
                {"id": "a", "title": "One", "url": "https://www.youtube.com/watch?v=a"},
                {"id": "b", "title": "Two", "url": "https://www.youtube.com/watch?v=b"},
                {"id": "fail", "title": "Three", "url": "https://www.youtube.com/watch?v=fail"}]}
        return youtube_info()

    def fake_download(url, workdir: Path, o, *, on_progress, cancel):
        calls["selector"] = o.selector
        calls["options"] = o
        if "fail" in url:
            raise engine.EngineError("This video is unavailable or was removed.")
        kind, container, audio_format = o.kind, o.container, o.audio_format
        workdir.mkdir(parents=True, exist_ok=True)
        for i in range(3):
            if cancel.is_set():
                raise engine.Cancelled()
            on_progress({"stage": "downloading", "downloaded": i * 10, "total": 30, "speed": 10, "eta": 3 - i, "part": "137"})
            time.sleep(0.05)
        on_progress({"stage": "merging"})
        p = workdir / f"Test video [{url[-1]}].{container if kind == 'video' else audio_format}"
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
    assert job["status"] == "done" and job["filename"] == "Test video [c].mp4"
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


def test_cookie_problem_is_flagged(client):
    r = client.post("/api/info", json={"url": "https://www.youtube.com/watch?v=bot"})
    assert r.status_code == 422 and r.json()["detail"]["cookie_problem"] is True


def test_playlist_job_zips_and_skips_failures(client):
    import zipfile, io
    r = client.post("/api/jobs", json={"url": "https://www.youtube.com/playlist?list=PL", "kind": "audio",
                                       "audio_format": "m4a", "playlist": True})
    assert r.status_code == 201, r.text
    job = wait_done(client, r.json()["id"])
    assert job["status"] == "done" and job["filename"] == "My list.zip"
    assert job["playlist"]["count"] == 3 and len(job["playlist"]["failed"]) == 1
    z = zipfile.ZipFile(io.BytesIO(client.get(job["file_url"]).content))
    assert sorted(z.namelist()) == ["001 Test video [a].m4a", "002 Test video [b].m4a"]
    assert client.calls["selector"].startswith("bestaudio[ext=m4a]")


def test_playlist_items_subset(client):
    r = client.post("/api/jobs", json={"url": "https://www.youtube.com/playlist?list=PL", "playlist": True,
                                       "playlist_items": [2]})
    job = wait_done(client, r.json()["id"])
    assert job["filename"] == "Test video [b].mp4"


def test_trim_and_subtitles(client):
    r = client.post("/api/jobs", json={"url": URL, "start": 10, "end": 70.5, "subtitles": ["en"]})
    assert r.status_code == 201
    assert "0:10–1:10" in r.json()["label"] and "subs en" in r.json()["label"]
    wait_done(client, r.json()["id"])
    o = client.calls["options"]
    assert (o.start, o.end, o.subtitles) == (10, 70.5, ["en"])
    bad = client.post("/api/jobs", json={"url": URL, "start": 50, "end": 20})
    assert bad.status_code == 422


def test_batch(client):
    r = client.post("/api/jobs/batch", json={"urls": [URL + "1", "https://www.youtube.com/watch?v=bad", URL + "1"],
                                             "options": {"url": "x", "kind": "audio"}})
    res = r.json()["results"]
    assert len(res) == 2 and "job" in res[0] and res[1]["status"] == 422


def test_direct_links(client):
    r = client.get("/api/direct-links", params={"url": URL})
    assert r.status_code == 200
    kinds = {l["kind"] for l in r.json()["links"]}
    assert kinds == {"video", "audio"}


def test_legacy_extension_flow(client):
    r = client.post("/api/download", data={"url": URL, "format": "audio", "quality": "best"},
                    headers={"X-Extension-Request": "true", "X-Extension-Version": "1.4"})
    sid = r.json()["session_id"]
    for _ in range(100):
        p = client.get("/api/progress", headers={"X-Session-ID": sid}).json()
        if p["status"] == "Completed":
            break
        time.sleep(0.05)
    assert p["status"] == "Completed" and p["percent"] == "100.0%"
    f = client.get("/downloads/" + p["filename"])
    assert f.status_code == 200 and f.content == b"x" * 30
    old = client.get("/api/direct-links", params={"url": URL, "format": "audio"}).json()
    assert old["success"] and "m4a" in old["links"]


def test_admin(client, monkeypatch):
    monkeypatch.setattr(config, "ADMIN_PASSWORD", "pw")
    monkeypatch.setattr(engine, "check_cookies", lambda: main.cookies.summary())
    assert client.get("/api/admin/overview").status_code == 401
    assert client.post("/api/admin/login", json={"password": "nope"}).status_code == 401
    tok = client.post("/api/admin/login", json={"password": "pw"}).json()["token"]
    h = {"X-Admin-Token": tok}
    wait_done(client, client.post("/api/jobs", json={"url": URL}).json()["id"])
    ov = client.get("/api/admin/overview", headers=h).json()
    assert ov["stats"]["today"] >= 1
    assert client.get("/api/admin/requests", headers=h).json()["requests"][0]["status"] == "done"
    bad = client.put("/api/admin/cookies", headers=h, json={"text": "hello"})
    assert bad.status_code == 400
    text = ".youtube.com\tTRUE\t/\tTRUE\t4102444800\tLOGIN_INFO\tabc\n.youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\tdef\n"
    c = client.put("/api/admin/cookies", headers=h, json={"text": text}).json()
    assert c["present"] and c["youtube_signed_in"] and c["count"] == 2
    assert client.get("/api/admin/requests/export", headers=h).text.startswith("id,ts")
    assert client.delete("/api/admin/cookies", headers=h).json()["present"] is False


def test_file_is_removed_soon_after_it_is_sent(client, monkeypatch):
    job = wait_done(client, client.post("/api/jobs", json={"url": URL}).json()["id"])
    assert job["expires"] - job["finished"] == pytest.approx(config.FILE_TTL_MIN * 60, abs=1)
    client.get(job["file_url"] + "?inline=1")          # previews don't count
    assert client.get(f"/api/jobs/{job['id']}").json()["expires"] == job["expires"]
    client.get(job["file_url"])
    after = client.get(f"/api/jobs/{job['id']}").json()
    assert after["expires"] < job["expires"]
    monkeypatch.setattr(config, "SEND_GRACE_MIN", 0)
    main.app.state.jobs.sweep()
    assert client.get(f"/api/jobs/{job['id']}").status_code == 404


def test_releases_upload_and_downloads(client, monkeypatch):
    monkeypatch.setattr(config, "ADMIN_PASSWORD", "pw")
    h = {"X-Admin-Token": client.post("/api/admin/login", json={"password": "pw"}).json()["token"]}
    assert client.get("/api/releases").json() == {"android": None, "extension": None, "helper": None, "files": []}
    assert client.get("/app/version.json").status_code == 404
    r = client.post("/api/admin/releases/android", headers=h, data={"changelog": "- Faster\n- Fixes"},
                    files=[("apks", ("app-universal-release.apk", make_apk(["arm64-v8a", "x86_64"]))),
                           ("apks", ("app-arm64-v8a-release.apk", make_apk(["arm64-v8a"])))])
    assert r.status_code == 200, r.text
    assert r.json()["version"] == "0.1.0" and r.json()["apks"] == ["arm64-v8a", "universal"]
    v = client.get("/app/version.json").json()
    assert v["versionName"] == "0.1.0" and v["versionCode"] == 100 and v["changelog"] == ["Faster", "Fixes"]
    assert v["apks"]["universal"]["url"].endswith("/app/haul-0.1.0-universal.apk")
    assert client.get("/get/android").content == make_apk(["arm64-v8a", "x86_64"])
    # Adding one more CPU build of the same version keeps the others and the changelog.
    client.post("/api/admin/releases/android", headers=h, data={"force_update": "true"},
                files=[("apks", ("app-x86_64-release.apk", make_apk(["x86_64"])))])
    v = client.get("/app/version.json").json()
    assert set(v["apks"]) == {"universal", "arm64-v8a", "x86_64"} and v["changelog"] == ["Faster", "Fixes"]
    assert v["minSupported"] == 100
    # Debug builds, non-APKs, and older versions are refused.
    dbg = client.post("/api/admin/releases/android", headers=h,
                      files=[("apks", ("Haul-debug.apk", make_apk([], manifest="debug-manifest.bin")))])
    assert dbg.status_code == 400 and "debug build" in dbg.json()["detail"]
    assert client.post("/api/admin/releases/android", headers=h,
                       files=[("apks", ("x.apk", b"not a zip"))]).status_code == 400
    from app import apkinfo
    monkeypatch.setattr(apkinfo, "manifest_versions", lambda b: (50, "0.0.5", "app.haul.android"))
    old = client.post("/api/admin/releases/android", headers=h, files=[("apks", ("old.apk", make_apk([])))])
    assert old.status_code == 400 and "older" in old.json()["detail"]
    assert client.post("/api/admin/releases/helper", headers=h, data={"version": "1.0"},
                       files={"file": ("haul-helper.exe", b"MZ")}).status_code == 200
    assert client.get("/get/helper").content == b"MZ"
    assert client.post("/api/admin/releases/extension", headers=h,
                       data={"store_url": "https://chromewebstore.google.com/detail/x"}).status_code == 200
    assert client.get("/get/extension", follow_redirects=False).status_code == 307
    assert client.post("/api/admin/releases/files", headers=h, data={"title": "Guide"},
                       files={"file": ("../../evil name.pdf", b"PDF")}).json()["name"] == "evil name.pdf"
    pub = client.get("/api/releases").json()
    assert pub["helper"]["version"] == "1.0" and pub["files"][0]["title"] == "Guide"
    assert client.get("/files/evil name.pdf").content == b"PDF"
    assert client.post("/api/releases/files", files={"file": ("x", b"x")}).status_code in (404, 405)
    assert client.post("/api/admin/releases/files", files={"file": ("x", b"x")}).status_code == 401
    client.delete("/api/admin/releases/helper", headers=h)
    assert client.get("/get/helper").status_code == 404


def test_send_to_phone_pairing(client, monkeypatch):
    from app import pair
    monkeypatch.setattr(pair, "check_url", lambda u: u.strip())
    p = client.post("/api/pair").json()
    assert p["expires_in"] == 600 and len(p["send_key"]) >= 40
    assert client.get("/api/pair/status", params={"id": p["pair_id"], "key": p["send_key"]}).json()["claimed"] is False
    # Nothing can be sent before the phone claims it, and wrong keys look the same as unknown ones.
    send = {"pair_id": p["pair_id"], "send_key": p["send_key"], "url": "https://youtu.be/x", "mode": "audio", "title": "X"}
    assert client.post("/api/pair/send", json=send).status_code == 404
    bad = client.post("/api/pair/claim", json={"pair_id": p["pair_id"], "receive_token": "nope"})
    assert bad.status_code == 404 and bad.json() == {"ok": False}
    assert client.post("/api/pair/claim", json={"pair_id": p["pair_id"], "receive_token": p["receive_token"],
                                                "device_name": "Pixel"}).json() == {"ok": True}
    st = client.get("/api/pair/status", params={"id": p["pair_id"], "key": p["send_key"]})
    assert st.json() == {"ok": True, "claimed": True, "device_name": "Pixel"}
    assert st.headers["access-control-allow-origin"] == "*"
    item = client.post("/api/pair/send", json=send).json()
    assert item["ok"] and item["item_id"]
    # The receive token can't be used to send, and the send key can't read the inbox.
    assert client.post("/api/pair/send", json={**send, "send_key": p["receive_token"]}).status_code == 404
    assert client.get("/api/pair/inbox", params={"id": p["pair_id"], "token": p["send_key"]}).status_code == 404
    box = client.get("/api/pair/inbox", params={"id": p["pair_id"], "token": p["receive_token"]}).json()
    assert [(i["url"], i["mode"], i["title"]) for i in box["items"]] == [("https://youtu.be/x", "audio", "X")]
    assert client.get("/api/pair/inbox", params={"id": p["pair_id"], "token": p["receive_token"]}).json()["items"] == []
    assert client.post("/api/pair/unpair", json={"pair_id": p["pair_id"], "receive_token": p["receive_token"]}).json() == {"ok": True}
    assert client.get("/api/pair/status", params={"id": p["pair_id"], "key": p["send_key"]}).status_code == 404


def test_pairing_expires_and_caps_queue(client, monkeypatch):
    from app import pair
    monkeypatch.setattr(pair, "check_url", lambda u: u.strip())
    monkeypatch.setattr(pair, "_limited", lambda *a, **k: False)
    p = client.post("/api/pair").json()
    real_time = pair.time.time
    monkeypatch.setattr(pair.time, "time", lambda: real_time() + 601)  # past 10 minutes, still unclaimed
    assert client.post("/api/pair/claim", json={"pair_id": p["pair_id"], "receive_token": p["receive_token"]}).status_code == 404
    monkeypatch.setattr(pair.time, "time", real_time)
    p = client.post("/api/pair").json()
    client.post("/api/pair/claim", json={"pair_id": p["pair_id"], "receive_token": p["receive_token"]})
    send = {"pair_id": p["pair_id"], "send_key": p["send_key"], "url": "https://a.b/c"}
    for _ in range(50):
        assert client.post("/api/pair/send", json=send).status_code == 200
    assert client.post("/api/pair/send", json=send).status_code == 429


def test_info_pages_and_contact(client, monkeypatch):
    for page in ("about", "privacy", "terms", "copyright", "contact", "helper"):
        r = client.get(f"/{page}")
        assert r.status_code == 200 and 'href="privacy"' in r.text, page
    assert client.get("/api/health").json()["contact_email"] is None
    monkeypatch.setattr(config, "CONTACT_EMAIL", "hi@example.com")
    assert client.get("/api/health").json()["contact_email"] == "hi@example.com"


def test_charts_and_alerts(client, monkeypatch):
    from app import store, upkeep
    monkeypatch.setattr(config, "ADMIN_PASSWORD", "pw")
    h = {"X-Admin-Token": client.post("/api/admin/login", json={"password": "pw"}).json()["token"]}
    store.clear()
    for i in range(5):
        store.log(job=f"y{i}", url="https://youtu.be/x", site="Youtube", status="error")
    store.log(job="v1", url="https://vimeo.com/1", site="Vimeo", status="done", size=10)
    c = client.get("/api/admin/charts?days=7", headers=h).json()
    assert len(c["daily"]) == 7 and c["daily"][-1]["total"] == 6 and c["daily"][-1]["failed"] == 5
    assert c["sites"][0] == {"site": "Youtube", "total": 5, "failed": 5}
    o = client.get("/api/admin/overview", headers=h).json()
    assert any("YouTube downloads failed" in a["text"] for a in o["alerts"])
    store.clear()


def test_nightly_update_restarts_only_when_changed(monkeypatch):
    from app import engine, upkeep

    class Jobs:
        def active(self):
            return 0
    restarts = []
    monkeypatch.setattr(engine, "restart_soon", lambda: restarts.append(1))
    monkeypatch.setattr(engine, "update_ytdlp", lambda: {"before": "1", "installed": "1", "restart_needed": False})
    upkeep.nightly(Jobs())
    assert restarts == [] and upkeep.state()["update"]["installed"] == "1"
    monkeypatch.setattr(engine, "update_ytdlp", lambda: {"before": "1", "installed": "2", "restart_needed": True})
    upkeep.nightly(Jobs())
    assert restarts == [1]

    def broken():
        raise engine.EngineError("no network")
    monkeypatch.setattr(engine, "update_ytdlp", broken)
    upkeep.nightly(Jobs())
    assert upkeep.state()["update"]["error"] == "no network"


def test_seo_pages_sitemap_and_robots(client):
    home = client.get("/").text
    assert '<link rel="canonical" href="http://testserver/">' in home and "FAQPage" in home
    assert 'href="youtube-downloader"' in home and 'id="url"' in home
    yt = client.get("/youtube-downloader").text
    assert "<title>YouTube video downloader" in yt and "<h1>Download YouTube videos.</h1>" in yt
    assert 'href="http://testserver/youtube-downloader"' in yt and 'id="mediacard"' in yt
    assert 'data-kind="audio"' in client.get("/youtube-to-mp3").text
    sm = client.get("/sitemap.xml")
    assert sm.headers["content-type"].startswith("application/xml") and "/tiktok-downloader</loc>" in sm.text
    assert "Sitemap: http://testserver/sitemap.xml" in client.get("/robots.txt").text


def test_pages_and_scripts_are_revalidated(client):
    for path in ("/", "/admin", "/app.js", "/admin.js", "/style.css"):
        assert client.get(path).headers.get("cache-control") == "no-cache", path


def test_helper_builds_and_install_script(client, monkeypatch):
    monkeypatch.setattr(config, "ADMIN_PASSWORD", "pw")
    h = {"X-Admin-Token": client.post("/api/admin/login", json={"password": "pw"}).json()["token"]}
    assert client.get("/get/helper/mac").status_code == 404
    r = client.post("/api/admin/releases/helper", headers=h, data={"version": "0.4.0"},
                    files={"file": ("haul-helper.exe", b"MZwin"), "mac": ("haul-helper-mac", b"\xcf\xfa\xed\xfemac"),
                           "linux-x64": ("haul-helper-linux-x64", b"\x7fELFx64")})
    assert r.status_code == 200, r.text
    # A later upload of one build keeps the others.
    client.post("/api/admin/releases/helper", headers=h, data={"version": "0.4.1"},
                files={"linux-arm64": ("haul-helper-linux-arm64", b"\x7fELFarm")})
    rel = client.get("/api/releases").json()["helper"]
    assert rel["version"] == "0.4.1" and set(rel["builds"]) == {"windows", "mac", "linux-x64", "linux-arm64"}
    mac = client.get("/get/helper/mac")
    assert mac.content == b"\xcf\xfa\xed\xfemac" and mac.headers["content-type"] == "application/octet-stream"
    assert client.get("/get/helper/linux-arm64").content == b"\x7fELFarm"
    assert client.get("/get/helper").content == b"MZwin"
    assert client.get("/get/helper/windows-xp").status_code == 404
    bad = client.post("/api/admin/releases/helper", headers=h, files={"mac": ("haul-helper.exe", b"MZ")})
    assert bad.status_code == 400
    sh = client.get("/install.sh")
    assert sh.headers["content-type"].startswith("text/plain") and "\r" not in sh.text
    assert sh.text.startswith("#!/bin/sh") and 'SITE="${HAUL_SITE:-http://testserver}"' in sh.text
    client.delete("/api/admin/releases/helper", headers=h)


def test_supported_sites_page(client):
    r = client.get("/sites")
    assert r.status_code == 200 and "<h1>Supported sites</h1>" in r.text
    assert 'data-n="youtube"' in r.text and 'data-n="generic"' not in r.text
    assert r.text.count("<li data-n=") > 1000
    assert 'href="sites"' in client.get("/").text


def test_upload_in_pieces_then_publish(client, monkeypatch):
    monkeypatch.setattr(config, "ADMIN_PASSWORD", "pw")
    h = {"X-Admin-Token": client.post("/api/admin/login", json={"password": "pw"}).json()["token"]}
    client.delete("/api/admin/releases/android", headers=h)
    apk = make_apk(["arm64-v8a"])
    up = client.post("/api/admin/releases/uploads", headers=h).json()["id"]
    half = len(apk) // 2
    assert client.put(f"/api/admin/releases/uploads/{up}?offset=0", headers=h, content=apk[:half]).json()["size"] == half
    # A repeated piece is refused with the size the server has, so the page can carry on.
    again = client.put(f"/api/admin/releases/uploads/{up}?offset=0", headers=h, content=apk[:half])
    assert again.status_code == 409 and again.json()["size"] == half
    client.put(f"/api/admin/releases/uploads/{up}?offset={half}", headers=h, content=apk[half:])
    r = client.post("/api/admin/releases/android", headers=h,
                    data={"staged": json.dumps({"id": up, "name": "app-arm64-v8a-release.apk", "field": "apks"})})
    assert r.status_code == 200, r.text
    assert r.json()["apks"] == ["arm64-v8a"] and client.get("/get/android").content == apk
    assert not list((releases.root() / ".uploads").iterdir())  # pieces are cleaned up
    # Unknown or malformed upload ids are refused.
    assert client.put("/api/admin/releases/uploads/not-an-id", headers=h, content=b"x").status_code == 404
    bad = client.post("/api/admin/releases/files", headers=h, data={"staged": json.dumps({"id": "0" * 32, "field": "file"})})
    assert bad.status_code == 400
    assert client.put(f"/api/admin/releases/uploads/{'a' * 32}", content=b"x").status_code == 401
    client.delete("/api/admin/releases/android", headers=h)
