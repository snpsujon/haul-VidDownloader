"""Tells which computer a PC helper file is for, and its version, from the file itself.

Windows programs start with "MZ", Mac ones with a Mach-O header, and Linux ones with ELF, whose
machine field says x64 or ARM64. The helper's version is in its user agent, "haul-helper/0.4.0".
"""
from __future__ import annotations

import re
from pathlib import Path

SYSTEMS = {"windows": "Windows", "mac": "Mac", "linux-x64": "Linux (x64)", "linux-arm64": "Linux (ARM64)"}
_MACHO = {b"\xca\xfe\xba\xbe", b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf", b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe"}
_VERSION = re.compile(rb"haul-helper/(\d+\.\d+\.\d+)")


def system(head: bytes, filename: str = "") -> str | None:
    if head[:2] == b"MZ" or filename.lower().endswith((".exe", ".msi")):
        return "windows"
    if head[:4] in _MACHO:
        return "mac"
    if head[:4] == b"\x7fELF" and len(head) >= 20:
        machine = int.from_bytes(head[18:20], "little" if head[5] == 1 else "big")
        return {0x3E: "linux-x64", 0xB7: "linux-arm64"}.get(machine)
    return None


def version(path: Path) -> str | None:
    """Scans the file in 4 MB steps, overlapping so a match split across steps is still found."""
    tail = b""
    with path.open("rb") as f:
        while chunk := f.read(4 << 20):
            m = _VERSION.search(tail + chunk)
            if m:
                return m.group(1).decode()
            tail = chunk[-64:]
    return None


def read(path: Path, filename: str = "") -> dict:
    with path.open("rb") as f:
        head = f.read(64)
    return {"system": system(head, filename), "version": version(path)}
