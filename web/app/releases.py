"""Files the site hands out: the Android app, the browser extension, the PC helper, and any
other file the admin uploads. Kept in DATA_DIR/releases with a small JSON manifest.

The Android app checks <site>/app/version.json for updates (see scripts/package_release.py in
the app), so uploading APKs here also publishes that file.
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import threading
import time
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, RedirectResponse

from . import config

STATIC = Path(__file__).resolve().parent.parent / "static"
ABIS = ("universal", "arm64-v8a", "armeabi-v7a", "x86_64")
# Mac and Linux builds of the PC helper, fetched by install.sh. The Windows .exe is helper["file"].
HELPER_BUILDS = ("mac", "linux-x64", "linux-arm64")
_lock = threading.Lock()


class UploadError(ValueError):
    pass


def root() -> Path:
    return config.DATA_DIR / "releases"


def _manifest_path() -> Path:
    return root() / "releases.json"


def load() -> dict:
    try:
        data = json.loads(_manifest_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    data.setdefault("android", None)
    data.setdefault("extension", None)
    data.setdefault("helper", None)
    data.setdefault("files", [])
    return data


def _save(data: dict) -> None:
    root().mkdir(parents=True, exist_ok=True)
    tmp = _manifest_path().with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    tmp.replace(_manifest_path())


def safe_name(name: str) -> str:
    name = Path(name or "file").name
    name = re.sub(r"[^A-Za-z0-9._ -]+", "_", name).strip(" .")
    return name[:120] or "file"


async def store(upload: UploadFile, dest: Path) -> dict:
    """Streams an upload to dest, hashing it on the way; refuses files over the limit."""
    limit = config.MAX_UPLOAD_MB * 1024 * 1024
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    h = hashlib.sha256()
    size = 0
    try:
        with tmp.open("wb") as out:
            while chunk := await upload.read(1 << 20):
                size += len(chunk)
                if size > limit:
                    raise UploadError(f"That file is bigger than the upload limit ({config.MAX_UPLOAD_MB} MB).")
                h.update(chunk)
                out.write(chunk)
        if size == 0:
            raise UploadError("That file is empty.")
        tmp.replace(dest)
    finally:
        tmp.unlink(missing_ok=True)
    return {"file": dest.name, "size": size, "sha256": h.hexdigest()}


def _given(f: UploadFile | None) -> bool:
    return f is not None and bool(f.filename)


def _remove_files(slot_dir: Path, keep: set[str]) -> None:
    if slot_dir.is_dir():
        for f in slot_dir.iterdir():
            if f.is_file() and f.name not in keep:
                f.unlink(missing_ok=True)


# Public view -------------------------------------------------------------------------------

def public(base: str = "") -> dict:
    d = load()
    out: dict = {"android": None, "extension": None, "helper": None, "files": []}
    a = d["android"]
    if a and a.get("apks"):
        out["android"] = {
            "version": a["versionName"], "version_code": a["versionCode"], "changelog": a.get("changelog", []),
            "updated": a.get("updated"), "url": base + "/get/android",
            "apks": {abi: {"url": f"{base}/app/{v['file']}", "size": v["size"], "sha256": v["sha256"]}
                     for abi, v in a["apks"].items()},
        }
    for slot in ("extension", "helper"):
        r = d[slot]
        if r and (r.get("file") or r.get("store_url") or r.get("builds")):
            out[slot] = {
                "version": r.get("version"), "notes": r.get("notes"), "updated": r.get("updated"),
                "size": r.get("size"), "sha256": r.get("sha256"),
                "url": base + f"/get/{slot}" if r.get("file") else None,
                "store_url": r.get("store_url"),
            }
            if slot == "helper":
                builds = {"windows": r} if r.get("file") else {}
                builds |= r.get("builds") or {}
                out[slot]["builds"] = {
                    k: {"url": base + ("/get/helper" if k == "windows" else f"/get/helper/{k}"),
                        "size": v["size"], "sha256": v["sha256"]} for k, v in builds.items()}
    out["files"] = [{"name": f["file"], "title": f.get("title") or f["file"], "size": f["size"],
                     "sha256": f["sha256"], "updated": f.get("updated"), "url": f"{base}/files/{f['file']}"}
                    for f in d["files"]]
    return out


def version_json(base: str) -> dict:
    """The update file the Android app reads, in package_release.py's format."""
    a = load()["android"]
    if not a or not a.get("apks"):
        raise HTTPException(404, "No app has been uploaded yet.")
    return {
        "versionCode": a["versionCode"],
        "versionName": a["versionName"],
        "minSupported": a.get("minSupported", 0),
        "changelog": a.get("changelog", []),
        "apks": {abi: {"url": f"{base}/app/{v['file']}", "sha256": v["sha256"], "size": v["size"]}
                 for abi, v in a["apks"].items()},
    }


def base_url(request: Request) -> str:
    return (config.PUBLIC_URL or str(request.base_url)).rstrip("/")


public_router = APIRouter()


@public_router.get("/api/releases", tags=["downloads"], summary="The app, extension, PC helper and other files on offer")
async def releases(request: Request):
    # Open to any origin (the extension's settings page reads it to offer helper updates).
    return JSONResponse(public(base_url(request)), headers={"Access-Control-Allow-Origin": "*"})


@public_router.get("/app/version.json", tags=["downloads"], summary="Update info for the Android app")
async def app_version(request: Request):
    return version_json(base_url(request))


def _send(path: Path, name: str | None = None):
    if not path.is_file():
        raise HTTPException(404, "That file isn't available.")
    media = "application/vnd.android.package-archive" if path.suffix == ".apk" else "application/octet-stream"
    return FileResponse(path, filename=name or path.name, media_type=media)


@public_router.get("/app/{name}", include_in_schema=False)
async def app_file(name: str):
    a = load()["android"] or {}
    files = {v["file"] for v in (a.get("apks") or {}).values()}
    if name not in files:
        raise HTTPException(404, "That file isn't available.")
    return _send(root() / "android" / name)


@public_router.get("/get/{slot}", tags=["downloads"], summary="The latest Android app, extension or PC helper")
async def get_latest(slot: str):
    d = load()
    if slot == "android":
        apks = (d["android"] or {}).get("apks") or {}
        pick = apks.get("universal") or next(iter(apks.values()), None)
        if not pick:
            raise HTTPException(404, "The app hasn't been uploaded yet.")
        return _send(root() / "android" / pick["file"])
    if slot in ("extension", "helper"):
        r = d[slot] or {}
        if r.get("file"):
            return _send(root() / slot / r["file"])
        if r.get("store_url"):
            return RedirectResponse(r["store_url"])
        raise HTTPException(404, "That hasn't been uploaded yet.")
    raise HTTPException(404, "Unknown download.")


@public_router.get("/get/helper/{build}", tags=["downloads"], summary="The PC helper for macOS or Linux (raw binary)")
async def get_helper_build(build: str):
    b = ((load()["helper"] or {}).get("builds") or {}).get(build)
    if build not in HELPER_BUILDS or not b:
        raise HTTPException(404, "That helper build hasn't been uploaded yet.")
    return _send(root() / "helper" / b["file"])


@public_router.get("/install.sh", include_in_schema=False)
async def install_sh(request: Request):
    """The macOS/Linux helper installer: curl -fsSL <site>/install.sh | sh"""
    text = (STATIC / "install.sh").read_text(encoding="utf-8").replace("\r\n", "\n")
    text = text.replace('SITE="${HAUL_SITE:-https://stackforge-pro.xyz}"', f'SITE="${{HAUL_SITE:-{base_url(request)}}}"', 1)
    return PlainTextResponse(text, headers={"Cache-Control": "no-cache"})


@public_router.get("/files/{name}", include_in_schema=False)
async def other_file(name: str):
    if name not in {f["file"] for f in load()["files"]}:
        raise HTTPException(404, "That file isn't available.")
    return _send(root() / "files" / name)


# Admin -------------------------------------------------------------------------------------

def admin_router(require_admin) -> APIRouter:
    r = APIRouter(prefix="/api/admin/releases", tags=["admin"], dependencies=[Depends(require_admin)])

    @r.get("", summary="Everything uploaded, with file details")
    async def list_all(request: Request):
        return {"public": public(base_url(request)), "max_upload_mb": config.MAX_UPLOAD_MB}

    @r.post("/android", summary="Publish a new Android app version")
    async def upload_android(
        version_name: str = Form(..., max_length=40),
        version_code: int = Form(..., ge=1),
        min_supported: int = Form(0, ge=0),
        changelog: str = Form(""),
        universal: UploadFile | None = File(None),
        arm64_v8a: UploadFile | None = File(None, alias="arm64-v8a"),
        armeabi_v7a: UploadFile | None = File(None, alias="armeabi-v7a"),
        x86_64: UploadFile | None = File(None),
    ):
        files = {"universal": universal, "arm64-v8a": arm64_v8a, "armeabi-v7a": armeabi_v7a, "x86_64": x86_64}
        files = {abi: f for abi, f in files.items() if _given(f)}
        if not files:
            raise HTTPException(400, "Pick at least one APK (the universal one works on every phone).")
        for f in files.values():
            if not f.filename.lower().endswith(".apk"):
                raise HTTPException(400, f"{f.filename} isn't an .apk file.")
        if not re.fullmatch(r"[0-9A-Za-z.+_-]+", version_name):
            raise HTTPException(400, "Write the version like 1.3.0.")
        prev = load()["android"] or {}
        if prev.get("versionCode") and version_code < prev["versionCode"]:
            raise HTTPException(400, f"The version code has to be at least {prev['versionCode']} (the current one), or phones won't update.")
        apks = {}
        try:
            for abi, f in files.items():
                name = f"haul-{version_name}-{abi}.apk"
                apks[abi] = await store(f, root() / "android" / name)
        except UploadError as e:
            raise HTTPException(400, str(e))
        with _lock:
            d = load()
            d["android"] = {
                "versionName": version_name, "versionCode": version_code, "minSupported": min_supported,
                "changelog": [line.strip("-• \t") for line in changelog.splitlines() if line.strip()],
                "apks": apks, "updated": time.time(),
            }
            _save(d)
        _remove_files(root() / "android", {v["file"] for v in apks.values()})
        return {"ok": True}

    async def _upload_single(slot: str, version: str, notes: str, store_url: str | None,
                             file: UploadFile | None, exts: tuple[str, ...], builds: dict | None = None):
        prev = load()[slot] or {}
        entry = {"version": version or prev.get("version"), "notes": notes, "updated": time.time(),
                 "store_url": (store_url or "").strip() or None}
        if entry["store_url"] and not entry["store_url"].startswith("https://"):
            raise HTTPException(400, "The store link has to start with https://")
        if _given(file):
            if not file.filename.lower().endswith(exts):
                raise HTTPException(400, f"Upload a {' or '.join(exts)} file.")
            name = safe_name(file.filename)
            try:
                entry.update(await store(file, root() / slot / name))
            except UploadError as e:
                raise HTTPException(400, str(e))
        elif prev.get("file"):
            entry.update({k: prev[k] for k in ("file", "size", "sha256")})
        if builds is not None:
            # Builds not picked this time are kept from the last upload.
            entry["builds"] = dict(prev.get("builds") or {})
            for name, f in builds.items():
                if _given(f):
                    try:
                        entry["builds"][name] = await store(f, root() / slot / f"haul-helper-{name}")
                    except UploadError as e:
                        raise HTTPException(400, str(e))
        if not entry.get("file") and not entry["store_url"] and not entry.get("builds"):
            raise HTTPException(400, "Upload a file" + (" or add a store link." if slot == "extension" else "."))
        with _lock:
            d = load()
            d[slot] = entry
            _save(d)
        _remove_files(root() / slot, {entry.get("file")} | {b["file"] for b in (entry.get("builds") or {}).values()})
        return {"ok": True}

    @r.post("/extension", summary="Publish the browser extension (a .zip, a store link, or both)")
    async def upload_extension(version: str = Form("", max_length=40), notes: str = Form("", max_length=2000),
                               store_url: str = Form("", max_length=500), file: UploadFile | None = File(None)):
        return await _upload_single("extension", version, notes, store_url, file, (".zip", ".crx"))

    @r.post("/helper", summary="Publish the PC helper (Windows .exe, plus macOS and Linux builds)")
    async def upload_helper(version: str = Form("", max_length=40), notes: str = Form("", max_length=2000),
                            file: UploadFile | None = File(None),
                            mac: UploadFile | None = File(None),
                            linux_x64: UploadFile | None = File(None, alias="linux-x64"),
                            linux_arm64: UploadFile | None = File(None, alias="linux-arm64")):
        builds = {"mac": mac, "linux-x64": linux_x64, "linux-arm64": linux_arm64}
        for f in builds.values():
            if _given(f) and f.filename.lower().endswith((".exe", ".msi", ".zip", ".dmg", ".html")):
                raise HTTPException(400, f"{f.filename} doesn't look like a Mac or Linux helper. Upload haul-helper-mac or haul-helper-linux-….")
        return await _upload_single("helper", version, notes, None, file, (".exe", ".zip", ".msi"), builds)

    @r.delete("/{slot}", summary="Stop offering the app, extension or helper")
    async def remove_slot(slot: str):
        if slot not in ("android", "extension", "helper"):
            raise HTTPException(404, "Unknown download.")
        with _lock:
            d = load()
            d[slot] = None
            _save(d)
        shutil.rmtree(root() / slot, ignore_errors=True)
        return {"ok": True}

    @r.post("/files", summary="Upload another file to offer on the page")
    async def upload_file(file: UploadFile = File(...), title: str = Form("", max_length=120)):
        name = safe_name(file.filename)
        try:
            info = await store(file, root() / "files" / name)
        except UploadError as e:
            raise HTTPException(400, str(e))
        with _lock:
            d = load()
            d["files"] = [f for f in d["files"] if f["file"] != name]
            d["files"].append({**info, "title": title.strip() or None, "updated": time.time()})
            _save(d)
        return {"ok": True, "name": name}

    @r.delete("/files/{name}", summary="Remove an uploaded file")
    async def delete_file(name: str):
        with _lock:
            d = load()
            if name not in {f["file"] for f in d["files"]}:
                raise HTTPException(404, "No such file.")
            d["files"] = [f for f in d["files"] if f["file"] != name]
            _save(d)
        (root() / "files" / safe_name(name)).unlink(missing_ok=True)
        return {"ok": True}

    return r
