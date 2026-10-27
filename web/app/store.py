"""Request log in SQLite, for the admin page. Old rows are dropped after LOG_DAYS."""
from __future__ import annotations

import sqlite3
import threading
import time

from . import config

_lock = threading.Lock()
_conn: sqlite3.Connection | None = None

_SCHEMA = """
CREATE TABLE IF NOT EXISTS requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  job TEXT,
  ip TEXT,
  user_agent TEXT,
  source TEXT,
  client_version TEXT,
  url TEXT,
  title TEXT,
  site TEXT,
  kind TEXT,
  label TEXT,
  status TEXT,
  error TEXT,
  size INTEGER,
  seconds REAL
);
CREATE INDEX IF NOT EXISTS requests_ts ON requests(ts);
CREATE INDEX IF NOT EXISTS requests_job ON requests(job);
CREATE TABLE IF NOT EXISTS installs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  kind TEXT NOT NULL,
  detail TEXT,
  ip TEXT,
  country TEXT
);
CREATE INDEX IF NOT EXISTS installs_ts ON installs(ts);
"""


def init() -> None:
    global _conn
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    _conn = sqlite3.connect(config.DATA_DIR / "haul.db", check_same_thread=False)
    _conn.row_factory = sqlite3.Row
    with _lock:
        _conn.executescript(_SCHEMA)
        cols = {r[1] for r in _conn.execute("PRAGMA table_info(requests)")}
        if "country" not in cols:
            _conn.execute("ALTER TABLE requests ADD COLUMN country TEXT")
        _conn.execute("CREATE INDEX IF NOT EXISTS requests_ip ON requests(ip, ts)")
        _conn.execute("DELETE FROM installs WHERE ts < ?", (time.time() - 365 * 86400,))
        _conn.execute("DELETE FROM requests WHERE ts < ?", (time.time() - config.LOG_DAYS * 86400,))
        _conn.commit()


def close() -> None:
    global _conn
    if _conn is not None:
        _conn.close()
        _conn = None


def log(**row) -> None:
    if _conn is None:
        return
    row.setdefault("ts", time.time())
    cols = ", ".join(row)
    marks = ", ".join("?" for _ in row)
    with _lock:
        _conn.execute(f"INSERT INTO requests ({cols}) VALUES ({marks})", tuple(row.values()))
        _conn.commit()


def finish(job: str, **changes) -> None:
    if _conn is None or not changes:
        return
    sets = ", ".join(f"{k} = ?" for k in changes)
    with _lock:
        _conn.execute(f"UPDATE requests SET {sets} WHERE job = ?", (*changes.values(), job))
        _conn.commit()


def recent(limit: int = 200, offset: int = 0, q: str | None = None) -> list[dict]:
    if _conn is None:
        return []
    sql = "SELECT * FROM requests"
    args: list = []
    if q:
        sql += " WHERE url LIKE ? OR title LIKE ? OR ip LIKE ? OR site LIKE ?"
        args += [f"%{q}%"] * 4
    sql += " ORDER BY ts DESC LIMIT ? OFFSET ?"
    args += [limit, offset]
    with _lock:
        return [dict(r) for r in _conn.execute(sql, args).fetchall()]


def count(q: str | None = None) -> int:
    if _conn is None:
        return 0
    sql, args = "SELECT COUNT(*) FROM requests", []
    if q:
        sql += " WHERE url LIKE ? OR title LIKE ? OR ip LIKE ? OR site LIKE ?"
        args += [f"%{q}%"] * 4
    with _lock:
        return _conn.execute(sql, args).fetchone()[0]


def stats() -> dict:
    if _conn is None:
        return {}
    day = time.time() - 86400
    week = time.time() - 7 * 86400
    with _lock:
        c = _conn.execute
        total = c("SELECT COUNT(*) FROM requests").fetchone()[0]
        today = c("SELECT COUNT(*) FROM requests WHERE ts > ?", (day,)).fetchone()[0]
        failed = c("SELECT COUNT(*) FROM requests WHERE ts > ? AND status = 'error'", (day,)).fetchone()[0]
        visitors = c("SELECT COUNT(DISTINCT ip) FROM requests WHERE ts > ?", (day,)).fetchone()[0]
        bytes_ = c("SELECT COALESCE(SUM(size), 0) FROM requests WHERE ts > ?", (day,)).fetchone()[0]
        sites = [dict(r) for r in c("SELECT site, COUNT(*) AS n FROM requests WHERE ts > ? GROUP BY site ORDER BY n DESC LIMIT 8", (week,)).fetchall()]
        sources = [dict(r) for r in c("SELECT source, COUNT(*) AS n FROM requests WHERE ts > ? GROUP BY source ORDER BY n DESC", (week,)).fetchall()]
    return {"total": total, "today": today, "failed_today": failed, "visitors_today": visitors,
            "bytes_today": bytes_, "top_sites_week": sites, "sources_week": sources}


def clear() -> int:
    if _conn is None:
        return 0
    with _lock:
        n = _conn.execute("DELETE FROM requests").rowcount
        _conn.commit()
    return n


def export() -> list[dict]:
    return recent(limit=1_000_000)


def daily(days: int = 14) -> list[dict]:
    """Requests per day (server's local time), oldest first, with empty days filled in."""
    if _conn is None:
        return []
    start = time.mktime(time.localtime(time.time() - (days - 1) * 86400)[:3] + (0, 0, 0, 0, 0, -1))
    with _lock:
        rows = _conn.execute(
            "SELECT date(ts, 'unixepoch', 'localtime') AS day, COUNT(*) AS total, "
            "SUM(status = 'done') AS done, SUM(status = 'error') AS failed, COALESCE(SUM(size), 0) AS bytes, "
            "COUNT(DISTINCT ip) AS people FROM requests WHERE ts >= ? GROUP BY day", (start,)).fetchall()
    by_day = {r["day"]: dict(r) for r in rows}
    out = []
    for i in range(days):
        day = time.strftime("%Y-%m-%d", time.localtime(start + i * 86400 + 3600))
        out.append(by_day.get(day) or {"day": day, "total": 0, "done": 0, "failed": 0, "bytes": 0, "people": 0})
    return out


def site_health(hours: int = 24) -> list[dict]:
    """Requests and failures per site over the last hours, busiest first."""
    if _conn is None:
        return []
    with _lock:
        rows = _conn.execute(
            "SELECT COALESCE(site, '?') AS site, COUNT(*) AS total, SUM(status = 'error') AS failed "
            "FROM requests WHERE ts > ? AND status IN ('done', 'error') GROUP BY site ORDER BY total DESC LIMIT 10",
            (time.time() - hours * 3600,)).fetchall()
    return [dict(r) for r in rows]


def last_results(site_like: str, n: int = 10) -> list[str]:
    """Statuses of the latest finished requests for sites matching site_like, newest first."""
    if _conn is None:
        return []
    with _lock:
        rows = _conn.execute(
            "SELECT status FROM requests WHERE site LIKE ? AND status IN ('done', 'error') ORDER BY ts DESC LIMIT ?",
            (site_like, n)).fetchall()
    return [r[0] for r in rows]


def count_ip(ip: str, since: float) -> int:
    """Downloads started by ip since then (for the daily limit)."""
    if _conn is None or not ip:
        return 0
    with _lock:
        return _conn.execute("SELECT COUNT(*) FROM requests WHERE ip = ? AND ts > ?", (ip, since)).fetchone()[0]


def log_install(kind: str, detail: str | None = None, ip: str | None = None, country: str | None = None) -> None:
    """A download of the app, extension or helper (or a click through to a store)."""
    if _conn is None:
        return
    with _lock:
        _conn.execute("INSERT INTO installs (ts, kind, detail, ip, country) VALUES (?, ?, ?, ?, ?)",
                      (time.time(), kind, detail, ip, country))
        _conn.commit()


def _since(days: int) -> float:
    return time.mktime(time.localtime(time.time() - (days - 1) * 86400)[:3] + (0, 0, 0, 0, 0, -1))


def stats_report(days: int = 30) -> dict:
    """Everything on the Stats page for the last days."""
    if _conn is None:
        return {}
    start = _since(days)
    with _lock:
        c = _conn.execute
        sites = [dict(r) for r in c(
            "SELECT COALESCE(site, '?') AS site, COUNT(*) AS n, SUM(status = 'error') AS failed, COALESCE(SUM(size), 0) AS bytes "
            "FROM requests WHERE ts >= ? GROUP BY site ORDER BY n DESC LIMIT 15", (start,)).fetchall()]
        countries = [dict(r) for r in c(
            "SELECT COALESCE(country, '') AS country, COUNT(*) AS n, COUNT(DISTINCT ip) AS people "
            "FROM requests WHERE ts >= ? GROUP BY COALESCE(country, '') ORDER BY n DESC LIMIT 15", (start,)).fetchall()]
        sources = [dict(r) for r in c(
            "SELECT COALESCE(source, '?') AS source, COUNT(*) AS n FROM requests WHERE ts >= ? GROUP BY source ORDER BY n DESC",
            (start,)).fetchall()]
        kinds = [dict(r) for r in c(
            "SELECT COALESCE(kind, '?') AS kind, COUNT(*) AS n FROM requests WHERE ts >= ? GROUP BY kind ORDER BY n DESC",
            (start,)).fetchall()]
        rows = c("SELECT date(ts, 'unixepoch', 'localtime') AS day, kind, COUNT(*) AS n FROM installs "
                 "WHERE ts >= ? GROUP BY day, kind", (start,)).fetchall()
        totals = {r["kind"]: r["n"] for r in c("SELECT kind, COUNT(*) AS n FROM installs WHERE ts >= ? GROUP BY kind", (start,)).fetchall()}
        detail = [dict(r) for r in c("SELECT kind, COALESCE(detail, '') AS detail, COUNT(*) AS n FROM installs WHERE ts >= ? "
                                     "GROUP BY kind, detail ORDER BY n DESC LIMIT 20", (start,)).fetchall()]
    by_day: dict[str, dict] = {}
    for r in rows:
        by_day.setdefault(r["day"], {})[r["kind"]] = r["n"]
    daily = []
    for i in range(days):
        day = time.strftime("%Y-%m-%d", time.localtime(start + i * 86400 + 3600))
        daily.append({"day": day, **{k: by_day.get(day, {}).get(k, 0) for k in ("android", "extension", "helper")}})
    return {"days": days, "sites": sites, "countries": countries, "sources": sources, "kinds": kinds,
            "installs": {"daily": daily, "totals": totals, "detail": detail}}
