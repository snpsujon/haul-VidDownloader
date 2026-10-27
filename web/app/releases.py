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

from fastapi import APIRouter, Body, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, RedirectResponse

from . import apkinfo, config, extinfo, helperinfo, store as log_store

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
            "updated": a.get("updated"), "url": base + "/get/android", "min_supported": a.get("minSupported", 0),
            "apks": {abi: {"url": f"{base}/app/{v['file']}", "size": v["size"], "sha256": v["sha256"]}
                     for abi, v in a["apks"].items()},
        }
    for slot in ("extension", "helper"):
        r = d[slot]
        if r and (r.get("file") or r.get("store_url") or r.get("builds") or r.get("stores")):
            out[slot] = {
                "version": r.get("version"), "notes": r.get("notes"), "updated": r.get("updated"),
                "size": r.get("size"), "sha256": r.get("sha256"),
                "url": base + f"/get/{slot}" if r.get("file") else None,
                "store_url": r.get("store_url"),
            }
            if slot == "extension":
                stores = dict(r.get("stores") or {})
                if r.get("store_url") and not stores:
                    stores["chrome"] = r["store_url"]
                builds = {"chromium": {**r, "version": r.get("file_version")}} if r.get("file") else {}
                builds |= r.get("builds") or {}
                out[slot]["stores"] = stores
                # Links through the site, so clicks show on the Stats page.
                out[slot]["store_links"] = {k: f"{base}/get/extension/store/{k}" for k in stores}
                out[slot]["store_url"] = stores.get("chrome") or stores.get("edge") or next(iter(stores.values()), None)
                out[slot]["builds"] = {
                    k: {"url": base + ("/get/extension" if k == "chromium" else f"/get/extension/{k}"),
                        "size": v["size"], "sha256": v["sha256"], "version": v.get("version") or r.get("version")}
                    for k, v in builds.items()}
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


def _count(request: Request, kind: str, detail: str | None = None) -> None:
    """Counts a download for the Stats page. Resumed pieces of the same download don't count."""
    rng = request.headers.get("range", "")
    if rng and not rng.replace(" ", "").startswith("bytes=0-"):
        return
    from .main import client_country, client_ip
    log_store.log_install(kind, detail, client_ip(request), client_country(request))


@public_router.get("/app/{name}", include_in_schema=False)
async def app_file(name: str, request: Request):
    a = load()["android"] or {}
    files = {v["file"] for v in (a.get("apks") or {}).values()}
    if name not in files:
        raise HTTPException(404, "That file isn't available.")
    _count(request, "android", next((abi for abi, v in a["apks"].items() if v["file"] == name), None))
    return _send(root() / "android" / name)


@public_router.get("/get/{slot}", tags=["downloads"], summary="The latest Android app, extension or PC helper")
async def get_latest(slot: str, request: Request):
    d = load()
    if slot == "android":
        apks = (d["android"] or {}).get("apks") or {}
        pick = apks.get("universal") or next(iter(apks.values()), None)
        if not pick:
            raise HTTPException(404, "The app hasn't been uploaded yet.")
        _count(request, "android", "universal" if "universal" in apks else next(iter(apks)))
        return _send(root() / "android" / pick["file"])
    if slot in ("extension", "helper"):
        r = d[slot] or {}
        if r.get("file"):
            _count(request, slot, "zip" if slot == "extension" else "windows")
            return _send(root() / slot / r["file"])
        if r.get("store_url"):
            _count(request, slot, "store")
            return RedirectResponse(r["store_url"])
        raise HTTPException(404, "That hasn't been uploaded yet.")
    raise HTTPException(404, "Unknown download.")


@public_router.get("/get/extension/firefox", tags=["downloads"], summary="The Firefox build of the extension (.xpi)")
async def get_extension_firefox(request: Request):
    b = ((load()["extension"] or {}).get("builds") or {}).get("firefox")
    if not b:
        raise HTTPException(404, "The Firefox extension hasn't been uploaded yet.")
    _count(request, "extension", "firefox-file")
    return _send(root() / "extension" / b["file"])


@public_router.get("/get/extension/store/{kind}", tags=["downloads"], summary="Go to the extension's page on a browser store (counted)")
async def go_extension_store(kind: str, request: Request):
    x = load()["extension"] or {}
    url = (x.get("stores") or {}).get(kind) or (x.get("store_url") if kind == "chrome" else None)
    if kind not in extinfo.STORES or not url:
        raise HTTPException(404, "The extension isn't on that store.")
    _count(request, "extension", kind)
    return RedirectResponse(url)


@public_router.get("/get/helper/{build}", tags=["downloads"], summary="The PC helper for macOS or Linux (raw binary)")
async def get_helper_build(build: str, request: Request):
    b = ((load()["helper"] or {}).get("builds") or {}).get(build)
    if build not in HELPER_BUILDS or not b:
        raise HTTPException(404, "That helper build hasn't been uploaded yet.")
    _count(request, "helper", build)
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

    @r.post("/extension", summary="Publish the browser extension: store links per browser, and/or the package files")
    async def upload_extension(version: str = Form("", max_length=40), notes: str = Form("", max_length=2000),
                               store_chrome: str | None = Form(None, max_length=500),
                               store_edge: str | None = Form(None, max_length=500),
                               store_firefox: str | None = Form(None, max_length=500),
                               store_opera: str | None = Form(None, max_length=500),
                               store_url: str = Form("", max_length=500),
                               all_stores: bool = Form(False),
                               packages: list[UploadFile] = File(default_factory=list),
                               file: UploadFile | None = File(None),
                               staged: list[str] = Form(default_factory=list)):
        st = StagedSet(staged)
        try:
            picked = [f for f in [*packages, *st.all("packages"), file if _given(file) else st.one("file")] if _given(f)]
            links = {"chrome": store_chrome, "edge": store_edge, "firefox": store_firefox, "opera": store_opera}
            if all_stores:
                # The admin form sends every box; an empty one removes that link. (Form turns
                # an empty box into None, so without this flag None means "not sent, keep it".)
                links = {k: v or "" for k, v in links.items()}
            if store_url.strip() and links["chrome"] is None:
                links["chrome"] = store_url  # the old form's single link
            return await _publish_extension(version.strip(), notes.strip(), links, picked)
        finally:
            st.close()

    async def _publish_extension(version: str, notes: str, links: dict, picked: list):
        prev = load()["extension"] or {}
        stores = dict(prev.get("stores") or ({"chrome": prev["store_url"]} if prev.get("store_url") else {}))
        for kind, url in links.items():
            if url is None:
                continue  # not on the form: keep what's there
            url = url.strip()
            if not url:
                stores.pop(kind, None)
                continue
            err = extinfo.store_error(kind, url)
            if err:
                raise HTTPException(400, err)
            stores[kind] = url
        staging = root() / "extension" / f".incoming-{secrets.token_hex(4)}"
        found: dict[str, dict] = {}
        try:
            for i, f in enumerate(picked):
                if not f.filename.lower().endswith((".zip", ".crx", ".xpi")):
                    raise HTTPException(400, f"{f.filename}: pick the extension's .zip, .crx or .xpi file.")
                tmp = staging / str(i)
                try:
                    stored = await store(f, tmp)
                    info = extinfo.read(tmp, f.filename)
                except (UploadError, extinfo.NotAnExtension) as e:
                    raise HTTPException(400, str(e))
                if info["build"] in found:
                    raise HTTPException(400, f"Two of these files are both for {extinfo.BUILDS[info['build']]}. Pick one of each.")
                found[info["build"]] = {**stored, "version": info["version"], "tmp": tmp, "name": f.filename}
            entry = {"version": version or next((v["version"] for v in found.values() if v["version"]), None) or prev.get("version"),
                     "notes": notes, "updated": time.time(), "stores": stores,
                     "store_url": stores.get("chrome") or stores.get("edge") or next(iter(stores.values()), None),
                     "builds": dict(prev.get("builds") or {})}
            if prev.get("file"):
                entry.update({k: prev[k] for k in ("file", "size", "sha256")})
                entry["file_version"] = prev.get("file_version") or prev.get("version")
            for build, v in found.items():
                ext = Path(v["name"]).suffix.lower()
                name = f"haul-extension-{v['version'] or 'latest'}{'-firefox' if build == 'firefox' and ext != '.xpi' else ''}{ext}"
                name = safe_name(name)
                v["tmp"].replace(root() / "extension" / name)
                row = {"file": name, "size": v["size"], "sha256": v["sha256"], "version": v["version"]}
                if build == "chromium":
                    entry.update({k: row[k] for k in ("file", "size", "sha256")})
                    entry["file_version"] = v["version"]
                else:
                    entry["builds"][build] = row
        finally:
            shutil.rmtree(staging, ignore_errors=True)
        if not entry.get("file") and not entry["builds"] and not stores:
            raise HTTPException(400, "Add a store link or drop the extension's .zip.")
        with _lock:
            d = load()
            d["extension"] = entry
            _save(d)
        _remove_files(root() / "extension", {entry.get("file")} | {b["file"] for b in entry["builds"].values()})
        return {"ok": True, "version": entry["version"], "packages": [{"chromium": "Chrome .zip", "firefox": "Firefox file"}[b] + (f" {v['version']}" if v["version"] else "") for b, v in found.items()],
                "stores": [extinfo.STORES[k][0] for k in ("chrome", "edge", "firefox", "opera") if k in stores]}

    @r.delete("/extension/{kind}/{part}", summary="Remove one store link (store/chrome…) or package (package/chromium, package/firefox)")
    async def remove_extension_part(kind: str, part: str):
        with _lock:
            d = load()
            x = d["extension"] or {}
            if kind == "store" and part in extinfo.STORES:
                (x.get("stores") or {}).pop(part, None)
                if part == "chrome" and not x.get("stores"):
                    x["store_url"] = None
            elif kind == "package" and part == "chromium":
                for k in ("file", "size", "sha256", "file_version"):
                    x.pop(k, None)
            elif kind == "package" and part == "firefox":
                (x.get("builds") or {}).pop("firefox", None)
            else:
                raise HTTPException(404, "Unknown part.")
            if x:
                s_ = x.get("stores") or {}
                x["store_url"] = s_.get("chrome") or s_.get("edge") or next(iter(s_.values()), None)
            d["extension"] = x if (x.get("file") or x.get("builds") or x.get("stores") or x.get("store_url")) else None
            _save(d)
        x = d["extension"] or {}
        _remove_files(root() / "extension", {x.get("file")} | {b["file"] for b in (x.get("builds") or {}).values()})
        return {"ok": True}

    @r.post("/helper", summary="Publish the PC helper: drop the files, each one's system and version are read from it")
    async def upload_helper(version: str = Form("", max_length=40), notes: str = Form("", max_length=2000),
                            helpers: list[UploadFile] = File(default_factory=list),
                            file: UploadFile | None = File(None),
                            mac: UploadFile | None = File(None),
                            linux_x64: UploadFile | None = File(None, alias="linux-x64"),
                            linux_arm64: UploadFile | None = File(None, alias="linux-arm64"),
                            staged: list[str] = Form(default_factory=list)):
        st = StagedSet(staged)
        try:
            picked = [(None, f) for f in [*helpers, *st.all("helpers")]]
            for slot, f in (("windows", file if _given(file) else st.one("file")),
                            ("mac", mac if _given(mac) else st.one("mac")),
                            ("linux-x64", linux_x64 if _given(linux_x64) else st.one("linux-x64")),
                            ("linux-arm64", linux_arm64 if _given(linux_arm64) else st.one("linux-arm64"))):
                picked.append((slot, f))
            return await _publish_helper(version.strip(), notes, [(s, f) for s, f in picked if _given(f)])
        finally:
            st.close()

    async def _publish_helper(version: str, notes: str, picked: list):
        prev = load()["helper"] or {}
        if not picked and not prev:
            raise HTTPException(400, "Pick the helper files to publish.")
        staging = root() / "helper" / f".incoming-{secrets.token_hex(4)}"
        found: dict[str, dict] = {}
        try:
            for i, (declared, f) in enumerate(picked):
                tmp = staging / str(i)
                try:
                    stored = await store(f, tmp)
                except UploadError as e:
                    raise HTTPException(400, f"{f.filename}: {e}")
                info = helperinfo.read(tmp, f.filename)
                system = info["system"]
                if system is None:
                    raise HTTPException(400, f"{f.filename} isn't a Windows, Mac or Linux program. Pick the files from the helper folder.")
                if declared and declared != system:
                    raise HTTPException(400, f"{f.filename} is for {helperinfo.SYSTEMS[system]}, not {helperinfo.SYSTEMS[declared]}.")
                if system in found:
                    raise HTTPException(400, f"Two of these files are for {helperinfo.SYSTEMS[system]}. Pick one of each.")
                found[system] = {**stored, "version": info["version"], "tmp": tmp, "name": f.filename}
            seen = {v["version"] for v in found.values() if v["version"]}
            if not version and len(seen) > 1:
                raise HTTPException(400, "These files are different helper versions (" + ", ".join(sorted(seen)) + "). Upload one version at a time.")
            version = version or (seen.pop() if seen else None) or prev.get("version")
            entry = {"version": version, "notes": notes.strip(), "updated": time.time(), "store_url": None,
                     "builds": dict(prev.get("builds") or {})}
            if prev.get("file"):
                entry.update({k: prev[k] for k in ("file", "size", "sha256")})
            for system, v in found.items():
                if system == "windows":
                    name = safe_name(v["name"])
                    if not name.lower().endswith((".exe", ".msi", ".zip")):
                        name = "haul-helper.exe"
                    v["tmp"].replace(root() / "helper" / name)
                    entry.update({"file": name, "size": v["size"], "sha256": v["sha256"]})
                else:
                    name = f"haul-helper-{system}"
                    v["tmp"].replace(root() / "helper" / name)
                    entry["builds"][system] = {"file": name, "size": v["size"], "sha256": v["sha256"]}
        finally:
            shutil.rmtree(staging, ignore_errors=True)
        with _lock:
            d = load()
            d["helper"] = entry
            _save(d)
        _remove_files(root() / "helper", {entry.get("file")} | {b["file"] for b in entry["builds"].values()})
        return {"ok": True, "version": version,
                "systems": [helperinfo.SYSTEMS[s] for s in ("windows", "mac", "linux-x64", "linux-arm64") if s in found]}

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

    @r.post("/files", summary="Upload one or more files to offer on the page (titles[] in the same order)")
    async def upload_file(file: UploadFile | None = File(None), title: str = Form("", max_length=120),
                          files: list[UploadFile] = File(default_factory=list),
                          titles: list[str] = Form(default_factory=list),
                          staged: list[str] = Form(default_factory=list)):
        st = StagedSet(staged)
        try:
            picked = [(f, title) for f in [file if _given(file) else st.one("file")] if _given(f)]
            picked += [(f, titles[i] if i < len(titles) else "") for i, f in enumerate([*files, *st.all("files")]) if _given(f)]
            if not picked:
                raise HTTPException(400, "Pick a file to upload.")
            rows = []
            for f, t in picked:
                name = safe_name(f.filename)
                try:
                    info = await store(f, root() / "files" / name)
                except UploadError as e:
                    raise HTTPException(400, f"{f.filename}: {e}")
                rows.append({**info, "title": t.strip()[:120] or None, "updated": time.time()})
        finally:
            st.close()
        with _lock:
            d = load()
            names = {r["file"] for r in rows}
            d["files"] = [f for f in d["files"] if f["file"] not in names] + rows
            _save(d)
        return {"ok": True, "name": rows[0]["file"], "names": [r["file"] for r in rows]}

    @r.patch("/files/{name}", summary="Change the title shown for a file")
    async def retitle_file(name: str, body: dict = Body(...)):
        title = str(body.get("title") or "")[:120]
        with _lock:
            d = load()
            row = next((f for f in d["files"] if f["file"] == name), None)
            if row is None:
                raise HTTPException(404, "No such file.")
            row["title"] = title.strip() or None
            _save(d)
        return {"ok": True}

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
