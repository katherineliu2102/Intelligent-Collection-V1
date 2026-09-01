#!/usr/bin/env python3
"""向调度 topic 发一条 tick，用于 T5-S 的手工注入（S2/S3/S5/S6）。

Pilot 机上 gcloud 用的是 VM 默认 compute SA，其 access scope 不含 pubsub.publish，
`gcloud pubsub topics publish` 会返回 ACCESS_TOKEN_SCOPE_INSUFFICIENT。故注入从本地
用仓库根的 authorized_user ADC 走 REST，与 provision-scheduler.py 同一套凭证。

    publish-schedule-tick.py planStepDue            # 带 job 属性（应用只按属性路由）
    publish-schedule-tick.py --no-attribute 'job: planStepDue'   # S3：只把 job 写进消息体
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = "fintech-all"
TOPIC = "intelligent-collection-schedule-v1"


def access_token() -> str:
    adc = Path(__file__).resolve().parents[2] / "credentials.json"
    if not adc.exists():
        sys.exit(f"找不到 {adc}；向主架构负责人索取 ADC 凭证后重试")
    cfg = json.loads(adc.read_text())
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


def publish(token: str, message: str, job: str | None) -> str:
    msg = {"data": base64.b64encode(message.encode()).decode()}
    if job is not None:
        msg["attributes"] = {"job": job}
    req = urllib.request.Request(
        f"https://pubsub.googleapis.com/v1/projects/{PROJECT}/topics/{TOPIC}:publish",
        data=json.dumps({"messages": [msg]}).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)["messageIds"][0]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("job", help="job 属性值；配 --no-attribute 时该参数作为消息体")
    ap.add_argument(
        "--no-attribute",
        action="store_true",
        help="不带 job 属性发布，用于验证应用不从消息体读 job（S3）",
    )
    ap.add_argument("-n", "--count", type=int, default=1, help="连发几条（S5/S6）")
    args = ap.parse_args()

    token = access_token()
    for _ in range(args.count):
        if args.no_attribute:
            print(publish(token, args.job, None))
        else:
            print(publish(token, "scheduled-tick", args.job))


if __name__ == "__main__":
    main()
