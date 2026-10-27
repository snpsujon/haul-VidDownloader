"""Reads a browser extension package: which browser it's for and its version.

A .zip, .crx or .xpi holds manifest.json. Firefox builds say so with browser_specific_settings
(or the older applications) "gecko" key, and .xpi files are always Firefox. Everything else is
a Chromium build, which Chrome, Edge, Brave and Opera all load.
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

BUILDS = {"chromium": "Chrome, Edge, Brave, Opera", "firefox": "Firefox"}
# Where each browser's store lives, to catch a link pasted into the wrong box.
STORES = {
    "chrome": ("Chrome Web Store", ("chromewebstore.google.com", "chrome.google.com")),
    "edge": ("Edge Add-ons", ("microsoftedge.microsoft.com",)),
    "firefox": ("Firefox Add-ons", ("addons.mozilla.org",)),
    "opera": ("Opera Add-ons", ("addons.opera.com",)),
}


class NotAnExtension(ValueError):
    pass


def read(path: Path, filename: str) -> dict:
    """{"build": "chromium" | "firefox", "version", "name"} for the package at path."""
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            # Some zips wrap everything in one folder; take the shallowest manifest.json.
            found = sorted((n for n in names if n.rsplit("/", 1)[-1] == "manifest.json"), key=lambda n: n.count("/"))
            if not found:
                raise NotAnExtension(f"{filename} has no manifest.json, so it isn't a browser extension.")
            m = json.loads(z.read(found[0]).decode("utf-8-sig"))
    except zipfile.BadZipFile:
        raise NotAnExtension(f"{filename} isn't a .zip, .crx or .xpi extension file.")
    except (ValueError, UnicodeDecodeError):
        raise NotAnExtension(f"The manifest.json in {filename} couldn't be read.")
    if not isinstance(m, dict):
        raise NotAnExtension(f"The manifest.json in {filename} couldn't be read.")
    gecko = (m.get("browser_specific_settings") or m.get("applications") or {})
    firefox = filename.lower().endswith(".xpi") or (isinstance(gecko, dict) and "gecko" in gecko)
    version = m.get("version")
    return {"build": "firefox" if firefox else "chromium",
            "version": str(version) if version else None,
            "name": m.get("name") if isinstance(m.get("name"), str) else None}


def store_error(kind: str, url: str) -> str | None:
    """Why url isn't a link to kind's store, or None if it looks right."""
    label, hosts = STORES[kind]
    if not url.startswith("https://"):
        return f"The {label} link has to start with https://"
    host = url[8:].split("/", 1)[0].split(":", 1)[0].lower()
    if not any(host == h or host.endswith("." + h) for h in hosts):
        return f"The {label} box needs a link from {hosts[0]}."
    return None
