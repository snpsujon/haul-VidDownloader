"""Files the site hands out: the Android app, the browser extension, the PC helper, and any
other file the admin uploads. Kept in DATA_DIR/releases with a small JSON manifest.

The Android app checks <site>/app/version.json for updates (see scripts/package_release.py in
the app), so uploading APKs here also publishes that file.
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
import shutil
import threading
import time
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, RedirectResponse

from . import apkinfo, config

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


# Uploads in pieces ---------------------------------------------------------------------------
# Proxies in front of the server (nginx defaults to 1 MB, Cloudflare to 100 MB) refuse big
# requests, so the admin page sends each file in small pieces first, then publishes with
# references to them ("staged" form fields: {"id", "name", "field"} as JSON).

STAGE_TTL = 86400


def _stage_dir() -> Path:
    return root() / ".uploads"


def _stage_path(upload_id: str) -> Path:
    if not re.fullmatch(r"[0-9a-f]{32}", upload_id or ""):
        raise HTTPException(404, "Unknown upload.")
    return _stage_dir() / upload_id


class Staged:
    """Looks like an UploadFile to store(): a filename and an async read()."""

    def __init__(self, path: Path, filename: str):
        self.path, self.filename, self._f = path, filename, None

    async def read(self, n: int = -1) -> bytes:
        if self._f is None:
            self._f = self.path.open("rb")
        return self._f.read(n)

    def close(self) -> None:
        if self._f:
            self._f.close()
        self.path.unlink(missing_ok=True)


class StagedSet:
    def __init__(self, raw: list[str]):
        self.by_field: dict[str, list[Staged]] = {}
        for item in raw:
            try:
                d = json.loads(item)
                path = _stage_path(str(d["id"]))
            except (ValueError, KeyError, TypeError):
                raise HTTPException(400, "A piece of the upload went missing. Try again.")
            if not path.is_file():
                raise HTTPException(400, "A piece of the upload went missing. Try again.")
            self.by_field.setdefault(str(d.get("field") or "file"), []).append(Staged(path, str(d.get("name") or "file")))

    def all(self, field: str) -> list:
        return self.by_field.get(field, [])

    def one(self, field: str):
        return next(iter(self.all(field)), None)

    def close(self) -> None:
        for files in self.by_field.values():
            for f in files:
                f.close()


def _sweep_stages() -> None:
    d = _stage_dir()
    if d.is_dir():
        for f in d.iterdir():
            if f.stat().st_mtime < time.time() - STAGE_TTL:
                f.unlink(missing_ok=True)


# Admin -------------------------------------------------------------------------------------

def admin_router(require_admin) -> APIRouter:
    r = APIRouter(prefix="/api/admin/releases", tags=["admin"], dependencies=[Depends(require_admin)])

    @r.post("/uploads", summary="Start a file upload that's sent in pieces")
    async def start_upload():
        _sweep_stages()
        _stage_dir().mkdir(parents=True, exist_ok=True)
        upload_id = secrets.token_hex(16)
        _stage_path(upload_id).touch()
        return {"id": upload_id}

    @r.put("/uploads/{upload_id}", summary="Add the next piece (the raw bytes) at the given offset")
    async def add_piece(upload_id: str, request: Request, offset: int = 0):
        path = _stage_path(upload_id)
        if not path.is_file():
            raise HTTPException(404, "Unknown upload.")
        size = path.stat().st_size
        if offset != size:
            return JSONResponse({"detail": "Wrong offset.", "size": size}, status_code=409)
        limit = config.MAX_UPLOAD_MB * 1024 * 1024
        with path.open("ab") as out:
            async for chunk in request.stream():
                size += len(chunk)
                if size > limit:
                    out.truncate(offset)
                    raise HTTPException(400, f"That file is bigger than the upload limit ({config.MAX_UPLOAD_MB} MB).")
                out.write(chunk)
        return {"size": size}

    @r.get("", summary="Everything uploaded, with file details")
    async def list_all(request: Request):
        return {"public": public(base_url(request)), "max_upload_mb": config.MAX_UPLOAD_MB}

    @r.post("/android", summary="Publish a new Android app version")
    async def upload_android(
        apks: list[UploadFile] = File(default_factory=list),
        version_file: UploadFile | None = File(None),
        changelog: str = Form(""),
        force_update: bool = Form(False),
        version_name: str = Form("", max_length=40),
        version_code: int = Form(0, ge=0),
        min_supported: int | None = Form(None, ge=0),
        universal: UploadFile | None = File(None),
        arm64_v8a: UploadFile | None = File(None, alias="arm64-v8a"),
        armeabi_v7a: UploadFile | None = File(None, alias="armeabi-v7a"),
        x86_64: UploadFile | None = File(None),
        staged: list[str] = Form(default_factory=list),
    ):
        """Drop the APKs; the version and CPU type are read from each one. A version.json from
        scripts/package_release.py can come along for its changelog and minimum version."""
        st = StagedSet(staged)
        try:
            return await _publish_android(
                [f for f in [*apks, *st.all("apks"), universal, arm64_v8a, armeabi_v7a, x86_64] if _given(f)],
                version_file if _given(version_file) else st.one("version_file"),
                changelog, force_update, version_name, version_code, min_supported)
        finally:
            st.close()

    async def _publish_android(files, version_file, changelog, force_update, version_name, version_code, min_supported):
        if not files:
            raise HTTPException(400, "Pick the APK to publish (app-universal-release.apk works on every phone).")
        extra: dict = {}
        if _given(version_file):
            try:
                extra = json.loads((await version_file.read(1 << 20)).decode("utf-8-sig"))
                assert isinstance(extra, dict)
            except (ValueError, AssertionError):
                raise HTTPException(400, "That version.json couldn't be read.")
        staging = root() / "android" / f".incoming-{secrets.token_hex(4)}"
        found: dict[str, dict] = {}
        try:
            for i, f in enumerate(files):
                if not f.filename.lower().endswith(".apk"):
                    raise HTTPException(400, f"{f.filename} isn't an .apk file.")
                tmp = staging / f"{i}.apk"
                try:
                    stored = await store(f, tmp)
                    info = apkinfo.read(tmp)
                except (UploadError, apkinfo.NotAnApk) as e:
                    raise HTTPException(400, f"{f.filename}: {e}")
                if (info["package"] or "").endswith(".debug"):
                    raise HTTPException(400, f"{f.filename} is a debug build. Upload the release APK "
                                             "(app/build/outputs/apk/release).")
                if info["abi"] in found:
                    raise HTTPException(400, f"Two of these APKs are both for {info['abi']}. Pick one of each.")
                found[info["abi"]] = {**stored, **info, "tmp": tmp}
            names = {v["version_name"] for v in found.values()}
            if len(names) > 1:
                raise HTTPException(400, "These APKs are different versions (" + ", ".join(sorted(names)) + "). Upload one version at a time.")
            name = version_name.strip() or names.pop()
            code = version_code or max(v["version_code"] for v in found.values())
            if not re.fullmatch(r"[0-9A-Za-z.+_-]+", name):
                raise HTTPException(400, "The version inside that APK has unusual characters.")
            prev = load()["android"] or {}
            if prev.get("package") and found and next(iter(found.values()))["package"] not in (None, prev["package"]):
                raise HTTPException(400, "That APK is a different app from the one published.")
            if prev.get("versionCode") and code < prev["versionCode"]:
                raise HTTPException(400, f"This APK is version {name} ({code}), older than the published "
                                         f"{prev['versionName']} ({prev['versionCode']}). Phones only update to a newer version.")
            notes = [line.strip("-• \t") for line in changelog.splitlines() if line.strip()]
            if not notes and isinstance(extra.get("changelog"), list):
                notes = [str(x) for x in extra["changelog"]][:30]
            if force_update:
                minimum = code
            elif min_supported is not None:
                minimum = min_supported
            elif isinstance(extra.get("minSupported"), int):
                minimum = extra["minSupported"]
            else:
                minimum = 0
            apk_rows = {}
            for abi, v in found.items():
                final = f"haul-{name}-{abi}.apk"
                v["tmp"].replace(root() / "android" / final)
                apk_rows[abi] = {"file": final, "size": v["size"], "sha256": v["sha256"]}
        finally:
            shutil.rmtree(staging, ignore_errors=True)
        with _lock:
            d = load()
            # APKs for other CPUs from the same version stay published.
            old = d["android"] or {}
            if old.get("versionName") == name and old.get("versionCode") == code:
                apk_rows = {**old.get("apks", {}), **apk_rows}
            d["android"] = {
                "versionName": name, "versionCode": code, "minSupported": minimum,
                "changelog": notes or (old.get("changelog", []) if old.get("versionCode") == code else []),
                "apks": apk_rows, "package": next(iter(found.values()))["package"], "updated": time.time(),
            }
            _save(d)
        _remove_files(root() / "android", {v["file"] for v in apk_rows.values()})
        return {"ok": True, "version": name, "version_code": code, "apks": sorted(apk_rows), "min_supported": minimum}

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
                               store_url: str = Form("", max_length=500), file: UploadFile | None = File(None),
                               staged: list[str] = Form(default_factory=list)):
        st = StagedSet(staged)
        try:
            return await _upload_single("extension", version, notes, store_url,
                                        file if _given(file) else st.one("file"), (".zip", ".crx"))
        finally:
            st.close()

    @r.post("/helper", summary="Publish the PC helper (Windows .exe, plus macOS and Linux builds)")
    async def upload_helper(version: str = Form("", max_length=40), notes: str = Form("", max_length=2000),
                            file: UploadFile | None = File(None),
                            mac: UploadFile | None = File(None),
                            linux_x64: UploadFile | None = File(None, alias="linux-x64"),
                            linux_arm64: UploadFile | None = File(None, alias="linux-arm64"),
                            staged: list[str] = Form(default_factory=list)):
        st = StagedSet(staged)
        try:
            return await _publish_helper(version, notes, file if _given(file) else st.one("file"), {
                "mac": mac if _given(mac) else st.one("mac"),
                "linux-x64": linux_x64 if _given(linux_x64) else st.one("linux-x64"),
                "linux-arm64": linux_arm64 if _given(linux_arm64) else st.one("linux-arm64")})
        finally:
            st.close()

    async def _publish_helper(version, notes, file, builds):
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
    async def upload_file(file: UploadFile | None = File(None), title: str = Form("", max_length=120),
                          staged: list[str] = Form(default_factory=list)):
        st = StagedSet(staged)
        try:
            file = file if _given(file) else st.one("file")
            if not _given(file):
                raise HTTPException(400, "Pick a file to upload.")
            name = safe_name(file.filename)
            try:
                info = await store(file, root() / "files" / name)
            except UploadError as e:
                raise HTTPException(400, str(e))
        finally:
            st.close()
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
