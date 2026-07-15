#!/usr/bin/env python3
"""Login and fetch dashboard outreach API."""
import json
import urllib.request
import http.cookiejar

BASE = "http://localhost:8888"
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def post(path, body):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with opener.open(req) as resp:
        return json.loads(resp.read())


def get(path):
    with opener.open(BASE + path) as resp:
        return json.loads(resp.read())


login = post("/auth/login", {"username": "admin", "role": "SYSTEM_ADMIN"})
print("login:", login.get("success"), login.get("message", ""))

for days in (7, 30):
    data = get(f"/dashboard/outreach/realtime?days={days}")
    if not data.get("success"):
        print(f"days={days} FAILED:", data)
        continue
    s = data["data"]["summary"]
    attempted = s.get("totalAttempted", s.get("totalSent", 0))
    print(
        f"days={days}: records={s.get('totalRecords', s.get('totalSent', 0))} "
        f"attempted={attempted} delivered={s.get('delivered', 0)} "
        f"skipped={s.get('skipped', 0)} other={s.get('other', 0)} "
        f"rate={s.get('deliveryRate', 0)}"
    )
