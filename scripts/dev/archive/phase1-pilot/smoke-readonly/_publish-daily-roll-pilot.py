#!/usr/bin/env python3
"""Publish dailyRoll using Pilot GOOGLE_APPLICATION_CREDENTIALS (SA JSON). Do not print secrets."""
from __future__ import annotations

import base64
import json
import os
import time
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = "fintech-all"
TOPIC = "intelligent-collection-schedule-v1"
CRED = Path("/opt/app/secrets/credentials.json")


def token_from_authorized_user(cfg: dict) -> str:
    body = urllib.parse.urlencode(
        {
            "client_id": cfg["client_id"],
            "client_secret": cfg["client_secret"],
            "refresh_token": cfg["refresh_token"],
            "grant_type": "refresh_token",
        }
    ).encode()
    with urllib.request.urlopen(
        urllib.request.Request("https://oauth2.googleapis.com/token", data=body)
    ) as resp:
        return json.load(resp)["access_token"]


def token_from_service_account(cfg: dict) -> str:
    import hashlib
    import hmac
    import json as jsonlib

    # Use PyJWT-free JWT: google SA needs RS256. Prefer google-auth if present.
    try:
        from google.oauth2 import service_account
        from google.auth.transport.requests import Request

        creds = service_account.Credentials.from_service_account_info(
            cfg, scopes=["https://www.googleapis.com/auth/pubsub"]
        )
        creds.refresh(Request())
        return creds.token
    except ImportError:
        pass
    # cryptography / jwt may exist; else openssl via subprocess
    try:
        import jwt  # PyJWT
    except ImportError:
        jwt = None
    now = int(time.time())
    payload = {
        "iss": cfg["client_email"],
        "scope": "https://www.googleapis.com/auth/pubsub",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now,
        "exp": now + 3600,
    }
    if jwt is None:
        raise SystemExit("need google-auth or PyJWT to mint SA token")
    assertion = jwt.encode(payload, cfg["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion,
        }
    ).encode()
    with urllib.request.urlopen(
        urllib.request.Request("https://oauth2.googleapis.com/token", data=body)
    ) as resp:
        return json.load(resp)["access_token"]


def publish(token: str) -> str:
    msg = {
        "data": base64.b64encode(b"scheduled-tick").decode(),
        "attributes": {"job": "dailyRoll"},
    }
    req = urllib.request.Request(
        f"https://pubsub.googleapis.com/v1/projects/{PROJECT}/topics/{TOPIC}:publish",
        data=json.dumps({"messages": [msg]}).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.load(resp)["messageIds"][0]
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:800]
        raise SystemExit(f"publish HTTP {e.code}: {body}") from e


def main() -> None:
    cfg = json.loads(CRED.read_text())
    typ = cfg.get("type")
    ident = cfg.get("client_email") or (cfg.get("client_id") or "")[:24]
    print(f"cred_type={typ} ident={ident}")
    if typ == "authorized_user":
        token = token_from_authorized_user(cfg)
    elif typ == "service_account":
        token = token_from_service_account(cfg)
    else:
        raise SystemExit(f"unsupported cred type {typ}")
    mid = publish(token)
    print(f"published messageId={mid}")


if __name__ == "__main__":
    import urllib.error

    main()
