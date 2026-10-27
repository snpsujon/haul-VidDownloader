"""Things that keep the server healthy without anyone watching: a nightly yt-dlp update and
cookie check, and the alerts shown at the top of the admin page."""
from __future__ import annotations

import json
import logging
import shutil
import threading
import time

from . import config, cookies, engine, notify, settings, store

log = logging.getLogger("haul.upkeep")
_stop = threading.Event()


def _state_path():
    return config.DATA_DIR / "upkeep.json"


def state() -> dict:
    try:
        return json.loads(_state_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save(**changes) -> None:
    s = state() | changes
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    _state_path().write_text(json.dumps(s, indent=2), encoding="utf-8")


# Alerts ------------------------------------------------------------------------------------

def alerts() -> list[dict]:
    """Problems worth fixing, worst first. level is "bad" or "warn"."""
    out = []
    c = cookies.summary()
    if c.get("present"):
        if c.get("ok") is False or c.get("expired"):
            out.append({"level": "bad", "kind": "cookies", "key": "cookies-bad", "text": "YouTube cookies stopped working. Export fresh ones and upload them below."})
        elif c.get("expires") and c["expires"] < time.time() + 3 * 86400:
            out.append({"level": "warn", "kind": "cookies", "key": "cookies-expiring", "text": "YouTube cookies expire in less than 3 days. Upload fresh ones soon."})

    yt = store.last_results("Youtube%", 10)
    if len(yt) >= 5 and yt[:5].count("error") == 5:
        out.append({"level": "bad", "kind": "failures", "key": "youtube-failing", "text": "The last 5 YouTube downloads failed. YouTube may be blocking the server. "
                    "Try Update yt-dlp, then restart, or upload fresh cookies."})
    for s in store.site_health(24):
        if s["site"].lower().startswith("youtube"):
            continue
        if s["total"] >= 5 and s["failed"] / s["total"] >= 0.5:
            out.append({"level": "warn", "kind": "failures", "key": f"site-failing-{s['site']}", "text": f"{s['site']}: {s['failed']} of {s['total']} downloads failed today."})

    du = shutil.disk_usage(config.DATA_DIR)
    if du.free < 2 * 1024 ** 3 or du.free / du.total < 0.05:
        out.append({"level": "bad", "kind": "disk", "key": "disk-full", "text": f"Disk is almost full ({du.free / 1024 ** 3:.1f} GB free). Delete finished files below."})

    u = state().get("update") or {}
    if u.get("error") and u.get("at", 0) > time.time() - 2 * 86400:
        out.append({"level": "warn", "kind": "updates", "key": "update-failed", "text": "The nightly yt-dlp update failed: " + u["error"]})
    if u.get("restart_needed") and u.get("installed") != engine.versions()["yt_dlp"]:
        out.append({"level": "warn", "kind": "updates", "key": "restart-needed", "text": f"yt-dlp {u['installed']} is installed but the server is still running the old one. Restart to load it."})
    return out


# Nightly job -------------------------------------------------------------------------------

def nightly(jobs) -> dict:
    """Updates yt-dlp, restarts when idle if it changed, then re-checks the cookies."""
    result: dict = {"at": time.time()}
    try:
        result |= engine.update_ytdlp()
    except Exception as e:  # noqa: BLE001 - shown on the admin page
        result["error"] = str(e)[:300]
    _save(update=result)
    log.info("nightly update: %s", result)
    if cookies.path().exists():
        try:
            engine.check_cookies()
        except Exception:  # noqa: BLE001
            pass
    if result.get("restart_needed") and config.AUTO_RESTART:
        # Wait up to an hour for running downloads to finish, then restart to load it.
        for _ in range(60):
            if jobs.active() == 0:
                engine.restart_soon()
                break
            if _stop.wait(60):
                break
    return result


def _seconds_until(hour: int) -> float:
    now = time.localtime()
    target = time.mktime(now[:3] + (hour, 0, 0, 0, 0, -1))
    if target <= time.time():
        target += 86400
    return target - time.time()


# Sending alerts ----------------------------------------------------------------------------

REMIND_HOURS = 12


def send_alerts() -> list[dict]:
    """Sends alerts that are new (or still there after REMIND_HOURS) to Telegram and email.
    Returns what was sent."""
    a = settings.load()["alerts"]
    if not notify.configured(a):
        return []
    now, st = time.time(), state()
    sent: dict = {k: v for k, v in (st.get("notified") or {}).items()}
    current = [x for x in alerts() if a.get(x.get("kind"), True)]
    fresh = [x for x in current if sent.get(x["key"], 0) < now - REMIND_HOURS * 3600]
    if fresh:
        name = settings.load()["site_name"] or "Haul"
        text = f"{name}: " + ("a problem needs a look" if len(fresh) == 1 else f"{len(fresh)} problems need a look") + "\n\n" + \
            "\n".join(("❗ " if x["level"] == "bad" else "⚠️ ") + x["text"] for x in fresh)
        results = notify.send(text, a)
        if any(r["ok"] for r in results):
            for x in fresh:
                sent[x["key"]] = now
        else:
            log.warning("couldn't send alerts: %s", results)
    keys = {x["key"] for x in current}
    _save(notified={k: v for k, v in sent.items() if k in keys})  # a fixed problem can alert again later
    return fresh


def start(jobs) -> None:
    _stop.clear()

    def watch():
        while not _stop.wait(600):
            try:
                send_alerts()
            except Exception:  # noqa: BLE001
                log.exception("sending alerts failed")

    threading.Thread(target=watch, name="haul-alerts", daemon=True).start()
    if not config.AUTO_UPDATE:
        return

    def loop():
        while not _stop.wait(_seconds_until(config.AUTO_UPDATE_HOUR)):
            try:
                nightly(jobs)
            except Exception:  # noqa: BLE001
                log.exception("nightly upkeep failed")
            _stop.wait(120)  # don't run twice in the same minute

    threading.Thread(target=loop, name="haul-upkeep", daemon=True).start()


def stop() -> None:
    _stop.set()
