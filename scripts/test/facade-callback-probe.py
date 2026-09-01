#!/usr/bin/env python3
"""L2-CB 级别 4 的回调探针：对 Facade 账户级入口发构造/重放报文，闭合台账列的四项差集。

签名口径见 [Facade 客户接入手册 §11.3](../../docs/channel/FACADE客户接入手册.md)：HMAC-SHA256 打在
**canonical JSON**（`sort_keys=True` + 紧凑分隔符）上，而非原始 body 字节——服务端
`FacadeWebhookService` 先解析再 `FacadeCanonicalJson.dumps` 后验签，直接对字节签名必然失配。

    facade-callback-probe.py replay <canonical-json-file>     # 原样重放真实报文（验幂等）
    facade-callback-probe.py answered  --case 517301 --plan 843 --step 1684
    facade-callback-probe.py answered  --case 517301 --no-metadata   # 走身份反查分支
    facade-callback-probe.py forged    --case 517301 --plan 843 --step 1684

`answered` 造的是 `was_ai_connected=true` + `reason=NORMAL`，即 `FacadeCallbackMapper` 唯一映射到
`ANSWERED` 的组合。它拿不到真拨样本：批准测试号 +639451374358 实测三次都是
`MEDIA_NEGOTIATION_FAILED`（SIP 406），压根接不通，所以真人接通只能验我方映射这一侧。
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.request

URL = os.environ.get(
    "FACADE_CALLBACK_URL", "https://collection-admin.mocasa.com/webhook/facade-callback"
)


def canonical(payload: dict) -> str:
    return json.dumps(payload, separators=(",", ":"), sort_keys=True)


# Facade 入口读的是 X-Valubo-Signature；`/webhook/channel-callback` 用的才是
# X-Callback-Signature。两个端点头名不同，用错的那个等于没带签名，一律 401。
SIGNATURE_HEADER = "X-Valubo-Signature"


def post(body: str, secret: str, label: str) -> None:
    sig = hmac.new(secret.encode(), body.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(
        URL,
        data=body.encode(),
        headers={"Content-Type": "application/json", SIGNATURE_HEADER: sig},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print(f"{label} HTTP {resp.status} {resp.read().decode()}")
    except urllib.error.HTTPError as e:
        print(f"{label} HTTP {e.code} {e.read().decode()[:240]}")


def session_completed(args, *, answered: bool) -> dict:
    # external_batch_id 会被 identity() 当 providerMsgId 写进 timeline（session.completed 里
    # 通常没有 batch_id），所以不能让它带上 None —— 无 metadata 时按案件编号命名。
    batch_ref = (
        f"probe-{args.plan}:{args.step}"
        if args.plan and args.step
        else f"probe-case-{args.case}"
    )
    payload = {
        "event": "session.completed",
        "session_id": args.session,
        "external_batch_id": batch_ref,
        "external_case_id": str(args.case),
        "line_outcome": {
            "reason": "NORMAL" if answered else "NO_ANSWER",
            "sip_code": 200 if answered else 480,
            "was_ringing": True,
            "was_answered": answered,
            "was_ai_connected": answered,
        },
        "ai_result": {"promises": [], "result_label": None, "summary": None},
        "attempt_count": 1,
    }
    # 不带 client_metadata 时服务端按 external_case_id 反查唯一 EXECUTING AI_CALL 步骤。
    if not args.no_metadata:
        payload["client_metadata"] = {
            "case_id": args.case,
            "plan_id": args.plan,
            "step_id": args.step,
        }
    return payload


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["replay", "answered", "forged"])
    ap.add_argument("file", nargs="?", help="replay 模式下的 canonical JSON 文件")
    ap.add_argument("--case", type=int)
    ap.add_argument("--plan", type=int)
    ap.add_argument("--step", type=int)
    ap.add_argument("--session", default="l2cb-probe-answered-001")
    ap.add_argument("--no-metadata", action="store_true", help="不带 client_metadata，验身份反查")
    args = ap.parse_args()

    secret = os.environ.get("FACADE_CALLBACK_SECRET")
    if not secret:
        sys.exit("需要 FACADE_CALLBACK_SECRET（取自部署位 pilot.env，不入库）")

    if args.mode == "replay":
        if not args.file:
            sys.exit("replay 需要 canonical JSON 文件")
        with open(args.file) as f:
            raw = f.read().strip()
        # 必须解析后重新规范化，不能把文件内容当 canonical 直接签。
        # 审计表的 canonical_payload 是 MySQL JSON 列，读回时键序按 MySQL 自己的口径
        # （先长度后字典序）重排过，与 Python 的 sort_keys 不一致 —— 直接签会 401。
        post(canonical(json.loads(raw)), secret, "[重放真实报文]")
        return

    payload = session_completed(args, answered=True)
    if args.mode == "forged":
        post(canonical(payload), "wrong-secret-on-purpose", "[伪造签名]")
    else:
        post(canonical(payload), secret, "[真人接通]")


if __name__ == "__main__":
    main()
