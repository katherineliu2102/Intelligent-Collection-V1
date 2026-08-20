"""L1 Facade smoke: load api-key from Nacos, then create -> cases -> start.

Does not print secrets. Uses insecure TLS for the self-signed test Facade.
"""
from __future__ import print_function

import json
import os
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CALLEE = "+639451373897"
PHT = timezone(timedelta(hours=8))


def load_dotenv():
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def nacos_yaml():
    params = urllib.parse.urlencode(
        {
            "dataId": "intelligent-collection-local.yml",
            "group": os.environ["NACOS_GROUP"],
            "tenant": os.environ["NACOS_NAMESPACE"],
            "username": os.environ["NACOS_USERNAME"],
            "password": os.environ["NACOS_PASSWORD"],
        }
    )
    url = "http://{}/nacos/v1/cs/configs?{}".format(
        os.environ["NACOS_SERVER_ADDR"], params
    )
    return urllib.request.urlopen(url, timeout=15).read().decode("utf-8")


def parse_facade(yaml_text):
    cfg = {
        "base-url": "https://34.158.34.184/api/v1/facade",
        "api-key": "",
        "product-type": "Quick Loan",
        "currency": "PHP",
        "timezone": "Asia/Manila",
        "window-start": "08:00",
        "window-end": "21:00",
        "test-callee": CALLEE,
    }
    in_facade = False
    for raw in yaml_text.splitlines():
        if raw.strip().startswith("facade:"):
            in_facade = True
            continue
        if in_facade:
            if raw and not raw.startswith(" ") and not raw.startswith("\t"):
                break
            if raw.startswith("  ") and not raw.startswith("    ") and ":" in raw:
                if not raw.strip().startswith("#") and not raw.strip().startswith("facade:"):
                    key = raw.strip().split(":", 1)[0]
                    if key not in ("base-url", "api-key", "product-type", "currency",
                                   "timezone", "test-callee"):
                        break
            if ":" not in raw:
                continue
            key, val = raw.strip().split(":", 1)
            val = val.strip().strip("\"'")
            if key in cfg and val:
                cfg[key] = val
    if not cfg["api-key"]:
        raise SystemExit("Nacos channel.facade.api-key is empty")
    return cfg


def request_json(method, url, api_key, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + api_key)
    req.add_header("Content-Type", "application/json")
    ctx = ssl._create_unverified_context()
    try:
        with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else {}
        except ValueError:
            parsed = {"raw": raw[:500]}
        return e.code, parsed


def main():
    load_dotenv()
    cfg = parse_facade(nacos_yaml())
    base = cfg["base-url"].rstrip("/")
    print("nacos_api_key=set len={}".format(len(cfg["api-key"])))
    print("base_url={}".format(base))
    print("callee={}".format(CALLEE[:4] + "****" + CALLEE[-2:]))
    print("product_type={} currency={}".format(cfg["product-type"], cfg["currency"]))

    due = (datetime.now(PHT).date() - timedelta(days=5)).isoformat()
    external_id = "mocasa-smoke-90001-{}".format(int(time.time() * 1000))
    batch_body = {
        "external_batch_id": external_id,
        "script": {"domain": "collection"},
        "dial_policy": {
            "timezone": cfg["timezone"],
            "windows": [{"start_time": "08:00", "end_time": "21:00"}],
            "weekdays": [1, 2, 3, 4, 5, 6, 7],
        },
    }
    preview_script = json.dumps(batch_body["script"])
    print("script_has_language=", "language" in preview_script)
    print("dry_run_preview_ok=True")

    status, created = request_json("POST", base + "/batches", cfg["api-key"], batch_body)
    print("create_status=", status, "success=", created.get("success"))
    if not created.get("success"):
        err = created.get("error") or created
        print("create_error=", err)
        raise SystemExit(1)
    batch_id = created.get("data", {}).get("batch_id")
    print("batch_id=", batch_id)

    case_body = {
        "cases": [
            {
                "external_case_id": external_id,
                "callee_e164": CALLEE,
                "business_context": {
                    "borrower": {"name": "Test Borrower"},
                    "debt": {
                        "product_type": cfg["product-type"],
                        "currency": cfg["currency"],
                        "overdue_amount": 1000,
                        "days_past_due": 5,
                        "due_date": due,
                    },
                    "prior_contacts": [],
                    "prior_promises": [],
                    "client_metadata": {"case_id": 90001, "smoke": True},
                },
            }
        ]
    }
    status, uploaded = request_json(
        "POST", base + "/batches/" + batch_id + "/cases", cfg["api-key"], case_body
    )
    print("cases_status=", status, "success=", uploaded.get("success"),
          "rejected=", (uploaded.get("data") or {}).get("rejected"))
    if not uploaded.get("success") or (uploaded.get("data") or {}).get("rejected", 0) > 0:
        print("cases_error=", uploaded.get("error") or uploaded.get("data"))
        raise SystemExit(1)

    status, started = request_json(
        "POST", base + "/batches/" + batch_id + "/start", cfg["api-key"], {}
    )
    print("start_status=", status, "success=", started.get("success"))
    if not started.get("success"):
        print("start_error=", started.get("error") or started)
        raise SystemExit(1)

    time.sleep(2)
    status, batch = request_json("GET", base + "/batches/" + batch_id, cfg["api-key"])
    data = batch.get("data") or batch
    print("poll_status=", status, "success=", batch.get("success"))
    if isinstance(data, dict):
        keep = {k: data.get(k) for k in (
            "batch_id", "status", "state", "total_cases", "queued", "dialing",
            "completed", "failed", "external_batch_id"
        ) if k in data}
        print("batch=", json.dumps(keep, ensure_ascii=False))
    print("result=DELIVERED")


if __name__ == "__main__":
    main()
