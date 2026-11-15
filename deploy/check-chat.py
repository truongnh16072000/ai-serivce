#!/usr/bin/env python3
import datetime
import json
import socket
import sys
import time
import urllib.error
import urllib.request

CHAT_URL = "https://ai-service.htlabsapp.io.vn/api/v1/chat"
TELEGRAM_URL = "http://127.0.0.1:18080/api/v1/telegram/messages"
APP_CODE = "work_planner"


def post_json(url, payload, timeout):
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-APP-CODE": APP_CODE},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read(4096).decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read(4096).decode("utf-8", errors="replace")


def main():
    started = time.monotonic()
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    try:
        status, detail = post_json(CHAT_URL, {"message": "Reply exactly OK."}, 140)
    except OSError as error:
        status, detail = 0, str(error)
    elapsed = time.monotonic() - started
    if status == 200:
        print(f"{timestamp} Chat OK: HTTP 200 in {elapsed:.1f}s", flush=True)
        return 0

    text = (
        f"AI service: CHAT API ERROR\nHost: {socket.gethostname()}\nUTC: {timestamp}\n"
        f"URL: {CHAT_URL}\nHTTP: {status or 'connection error'}\n"
        f"Duration: {elapsed:.1f}s\nError: {detail[:2400]}"
    )
    print(text, file=sys.stderr, flush=True)
    try:
        alert_status, alert_detail = post_json(
            TELEGRAM_URL, {"text": text, "disableLinkPreview": True}, 20
        )
        if not 200 <= alert_status < 300:
            print(f"Telegram failed: HTTP {alert_status}: {alert_detail[:1000]}",
                  file=sys.stderr, flush=True)
            return 2
        # shortcut: HTTP 202 only confirms queueing; use an independent notifier if delivery guarantees are needed.
        print(f"Telegram accepted alert: HTTP {alert_status}", flush=True)
    except OSError as error:
        print(f"Telegram connection failed: {error}", file=sys.stderr, flush=True)
        return 2
    return 1


if __name__ == "__main__":
    sys.exit(main())
