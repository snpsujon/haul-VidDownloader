"""Reads the version and CPU type out of an APK, so publishing the app needs no typing.

An APK is a zip. Its AndroidManifest.xml is in Android's binary XML format; the <manifest>
element carries android:versionCode and android:versionName. The lib/<abi>/ folders say which
CPUs it runs on: one folder means a per-CPU build, several (or none) means universal.
"""
from __future__ import annotations

import struct
import zipfile
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
_VERSION_CODE = 0x0101021B
_VERSION_NAME = 0x0101021C

_STRING_POOL, _XML, _RESOURCE_MAP, _START_ELEMENT = 0x0001, 0x0003, 0x0180, 0x0102
_TYPE_STRING, _TYPE_INT_DEC, _TYPE_INT_HEX = 0x03, 0x10, 0x11


class NotAnApk(ValueError):
    pass


def _strings(buf: bytes, at: int) -> list[str]:
    _, header_size, _, count, _, flags, start, _ = struct.unpack_from("<HHIIIIII", buf, at)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{count}I", buf, at + header_size)
    base = at + start
    out = []
    for off in offsets:
        p = base + off
        if utf8:
            # Two lengths (UTF-16 units, then bytes), each one or two bytes long.
            p += 2 if buf[p] & 0x80 else 1
            n = buf[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | buf[p + 1]
                p += 1
            p += 1
            out.append(buf[p:p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]
                p += 2
            out.append(buf[p:p + n * 2].decode("utf-16-le", "replace"))
    return out


def manifest_versions(axml: bytes) -> tuple[int | None, str | None, str | None]:
    """(versionCode, versionName, package) from a binary AndroidManifest.xml."""
    if len(axml) < 8 or struct.unpack_from("<H", axml, 0)[0] != _XML:
        raise NotAnApk("The manifest isn't in Android's binary format.")
    strings: list[str] = []
    res_ids: list[int] = []
    p = struct.unpack_from("<H", axml, 2)[0]
    while p + 8 <= len(axml):
        ctype, header_size, size = struct.unpack_from("<HHI", axml, p)
        if size < 8:
            break
        if ctype == _STRING_POOL:
            strings = _strings(axml, p)
        elif ctype == _RESOURCE_MAP:
            res_ids = list(struct.unpack_from(f"<{(size - header_size) // 4}I", axml, p + header_size))
        elif ctype == _START_ELEMENT:
            ext = p + header_size
            _, name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<iiHHH", axml, ext)
            if 0 <= name_idx < len(strings) and strings[name_idx] == "manifest":
                code = name = package = None
                for i in range(attr_count):
                    a = ext + attr_start + i * attr_size
                    _, aname, raw, _, _, dtype, data = struct.unpack_from("<iiiHBBI", axml, a)
                    rid = res_ids[aname] if 0 <= aname < len(res_ids) else None
                    label = strings[aname] if 0 <= aname < len(strings) else ""
                    value = strings[raw] if 0 <= raw < len(strings) else None
                    if rid == _VERSION_CODE or label == "versionCode":
                        code = data if dtype in (_TYPE_INT_DEC, _TYPE_INT_HEX) else int(value) if value else None
                    elif rid == _VERSION_NAME or label == "versionName":
                        name = value if value is not None else (strings[data] if dtype == _TYPE_STRING and data < len(strings) else None)
                    elif label == "package":
                        package = value
                return code, name, package
        p += size
    raise NotAnApk("No <manifest> element was found.")


def read(path: Path) -> dict:
    """{"version_code", "version_name", "package", "abi"} for the APK at path."""
    try:
        with zipfile.ZipFile(path) as z:
            try:
                axml = z.read("AndroidManifest.xml")
            except KeyError:
                raise NotAnApk("That file has no AndroidManifest.xml, so it isn't an APK.")
            libs = {n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.count("/") >= 2}
    except zipfile.BadZipFile:
        raise NotAnApk("That file isn't an APK.")
    code, name, package = manifest_versions(axml)
    if not code or not name:
        raise NotAnApk("Couldn't find the version inside that APK.")
    abi = next(iter(libs)) if len(libs) == 1 and next(iter(libs)) in ABIS else "universal"
    return {"version_code": code, "version_name": name, "package": package, "abi": abi}
