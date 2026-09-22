#!/usr/bin/env python3
"""T3o-7 演练：向案件 topic 发一条「能被解析、但处理必然瞬态失败」的白名单消息。

判定见[T3o 执行取证手册 §5](../../docs/testing/runbooks/MOCASA催收系统升级_Phase1_T3o执行取证手册.md)：
该消息应连续 nack 超过 `maxDeliveryAttempts=5`，被转投 `intelligent-collection-cases-dlq`，
且原始 payload 与 `eventId` 保留。

失败方式选的是 `overdueAmount` 超出 `t_ai_collection.overdue_amount DECIMAL(18,2)` 的范围：
报文能过解析与契约校验（所以不会被判 PoisonMessageException 直接 ack），却在写投影时被
MySQL 严格模式拒绝，落到消费者的通用 catch → nack。这正是 DLQ 要兜的那一类——代码以为
是瞬态、实际重投多少次都不会成功。

caseId 用合成号而非真实案件：写库必失败意味着投影不会被改，但万一该假设不成立（比如
非严格模式会截断），拿真实案件做靶子就会污染借款人的欠款金额。合成号需先临时加进
`COLLECTION_PILOT_LOAN_IDS`，否则消息在白名单闸门就被 ack 跳过，压根到不了注入点。

    publish-cases-dlq-drill.py                    # 发一条，打印 eventId
    publish-cases-dlq-drill.py --case-id 99000915
    publish-cases-dlq-drill.py --list             # 列出死信订阅现存消息
    publish-cases-dlq-drill.py --replay <eventId> # 受控重放：修好金额重发，并把死信原件 ack 结案
    publish-cases-dlq-drill.py --terminate <messageId> --reason '...'   # 无法修复的死信终止结案
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = "fintech-all"
TOPIC = "intelligent-collection-cases-v1"
DLQ_SUB = "intelligent-collection-cases-dlq-sub"

# DECIMAL(18,2) 的上界是 9999999999999999.99，这里再高两个数量级，确保严格模式必报
# "Out of range value"，而不是靠精度截断侥幸通过。
OUT_OF_RANGE_AMOUNT = "999999999999999999999.99"


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


def api(token: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(
        f"https://pubsub.googleapis.com/v1/projects/{PROJECT}/{path}",
        data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as resp:
        raw = resp.read()
    return json.loads(raw) if raw else {}


def drain_dlq(token: str, rounds: int = 6) -> dict[str, tuple[dict, str]]:
    """把死信订阅现存消息收敛成 messageId -> (message, 最新 ackId)。

    Pub/Sub 的 pull 一次只给一个随机子集，单次拉取看不全；重复拉几轮靠 messageId 去重，
    后到的 ackId 覆盖先到的（旧 ackId 会随 ackDeadline 过期而失效）。
    """
    seen: dict[str, tuple[dict, str]] = {}
    for _ in range(rounds):
        resp = api(token, f"subscriptions/{DLQ_SUB}:pull", {"maxMessages": 10})
        for m in resp.get("receivedMessages", []):
            seen[m["message"]["messageId"]] = (m["message"], m["ackId"])
    return seen


def describe(message: dict) -> dict:
    raw = base64.b64decode(message["data"])
    try:
        payload = json.loads(raw)
        data = payload.get("data")
        return payload if isinstance(data, dict) else {"dataType": payload.get("dataType")}
    except json.JSONDecodeError:
        return {}


def build_message(case_id: int, event_id: str) -> dict:
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    # borrower / device 在数仓契约里是「字符串化的 JSON」而非嵌套对象，照抄现网形态。
    borrower = json.dumps(
        {
            "name": "T3o Drill",
            "email": "wzynju@126.com",
            "phone": "+639451374358",
            "language": "en",
        }
    )
    data = {
        "eventId": event_id,
        "caseId": case_id,
        "userId": case_id,
        "caseVersion": hashlib.md5(event_id.encode()).hexdigest(),
        "dpd": 5,
        "stage": "S1",
        "product": "1",
        "collectionStatus": "IN_COLLECTION",
        "occurredAt": now,
        "overdueAmount": OUT_OF_RANGE_AMOUNT,
        "overduePrincipal": "100.00",
        "overdueInterest": "0.00",
        "overduePenaltyAmount": "0.00",
        "upcomingAmount": "0.00",
        "nextDueDate": "2026-09-01",
        "borrower": borrower,
        "device": "{}",
    }
    return {"dataType": "caseEvent", "data": data}


def publish(token: str, payload: dict) -> str:
    resp = api(
        token,
        f"topics/{TOPIC}:publish",
        {
            "messages": [
                {
                    "data": base64.b64encode(json.dumps(payload).encode()).decode(),
                    "attributes": {"dataType": "caseEvent"},
                }
            ]
        },
    )
    return resp["messageIds"][0]


def cmd_list(token: str) -> None:
    for mid, (msg, _) in drain_dlq(token).items():
        attrs = msg.get("attributes", {})
        data = describe(msg).get("data", {})
        print(f"--- messageId={mid}")
        print(f"    源订阅   = {attrs.get('CloudPubSubDeadLetterSourceSubscription')}")
        print(f"    投递次数 = {attrs.get('CloudPubSubDeadLetterSourceDeliveryCount')}")
        print(f"    发布时间 = {attrs.get('CloudPubSubDeadLetterSourceTopicPublishTime')}")
        print(f"    eventId  = {data.get('eventId') if isinstance(data, dict) else None}")
        if not isinstance(data, dict) or not data:
            print(f"    原文     = {base64.b64decode(msg['data'])[:120]!r}")


def cmd_replay(token: str, event_id: str) -> None:
    """修复后重放：把超范围金额换成合法值，用同一 eventId 重发，再 ack 掉死信原件。

    eventId 不变是关键——接入侧的幂等键就是它。重放一次业务应执行一次；同一 eventId 再发
    第二次必须被去重跳过，两者合起来才满足手册「业务只执行一次」。
    """
    target = None
    for mid, (msg, ack_id) in drain_dlq(token).items():
        data = describe(msg).get("data", {})
        if isinstance(data, dict) and data.get("eventId") == event_id:
            target = (mid, msg, ack_id, data)
            break
    if target is None:
        sys.exit(f"死信订阅里找不到 eventId={event_id}")

    mid, msg, ack_id, data = target
    payload = json.loads(base64.b64decode(msg["data"]))
    payload["data"]["overdueAmount"] = "100.00"
    new_msg_id = publish(token, payload)
    print(f"已重放 eventId={event_id} overdueAmount={OUT_OF_RANGE_AMOUNT} → 100.00")
    print(f"  新 messageId = {new_msg_id}")

    api(token, f"subscriptions/{DLQ_SUB}:acknowledge", {"ackIds": [ack_id]})
    print(f"  死信原件 messageId={mid} 已 ack 结案")


def cmd_terminate(token: str, message_id: str, reason: str) -> None:
    """终止结案：无法修复的死信（如 payload 结构本身非法）只能 ack 丢弃。

    Pub/Sub 死信订阅没有「带备注归档」的位置，理由只能落在这里的输出与台账里，
    所以 --reason 是必填，避免出现无人知道为何被丢掉的消息。
    """
    hit = drain_dlq(token).get(message_id)
    if hit is None:
        sys.exit(f"死信订阅里找不到 messageId={message_id}")
    msg, ack_id = hit
    print(f"终止 messageId={message_id}")
    print(f"  原文 = {base64.b64decode(msg['data'])[:200]!r}")
    print(f"  理由 = {reason}")
    api(token, f"subscriptions/{DLQ_SUB}:acknowledge", {"ackIds": [ack_id]})
    print("  已 ack 结案")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--case-id", type=int, default=99000915)
    ap.add_argument("--list", action="store_true", help="列出死信订阅现存消息")
    ap.add_argument("--replay", metavar="EVENT_ID", help="修复金额后重放，并 ack 死信原件")
    ap.add_argument("--terminate", metavar="MESSAGE_ID", help="终止一条无法修复的死信")
    ap.add_argument("--reason", help="--terminate 的结案理由（必填）")
    args = ap.parse_args()

    token = access_token()
    if args.list:
        cmd_list(token)
    elif args.replay:
        cmd_replay(token, args.replay)
    elif args.terminate:
        if not args.reason:
            sys.exit("--terminate 必须带 --reason")
        cmd_terminate(token, args.terminate, args.reason)
    else:
        event_id = f"t3o7-drill-{int(time.time())}"
        print(f"eventId={event_id}")
        print(f"messageId={publish(token, build_message(args.case_id, event_id))}")


if __name__ == "__main__":
    main()
