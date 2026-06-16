#!/usr/bin/env python3
"""SendGrid 直连发信（不经 DB / 不经 plan）。Phase 1 五封里程碑。"""
import json
import os
import sys
import urllib.request
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EMAIL = "wzynju@126.com"

TEMPLATES = {
    "S0_DUE_TODAY_EMAIL": "d-9b485bfd24e14950a7811faf33c2b22f",
    "S1_EMAIL_OVERDUE_NOTICE": "d-bc7f5aee7e304caf93ca4d435a73a1d7",
    "S2_EMAIL_ENTRY": "d-86ed8faae3b24489ad7db8a11067b8c4",
    "S4_EMAIL_ENTRY": "d-658d5be184ab4710a19c8419ed66bca9",
    "S4_EMAIL_PRE_CLOSE": "d-881ce23667cc4df2abf82097b890cae1",
}

CASES = [
    (92002, "test s0 user2", "S0_DUE_TODAY_EMAIL", 0, Decimal("5000.00"), date.today()),
    (93101, "test s1 user1", "S1_EMAIL_OVERDUE_NOTICE", 2, Decimal("2500.00"), date(2026, 6, 6)),
    (93201, "test s2 user1", "S2_EMAIL_ENTRY", 4, Decimal("4000.00"), date(2026, 6, 1)),
    (93401, "test s4 user1", "S4_EMAIL_ENTRY", 31, Decimal("5000.00"), date(2026, 5, 8)),
    (93404, "test s4 user4", "S4_EMAIL_PRE_CLOSE", 75, Decimal("5000.00"), date(2026, 3, 25)),
]


def load_dotenv():
    env_path = ROOT / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def load_nacos_publish_yaml():
    """Standalone 脚本从 nacos-publish.local.yml 读取 SendGrid 密钥（应用运行时从 Nacos 下发）。"""
    path = ROOT / "nacos-publish.local.yml"
    if not path.exists():
        return
    section = None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("sendgrid:"):
            section = "sendgrid"
            continue
        if line.startswith("notification:") or line.startswith("channel:"):
            section = None
            continue
        if section == "sendgrid" and line.startswith("api-key:"):
            os.environ.setdefault("SENDGRID_API_KEY", line.split(":", 1)[1].strip())
        if section == "sendgrid" and line.startswith("from-email:"):
            os.environ.setdefault("SENDGRID_FROM_EMAIL", line.split(":", 1)[1].strip())


def assignment_date(due: date) -> str:
    d = due.replace(day=due.day)  # copy
    from datetime import timedelta
    target = due + timedelta(days=91)
    return target.strftime("%B %d, %Y").replace(" 0", " ")


def template_data(case_id, name, slot, dpd, amount, due):
    data = {
        "borrower_name": name,
        "amount_due": float(amount),
        "overdue_days": dpd,
        "payment_link": f"https://app.mocasa.test/repay/{case_id}",
        "script_slot": slot,
    }
    if slot == "S4_EMAIL_PRE_CLOSE":
        data["assignment_date"] = assignment_date(due)
    return data


def send_one(api_key, from_email, case_id, name, slot, dpd, amount, due):
    tid = TEMPLATES[slot]
    payload = {
        "personalizations": [{
            "to": [{"email": EMAIL}],
            "dynamic_template_data": template_data(case_id, name, slot, dpd, amount, due),
            "custom_args": {"case_id": str(case_id), "script_slot": slot},
        }],
        "from": {"email": from_email, "name": "MOCASA Collections"},
        "template_id": tid,
    }
    req = urllib.request.Request(
        "https://api.sendgrid.com/v3/mail/send",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            msg_id = resp.headers.get("X-Message-Id", "")
            return True, msg_id or f"http-{resp.status}"
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return False, f"HTTP {e.code}: {body[:200]}"


def main():
    load_dotenv()
    load_nacos_publish_yaml()
    api_key = os.environ.get("SENDGRID_API_KEY", "")
    from_email = os.environ.get("SENDGRID_FROM_EMAIL", "collections@mocasa.com")
    if not api_key:
        print("SENDGRID_API_KEY missing (Nacos channel.sendgrid.api-key 或 nacos-publish.local.yml)")
        sys.exit(1)

    print(f"from={from_email} to={EMAIL} (no DB)")
    ok_count = 0
    for case_id, name, slot, dpd, amount, due in CASES:
        ok, detail = send_one(api_key, from_email, case_id, name, slot, dpd, amount, due)
        status = "PASS" if ok else "FAIL"
        print(f"  {status} caseId={case_id} {slot} -> {detail}")
        if ok:
            ok_count += 1
    print(f"done {ok_count}/{len(CASES)}")
    sys.exit(0 if ok_count == len(CASES) else 1)


if __name__ == "__main__":
    main()
