"""Sends alert messages to Telegram and email, with the details saved on the admin page."""
from __future__ import annotations

import json
import smtplib
import ssl
import urllib.error
import urllib.request
from email.message import EmailMessage
from email.utils import formataddr


def configured(a: dict) -> bool:
    return bool(a.get("telegram_token") and a.get("telegram_chat")) or bool(a.get("smtp_host") and a.get("email_to"))


def telegram(text: str, token: str, chat: str) -> None:
    body = json.dumps({"chat_id": chat, "text": text[:4000], "disable_web_page_preview": True}).encode()
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/sendMessage", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            r.read()
    except urllib.error.HTTPError as e:
        try:
            why = json.loads(e.read().decode()).get("description")
        except Exception:  # noqa: BLE001
            why = None
        raise RuntimeError(f"Telegram said: {why or e.reason}") from None
    except OSError as e:
        raise RuntimeError(f"Couldn't reach Telegram: {e}") from None


def email(text: str, a: dict) -> None:
    msg = EmailMessage()
    msg["Subject"] = text.split("\n", 1)[0][:150]
    sender = a.get("email_from") or a.get("smtp_user") or a["email_to"]
    msg["From"] = formataddr((a["email_name"], sender)) if a.get("email_name") else sender
    msg["To"] = a["email_to"]
    msg.set_content(text)
    security = a.get("smtp_security") or "starttls"
    port = int(a.get("smtp_port") or (465 if security == "ssl" else 587))
    try:
        if security == "ssl":
            s = smtplib.SMTP_SSL(a["smtp_host"], port, timeout=20, context=ssl.create_default_context())
        else:
            s = smtplib.SMTP(a["smtp_host"], port, timeout=20)
            s.ehlo()
            if security == "starttls":
                if not s.has_extn("starttls"):
                    s.close()
                    raise RuntimeError("That email server doesn't offer STARTTLS. Try SSL (port 465) or None.")
                s.starttls(context=ssl.create_default_context())
                s.ehlo()
        with s:
            if a.get("smtp_user"):
                s.login(a["smtp_user"], a.get("smtp_password") or "")
            s.send_message(msg)
    except smtplib.SMTPAuthenticationError:
        raise RuntimeError("The email server refused the username or password.") from None
    except (ConnectionRefusedError, TimeoutError, OSError) as e:
        if isinstance(e, ssl.SSLError):
            raise RuntimeError(f"The secure connection failed ({e.reason or e}). Check that Security matches the port.") from None
        if isinstance(e, smtplib.SMTPException):
            raise RuntimeError(f"Couldn't send the email: {e}") from None
        raise RuntimeError(f"Couldn't connect to {a['smtp_host']}:{port}. Check the host and port.") from None
    except (smtplib.SMTPException, OSError) as e:
        raise RuntimeError(f"Couldn't send the email: {e}") from None


def send(text: str, a: dict, only: str | None = None) -> list[dict]:
    """[{"to": "Telegram" | "Email", "ok", "error"?}] for each channel that's set up
    (only "telegram" or "email" when only is given)."""
    out = []
    if a.get("telegram_token") and a.get("telegram_chat") and only in (None, "telegram"):
        try:
            telegram(text, a["telegram_token"], a["telegram_chat"])
            out.append({"to": "Telegram", "ok": True})
        except RuntimeError as e:
            out.append({"to": "Telegram", "ok": False, "error": str(e)})
    if a.get("smtp_host") and a.get("email_to") and only in (None, "email"):
        try:
            email(text, a)
            out.append({"to": "Email", "ok": True})
        except RuntimeError as e:
            out.append({"to": "Email", "ok": False, "error": str(e)})
    return out
