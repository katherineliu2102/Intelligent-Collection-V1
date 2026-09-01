#!/usr/bin/env python3
"""在候选案件 Topic 上挂只读观测订阅，判断上游实际在往哪个 Topic 发布。

背景：正式订阅不能碰（同一订阅的消息只投一个消费者，peek 就是从生产消费者手里抢）。
Pub/Sub 的扇出语义让**独立订阅**只拿消息副本，因此挂观测订阅对现有消费者零影响。

局限：订阅只能收到**创建之后**发布的消息，看不到历史。所以低频 Topic 需要覆盖一个发布周期
（案件快照按契约 03:00 PHT 前发完，repaymentEvent 每 15 分钟）再回来 peek。

peek 采用 pull + modifyAckDeadline=0，消息立刻退回队列，不消费、不 ack。

用法：
  python3 scripts/test/observe-upstream-topics.py --create   # 挂观测订阅
  python3 scripts/test/observe-upstream-topics.py            # peek 各订阅
  python3 scripts/test/observe-upstream-topics.py --delete   # 回收
"""

import argparse
import base64
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = "fintech-all"
PUBSUB = "https://pubsub.googleapis.com/v1"
OBSERVER_SUFFIX = "-observer-tmp"
# 候选：契约定稿 Topic + 两个历史 Topic（线上 Nacos 曾指向 collection-cases-test1-sub）
CANDIDATE_TOPICS = [
    "intelligent-collection-cases-v1",
    "collection-cases",
    "collection-cases-test1",
]
RETENTION = "600s"
IDLE_EXPIRATION = "86400s"


def access_token() -> str:
    adc = Path(__file__).resolve().parents[2] / "credentials.json"
    if not adc.exists():
        sys.exit(f"找不到 {adc}")
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


class Api:
    def __init__(self, token: str):
        self.token = token

    def call(self, method: str, url: str, body=None):
        req = urllib.request.Request(
            url,
            data=json.dumps(body).encode() if body is not None else None,
            method=method,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            return {"__status": e.code, "__detail": e.read().decode()[:200]}


def observer_name(topic: str) -> str:
    return f"{topic}{OBSERVER_SUFFIX}"


def create(api: Api) -> None:
    for topic in CANDIDATE_TOPICS:
        sub = observer_name(topic)
        path = f"projects/{PROJECT}/subscriptions/{sub}"
        if "__status" not in api.call("GET", f"{PUBSUB}/{path}"):
            print(f"  = 已存在：{sub}")
            continue
        result = api.call(
            "PUT",
            f"{PUBSUB}/{path}",
            {
                "topic": f"projects/{PROJECT}/topics/{topic}",
                "ackDeadlineSeconds": 60,
                "messageRetentionDuration": RETENTION,
                "expirationPolicy": {"ttl": IDLE_EXPIRATION},
                "labels": {"purpose": "upstream-observation", "temporary": "true"},
            },
        )
        print(
            f"  + 已创建：{sub}"
            if "__status" not in result
            else f"  ! 失败：{sub} → {result['__status']} {result['__detail']}"
        )


def peek(api: Api) -> None:
    for topic in CANDIDATE_TOPICS:
        sub = observer_name(topic)
        base = f"{PUBSUB}/projects/{PROJECT}/subscriptions/{sub}"
        result = api.call("POST", f"{base}:pull", {"maxMessages": 10, "returnImmediately": True})
        if "__status" in result:
            print(f"{topic}: 观测订阅不可用（{result['__status']}），先跑 --create")
            continue
        messages = result.get("receivedMessages", [])
        print(f"\n{topic}: 取到 {len(messages)} 条")
        for message in messages:
            payload = message["message"]
            raw = base64.b64decode(payload.get("data", "")).decode("utf-8", "replace")
            summary = raw[:160]
            try:
                parsed = json.loads(raw)
                summary = (
                    f"dataType={parsed.get('dataType')} caseId={parsed.get('caseId')} "
                    f"顶层键={sorted(parsed.keys())}"
                )
            except json.JSONDecodeError:
                pass
            print(f"  publishTime={payload.get('publishTime')} attrs={payload.get('attributes')}")
            print(f"    {summary}")
        if messages:
            # 立刻退回队列：不消费任何上游消息
            api.call(
                "POST",
                f"{base}:modifyAckDeadline",
                {"ackIds": [m["ackId"] for m in messages], "ackDeadlineSeconds": 0},
            )
            print("  （已全部退回队列）")


def delete(api: Api) -> None:
    for topic in CANDIDATE_TOPICS:
        sub = observer_name(topic)
        path = f"projects/{PROJECT}/subscriptions/{sub}"
        if "__status" in api.call("GET", f"{PUBSUB}/{path}"):
            print(f"  = 不存在：{sub}")
            continue
        result = api.call("DELETE", f"{PUBSUB}/{path}")
        print(f"  - 已删除：{sub}" if "__status" not in result else f"  ! 删除失败：{sub}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--create", action="store_true")
    parser.add_argument("--delete", action="store_true")
    args = parser.parse_args()

    api = Api(access_token())
    if args.create:
        create(api)
    elif args.delete:
        delete(api)
    else:
        peek(api)


if __name__ == "__main__":
    main()
