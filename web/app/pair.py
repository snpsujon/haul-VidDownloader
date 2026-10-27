"""Send to phone: the extension pairs with Haul on a phone once, then sends it links through
the site. The site only holds the links until the phone picks them up.

Flow: the extension makes a pair (POST /api/pair) and shows a QR code with
haul://pair?site=…&id=<pair_id>&token=<receive_token>. The phone scans it and claims the pair.
After that the extension posts links with its send_key and the phone collects them from its
inbox with the receive_token. Wrong or unknown keys get 404 {"ok": false}.
"""
from __future__ import annotations

import hmac
import secrets
import sqlite3
import threading
import time
from collections import defaultdict, deque

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from . import config
from .security import BadLink, check_url

UNCLAIMED_TTL = 600          # an unclaimed pair lasts 10 minutes
PAIR_TTL = 180 * 86400       # a pair nobody uses for half a year is dropped
ITEM_TTL = 7 * 86400         # links wait a week for the phone
MAX_ITEMS = 50               # per pair

_lock = threading.Lock()
_conn: sqlite3.Connection | None = None

_SCHEMA = """
CREATE TABLE IF NOT EXISTS pairs (
  id TEXT PRIMARY KEY,
  send_key TEXT NOT NULL,
  receive_token TEXT NOT NULL,
  created REAL NOT NULL,
  claimed REAL,
  device_name TEXT,
  last_used REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS pair_items (
  id TEXT PRIMARY KEY,
  pair_id TEXT NOT NULL,
  url TEXT NOT NULL,
  mode TEXT NOT NULL,
  title TEXT,
  sent_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS pair_items_pair ON pair_items(pair_id, sent_at);
"""


def _db() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        config.DATA_DIR.mkdir(parents=True, exist_ok=True)
        _conn = sqlite3.connect(config.DATA_DIR / "pairs.db", check_same_thread=False)
        _conn.row_factory = sqlite3.Row
        _conn.executescript(_SCHEMA)
    return _conn


def close() -> None:
    global _conn
    if _conn is not None:
        _conn.close()
        _conn = None


def _sweep(db: sqlite3.Connection) -> None:
    now = time.time()
    db.execute("DELETE FROM pairs WHERE (claimed IS NULL AND created < ?) OR last_used < ?",
               (now - UNCLAIMED_TTL, now - PAIR_TTL))
    db.execute("DELETE FROM pair_items WHERE sent_at < ? OR pair_id NOT IN (SELECT id FROM pairs)",
               (now - ITEM_TTL,))


def _same(a: str | None, b: str | None) -> bool:
    return bool(a) and bool(b) and hmac.compare_digest(a.encode(), b.encode())


def _find(pair_id: str, *, send_key: str | None = None, receive_token: str | None = None) -> sqlite3.Row | None:
    """The pair, if the key matches. Unclaimed pairs past their 10 minutes don't count."""
    row = _db().execute("SELECT * FROM pairs WHERE id = ?", (pair_id or "",)).fetchone()
    if row is None:
        return None
    if row["claimed"] is None and row["created"] < time.time() - UNCLAIMED_TTL:
        return None
    if _same(send_key, row["send_key"]) or _same(receive_token, row["receive_token"]):
        return row
    return None


# Light rate limit, per IP: a burst of 30, then 1 a second.
_hits: dict[str, deque] = defaultdict(deque)


def _client_ip(request: Request) -> str:
    if config.TRUST_PROXY_HEADERS:
        fwd = request.headers.get("x-forwarded-for")
        if fwd:
            return fwd.split(",")[0].strip()
    return request.client.host if request.client else "?"


def _limited(request: Request, burst: int = 30, window: float = 30.0) -> bool:
    ip, now = _client_ip(request), time.monotonic()
    with _lock:
        q = _hits[ip]
        while q and q[0] < now - window:
            q.popleft()
        if len(q) >= burst:
            return True
        q.append(now)
        if len(_hits) > 10000:
            _hits.clear()
    return False


def _reply(body: dict, status: int = 200) -> JSONResponse:
    # The extension calls these from its own pages, so they're open to any origin.
    return JSONResponse(body, status_code=status, headers={"Access-Control-Allow-Origin": "*"})


def _nope() -> JSONResponse:
    return _reply({"ok": False}, 404)


def _slow() -> JSONResponse:
    return _reply({"ok": False, "error": "Too many requests. Try again in a moment."}, 429)


class Claim(BaseModel):
    pair_id: str = Field(max_length=64)
    receive_token: str = Field(max_length=128)
    device_name: str = Field("", max_length=80)


class Send(BaseModel):
    pair_id: str = Field(max_length=64)
    send_key: str = Field(max_length=128)
    url: str = Field(max_length=4000)
    mode: str = Field("video", pattern="^(video|audio)$")
    title: str | None = Field(None, max_length=300)


class Unpair(BaseModel):
    pair_id: str = Field(max_length=64)
    send_key: str | None = Field(None, max_length=128)
    receive_token: str | None = Field(None, max_length=128)


router = APIRouter(prefix="/api/pair", tags=["send to phone"])


@router.post("", summary="Start pairing (the extension shows the QR code)")
async def create(request: Request):
    if _limited(request, burst=10, window=60):
        return _slow()
    now = time.time()
    pair = {"pair_id": secrets.token_urlsafe(12), "send_key": secrets.token_urlsafe(32),
            "receive_token": secrets.token_urlsafe(32)}
    with _lock:
        db = _db()
        _sweep(db)
        db.execute("INSERT INTO pairs (id, send_key, receive_token, created, last_used) VALUES (?, ?, ?, ?, ?)",
                   (pair["pair_id"], pair["send_key"], pair["receive_token"], now, now))
        db.commit()
    return _reply({**pair, "expires_in": UNCLAIMED_TTL})


@router.post("/claim", summary="The phone claims a pair after scanning its QR code")
async def claim(body: Claim, request: Request):
    if _limited(request):
        return _slow()
    with _lock:
        row = _find(body.pair_id, receive_token=body.receive_token)
        if row is None:
            return _nope()
        now = time.time()
        _db().execute("UPDATE pairs SET claimed = COALESCE(claimed, ?), device_name = ?, last_used = ? WHERE id = ?",
                      (now, body.device_name.strip() or "Phone", now, row["id"]))
        _db().commit()
    return _reply({"ok": True})


@router.get("/status", summary="Whether the phone has claimed the pair yet")
async def status(request: Request, id: str = "", key: str = ""):
    if _limited(request, burst=120, window=60):
        return _slow()
    with _lock:
        row = _find(id, send_key=key)
    if row is None:
        return _nope()
    return _reply({"ok": True, "claimed": row["claimed"] is not None, "device_name": row["device_name"]})


@router.post("/send", summary="Send a link to the paired phone")
async def send(body: Send, request: Request):
    if _limited(request):
        return _slow()
    try:
        url = check_url(body.url)
    except BadLink as e:
        return _reply({"ok": False, "error": str(e)}, 400)
    with _lock:
        row = _find(body.pair_id, send_key=body.send_key)
        if row is None or row["claimed"] is None:
            return _nope()
        db, now = _db(), time.time()
        count = db.execute("SELECT COUNT(*) FROM pair_items WHERE pair_id = ?", (row["id"],)).fetchone()[0]
        if count >= MAX_ITEMS:
            return _reply({"ok": False, "error": "The phone has 50 links waiting. Open Haul on it to collect them."}, 429)
        item_id = secrets.token_urlsafe(9)
        title = (body.title or "").strip() or None
        db.execute("INSERT INTO pair_items (id, pair_id, url, mode, title, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                   (item_id, row["id"], url, body.mode, title, now))
        db.execute("UPDATE pairs SET last_used = ? WHERE id = ?", (now, row["id"]))
        db.commit()
    return _reply({"ok": True, "item_id": item_id})


@router.get("/inbox", summary="The phone collects its waiting links (they're removed once returned)")
async def inbox(request: Request, id: str = "", token: str = ""):
    if _limited(request, burst=120, window=60):
        return _slow()
    with _lock:
        row = _find(id, receive_token=token)
        if row is None:
            return _nope()
        db, now = _db(), time.time()
        rows = db.execute("SELECT id, url, mode, title, sent_at FROM pair_items WHERE pair_id = ? AND sent_at >= ? "
                          "ORDER BY sent_at", (row["id"], now - ITEM_TTL)).fetchall()
        db.execute("DELETE FROM pair_items WHERE pair_id = ?", (row["id"],))
        db.execute("UPDATE pairs SET last_used = ? WHERE id = ?", (now, row["id"]))
        db.commit()
    return _reply({"ok": True, "items": [dict(r) for r in rows]})


@router.post("/unpair", summary="Forget the pair, from either side")
async def unpair(body: Unpair, request: Request):
    if _limited(request):
        return _slow()
    with _lock:
        row = _find(body.pair_id, send_key=body.send_key, receive_token=body.receive_token)
        if row is None:
            return _nope()
        _db().execute("DELETE FROM pair_items WHERE pair_id = ?", (row["id"],))
        _db().execute("DELETE FROM pairs WHERE id = ?", (row["id"],))
        _db().commit()
    return _reply({"ok": True})
