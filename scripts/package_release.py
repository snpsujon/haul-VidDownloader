#!/usr/bin/env python3
"""Renames the release APKs, hashes them, and writes the version.json the app checks.

Output (upload the app/ folder to your website as-is):

    dist/app/haul-1.3.0-arm64-v8a.apk
    dist/app/haul-1.3.0-armeabi-v7a.apk
    dist/app/haul-1.3.0-x86_64.apk
    dist/app/haul-1.3.0-universal.apk
    dist/app/version.json
    dist/haul-website-upload.zip
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import sys
import zipfile

ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64", "universal"]


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--apks", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--site", required=True)
    p.add_argument("--version-name", required=True)
    p.add_argument("--version-code", required=True, type=int)
    p.add_argument("--min-supported", default="0")
    p.add_argument("--changelog", default="")
    a = p.parse_args()

    src = pathlib.Path(a.apks)
    out = pathlib.Path(a.out)
    app_dir = out / "app"
    if out.exists():
        shutil.rmtree(out)
    app_dir.mkdir(parents=True)

    site = a.site.rstrip("/")
    apks = {}
    for abi in ABIS:
        found = sorted(src.glob(f"*-{abi}-release*.apk"))
        if not found:
            print(f"warning: no APK for {abi}", file=sys.stderr)
            continue
        name = f"haul-{a.version_name}-{abi}.apk"
        dest = app_dir / name
        shutil.copy2(found[0], dest)
        apks[abi] = {"url": f"{site}/app/{name}", "sha256": sha256(dest), "size": dest.stat().st_size}

    if "universal" not in apks:
        print("error: the universal APK is missing", file=sys.stderr)
        return 1

    changelog = []
    cl = pathlib.Path(a.changelog) if a.changelog else None
    if cl and cl.exists():
        changelog = [line.strip().lstrip("-• ").strip() for line in cl.read_text().splitlines() if line.strip()]

    version = {
        "versionCode": a.version_code,
        "versionName": a.version_name,
        "minSupported": int(a.min_supported or 0),
        "changelog": changelog,
        "apks": apks,
    }
    (app_dir / "version.json").write_text(json.dumps(version, indent=2) + "\n")

    with zipfile.ZipFile(out / "haul-website-upload.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(app_dir.iterdir()):
            z.write(f, f"app/{f.name}")

    print(json.dumps(version, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
