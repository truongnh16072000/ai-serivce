#!/usr/bin/env python3
import base64
import datetime
import json
import math
import os
from pathlib import Path
import socket
import sys
import tempfile
import time
import urllib.request

AUTH_FILE = Path("/var/lib/ai-service/.codex/auth.json")
HOST_AUTH_FILE = Path("/root/.codex/auth.json")
STATE_FILE = Path("/var/lib/ai-service-codex-monitor/alerts.json")
TELEGRAM_URL = "http://127.0.0.1:18080/api/v1/telegram/messages"


def token_expiry(content):
    token = json.loads(content)["tokens"]["access_token"]
    if not isinstance(token, str):
        raise ValueError("Invalid access token")
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("Invalid access token")
    payload = base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4))
    expiry = json.loads(payload)["exp"]
    if type(expiry) is not int or expiry <= 0:
        raise ValueError("Invalid token expiry")
    return expiry


def sync_auth():
    try:
        source = HOST_AUTH_FILE.read_bytes()
        source_expiry = token_expiry(source)
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        print(f"Host Codex auth unavailable: {type(error).__name__}; checking service auth", flush=True)
        return
    if source_expiry <= time.time():
        print("Host Codex access token has expired; skipping sync", flush=True)
        return
    try:
        current_expiry = token_expiry(AUTH_FILE.read_bytes())
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        current_expiry = 0
    if current_expiry >= source_expiry:
        print("Service auth has the same or a later expiry; skipping sync", flush=True)
        return

    owner = AUTH_FILE.parent.stat()
    descriptor, temporary = tempfile.mkstemp(prefix=".auth-sync-", dir=AUTH_FILE.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchown(output.fileno(), owner.st_uid, owner.st_gid)
            output.write(source)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, AUTH_FILE)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print("Synced newer host Codex auth atomically; mode=600, service ownership preserved", flush=True)


def notify(text):
    request = urllib.request.Request(
        TELEGRAM_URL,
        data=json.dumps({"text": text, "disableLinkPreview": True}).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-APP-CODE": "work_planner"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status != 202:
            raise ValueError("Telegram did not accept the alert")


def main():
    try:
        sync_auth()
        expiry = token_expiry(AUTH_FILE.read_bytes())
        days = max(0, math.ceil((expiry - time.time()) / 86400))
        vietnam = datetime.timezone(datetime.timedelta(hours=7))
        expires_at = datetime.datetime.fromtimestamp(expiry, vietnam).strftime("%d/%m/%Y %H:%M:%S UTC+7")
        print(f"Codex access token expires {expires_at}; remaining days: {days}", flush=True)
        if days > 3:
            return 0

        state = json.loads(STATE_FILE.read_text()) if STATE_FILE.exists() else {}
        sent = state.get("sent", []) if state.get("expiry") == expiry else []
        if days in sent:
            print(f"Already notified for milestone {days}; skipping", flush=True)
            return 0
        warning = f"con toi da {days} ngay" if days else "DA HET HAN"
        text = (
            f"CANH BAO CODEX: access token {warning}\n"
            f"Host: {socket.gethostname()}\nService: ai-service\n"
            f"Het han: {expires_at}\n"
            "Kiem tra/dang nhap lai Codex neu can. "
            "Refresh token co the tu gia han access token; han phien dang nhap khong duoc cong bo."
        )
        notify(text)
        # shortcut: HTTP 202 confirms queueing only; an independent notifier is needed for guaranteed delivery.
        temporary = STATE_FILE.with_suffix(".tmp")
        temporary.write_text(json.dumps({"expiry": expiry, "sent": sent + [days]}))
        temporary.replace(STATE_FILE)
        print(f"Telegram accepted milestone {days}; notification recorded", flush=True)
        return 0
    except (OSError, ValueError, KeyError, TypeError, IndexError, OverflowError) as error:
        # Auth and HTTP errors may contain credentials; log only the exception type.
        print(f"Codex session check failed: {type(error).__name__}; check auth/state files and Telegram API",
              file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
