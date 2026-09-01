#!/usr/bin/env python3
"""L4a / L4b 测试用 Pub/Sub 资源的幂等自助开通（测试 SSOT T0-3）。

为什么不用 gcloud：本机 gcloud 的交互登录态会过期（reauth failed），而 ADC 的 refresh token
仍然有效，因此这里直接用 ADC 换 access token 走 Pub/Sub REST。

开通内容：
  1. topic  intelligent-collection-cases-test1      —— L4a 合成源专用，正式 topic 一律不发合成消息
  2. sub    intelligent-collection-cases-test1-sub  —— L4a 消费入口
  3. sub    intelligent-collection-cases-v1-l4b-sub —— 挂在正式 topic 上的独立订阅（扇出，
                                                       正式订阅 -v1-sub 的投递不受影响）
  4. topic  intelligent-collection-cases-dlq(+sub)  —— 两个测试订阅共用的死信去处

安全参数：保留期 1 天（测试订阅不该长期堆积）、ackDeadline 60s（对齐数据接入规格 §2.1）、
7 天无活动自动过期删除（忘记清理也不会长期残留）、死信投递 5 次后转 DLQ。

用法：python3 scripts/test/provision-l4-pubsub.py [--dry-run] [--delete]
"""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = "fintech-all"
PROJECT_NUMBER = "148313078015"
CASES_TOPIC_PROD = "intelligent-collection-cases-v1"
CASES_TOPIC_TEST = "intelligent-collection-cases-test1"
DLQ_TOPIC = "intelligent-collection-cases-dlq"
SUB_L4A = "intelligent-collection-cases-test1-sub"
SUB_L4B = "intelligent-collection-cases-v1-l4b-sub"
SUB_DLQ = "intelligent-collection-cases-dlq-sub"

ACK_DEADLINE_SECONDS = 60
TEST_RETENTION = "86400s"  # 1 天：测试订阅不长期堆积
DLQ_RETENTION = "604800s"  # 7 天：死信要留够排查时间
IDLE_EXPIRATION = "604800s"  # 7 天无活动自动删除，避免遗留订阅
MAX_DELIVERY_ATTEMPTS = 5

PUBSUB = "https://pubsub.googleapis.com/v1"
SERVICE_AGENT = f"serviceAccount:service-{PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"


def access_token() -> str:
    adc = Path(__file__).resolve().parents[2] / "credentials.json"
    if not adc.exists():
        sys.exit(f"找不到 {adc}；向主架构负责人索取 ADC 凭证后重试")
    cfg = json.loads(adc.read_text())
    if cfg.get("type") != "authorized_user":
        sys.exit(f"credentials.json 类型为 {cfg.get('type')}，本脚本只处理 authorized_user ADC")
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
    def __init__(self, token: str, dry_run: bool):
        self.token = token
        self.dry_run = dry_run

    def _call(self, method: str, url: str, body=None):
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
            return {"__status": e.code, "__detail": e.read().decode()[:400]}

    def exists(self, resource: str) -> bool:
        return "__status" not in self._call("GET", f"{PUBSUB}/{resource}")

    def put(self, resource: str, body: dict, label: str) -> bool:
        if self.exists(resource):
            print(f"  = 已存在，跳过：{label}")
            return True
        if self.dry_run:
            print(f"  + [dry-run] 将创建：{label}")
            return True
        result = self._call("PUT", f"{PUBSUB}/{resource}", body)
        if "__status" in result:
            print(f"  ! 创建失败：{label} → {result['__status']} {result['__detail']}")
            return False
        print(f"  + 已创建：{label}")
        return True

    def delete(self, resource: str, label: str) -> None:
        if not self.exists(resource):
            print(f"  = 不存在，跳过：{label}")
            return
        if self.dry_run:
            print(f"  - [dry-run] 将删除：{label}")
            return
        result = self._call("DELETE", f"{PUBSUB}/{resource}")
        print(
            f"  - 已删除：{label}"
            if "__status" not in result
            else f"  ! 删除失败：{label} → {result['__status']}"
        )

    def grant(self, resource: str, role: str, member: str) -> None:
        """死信投递依赖 Pub/Sub 服务代理的权限：DLQ topic 上要能 publish，源订阅上要能 subscribe。"""
        if self.dry_run:
            print(f"  + [dry-run] 将授予 {role} on {resource}")
            return
        policy = self._call("GET", f"{PUBSUB}/{resource}:getIamPolicy")
        if "__status" in policy:
            print(f"  ! 读取 IAM 失败：{resource} → {policy['__status']}")
            return
        bindings = policy.get("bindings", [])
        for binding in bindings:
            if binding.get("role") == role and member in binding.get("members", []):
                print(f"  = IAM 已具备：{role} on {resource.split('/')[-1]}")
                return
        bindings.append({"role": role, "members": [member]})
        policy["bindings"] = bindings
        result = self._call("POST", f"{PUBSUB}/{resource}:setIamPolicy", {"policy": policy})
        print(
            f"  + 已授予：{role} on {resource.split('/')[-1]}"
            if "__status" not in result
            else f"  ! 授权失败：{resource} → {result['__status']} {result['__detail']}"
        )


def topic(name: str) -> str:
    return f"projects/{PROJECT}/topics/{name}"


def subscription(name: str) -> str:
    return f"projects/{PROJECT}/subscriptions/{name}"


def test_subscription_body(topic_name: str) -> dict:
    return {
        "topic": topic(topic_name),
        "ackDeadlineSeconds": ACK_DEADLINE_SECONDS,
        "messageRetentionDuration": TEST_RETENTION,
        "expirationPolicy": {"ttl": IDLE_EXPIRATION},
        "retainAckedMessages": False,
        "deadLetterPolicy": {
            "deadLetterTopic": topic(DLQ_TOPIC),
            "maxDeliveryAttempts": MAX_DELIVERY_ATTEMPTS,
        },
        "labels": {"purpose": "l4-testing", "owner": "collection-architect"},
    }


def provision(api: Api) -> None:
    print("[1/4] 隔离 topic 与 DLQ topic")
    api.put(topic(CASES_TOPIC_TEST), {"labels": {"purpose": "l4a-synthetic"}}, CASES_TOPIC_TEST)
    api.put(topic(DLQ_TOPIC), {"labels": {"purpose": "l4-deadletter"}}, DLQ_TOPIC)

    print("[2/4] DLQ 权限（Pub/Sub 服务代理）")
    api.grant(topic(DLQ_TOPIC), "roles/pubsub.publisher", SERVICE_AGENT)

    print("[3/4] 订阅")
    api.put(subscription(SUB_L4A), test_subscription_body(CASES_TOPIC_TEST), SUB_L4A)
    api.put(subscription(SUB_L4B), test_subscription_body(CASES_TOPIC_PROD), SUB_L4B)
    api.put(
        subscription(SUB_DLQ),
        {
            "topic": topic(DLQ_TOPIC),
            "ackDeadlineSeconds": ACK_DEADLINE_SECONDS,
            "messageRetentionDuration": DLQ_RETENTION,
            "expirationPolicy": {"ttl": IDLE_EXPIRATION},
            "labels": {"purpose": "l4-deadletter"},
        },
        SUB_DLQ,
    )

    print("[4/4] 源订阅上的死信 subscriber 权限")
    for sub in (SUB_L4A, SUB_L4B):
        api.grant(subscription(sub), "roles/pubsub.subscriber", SERVICE_AGENT)


def teardown(api: Api) -> None:
    print("[1/2] 删除订阅")
    for sub in (SUB_L4A, SUB_L4B, SUB_DLQ):
        api.delete(subscription(sub), sub)
    print("[2/2] 删除本轮自建 topic（正式 topic 不动）")
    for name in (CASES_TOPIC_TEST, DLQ_TOPIC):
        api.delete(topic(name), name)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的动作")
    parser.add_argument("--delete", action="store_true", help="清理本脚本创建的资源")
    args = parser.parse_args()

    api = Api(access_token(), args.dry_run)
    print(f"project={PROJECT} dry_run={args.dry_run} action={'delete' if args.delete else 'create'}")
    teardown(api) if args.delete else provision(api)


if __name__ == "__main__":
    main()
