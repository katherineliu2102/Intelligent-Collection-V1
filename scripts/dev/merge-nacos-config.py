#!/usr/bin/env python3
"""把一个 YAML 片段深合并进 Nacos 上的某个 Data ID，并打印逐键 diff。

按 key 路径深合并 Nacos 配置，避免以文本追加的方式产生重复顶层键
（`collection:` / `channel:`）并导致 SnakeYAML 拒绝应用启动。

凭证从项目根 `.env` 读取 NACOS_*；密钥值一律以 <set:sha1前8位> 形式显示，不打印明文。

用法：
  python3 scripts/dev/merge-nacos-config.py deploy/nacos/l4b-collection.publish.yml            # dry-run
  python3 scripts/dev/merge-nacos-config.py deploy/nacos/l4b-collection.publish.yml --apply    # 备份并发布
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DATA_ID = "intelligent-collection-local.yml"
SECRET_HINTS = ("key", "password", "secret", "token", "credential")


def load_env() -> dict:
    """读 .env，但真实环境变量优先 —— .env 里的账号可能只读，发布时可临时换有写权限的账号。"""
    env = {}
    for line in (ROOT / ".env").read_text().splitlines():
        if "=" in line and not line.strip().startswith("#"):
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip().strip('"')
    for key in ("NACOS_SERVER_ADDR", "NACOS_GROUP", "NACOS_NAMESPACE", "NACOS_USERNAME", "NACOS_PASSWORD"):
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


class Nacos:
    def __init__(self, env: dict):
        self.addr = env["NACOS_SERVER_ADDR"].removeprefix("http://").rstrip("/")
        self.group = env.get("NACOS_GROUP", "DEFAULT_GROUP")
        self.tenant = env.get("NACOS_NAMESPACE", "")
        self.username = env["NACOS_USERNAME"]
        self.password = env["NACOS_PASSWORD"]
        body = urllib.parse.urlencode(
            {"username": self.username, "password": self.password}
        ).encode()
        with urllib.request.urlopen(
            urllib.request.Request(f"http://{self.addr}/nacos/v1/auth/login", data=body)
        ) as resp:
            self.token = json.load(resp)["accessToken"]

    def get(self, data_id: str) -> str:
        query = urllib.parse.urlencode(
            {
                "dataId": data_id,
                "group": self.group,
                "tenant": self.tenant,
                "accessToken": self.token,
            }
        )
        with urllib.request.urlopen(f"http://{self.addr}/nacos/v1/cs/configs?{query}") as resp:
            return resp.read().decode()

    def publish(self, data_id: str, content: str) -> str:
        # 写接口按 username/password 鉴权：accessToken 走同一路径会被 Nacos 403（与
        # Nacos 写接口的兼容口径）。
        body = urllib.parse.urlencode(
            {
                "dataId": data_id,
                "group": self.group,
                "tenant": self.tenant,
                "type": "yaml",
                "content": content,
                "username": self.username,
                "password": self.password,
            }
        ).encode()
        try:
            with urllib.request.urlopen(
                urllib.request.Request(
                    f"http://{self.addr}/nacos/v1/cs/configs", data=body, method="POST"
                )
            ) as resp:
                return resp.read().decode()
        except urllib.error.HTTPError as e:
            if e.code == 403:
                return "__forbidden"
            raise


def deep_merge(base: dict, patch: dict) -> dict:
    merged = dict(base)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def unset(node, path: list) -> None:
    if not isinstance(node, dict) or path[0] not in node:
        return
    if len(path) == 1:
        node.pop(path[0])
        return
    unset(node[path[0]], path[1:])


def flatten(node, prefix="") -> dict:
    flat = {}
    if isinstance(node, dict):
        for key, value in node.items():
            flat.update(flatten(value, f"{prefix}.{key}" if prefix else str(key)))
    else:
        flat[prefix] = node
    return flat


def mask(path: str, value) -> str:
    text = "" if value is None else str(value)
    if any(hint in path.lower() for hint in SECRET_HINTS) and text:
        digest = hashlib.sha1(text.encode()).hexdigest()[:8]
        return f"<set:{digest}>"
    return repr(value)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("patch_file", help="要合并进去的 YAML 片段")
    parser.add_argument("--data-id", default=DATA_ID)
    parser.add_argument("--apply", action="store_true", help="备份现网后真正发布")
    parser.add_argument(
        "--unset",
        action="append",
        default=[],
        metavar="a.b.c",
        help="从现网删除该 key 路径（深合并只能新增/改写，删除空转键需要它）",
    )
    args = parser.parse_args()

    nacos = Nacos(load_env())
    live_text = nacos.get(args.data_id)
    live = yaml.safe_load(live_text) or {}
    patch = yaml.safe_load(Path(args.patch_file).read_text()) or {}

    merged = deep_merge(live, patch)
    for path in args.unset:
        unset(merged, path.split("."))
    before, after = flatten(live), flatten(merged)

    print(f"dataId={args.data_id} group={nacos.group} namespace={nacos.tenant or '<public>'}")
    print("\n--- 变更键（值已脱敏）---")
    changed = 0
    for path in sorted(set(before) | set(after)):
        if before.get(path) != after.get(path):
            changed += 1
            state = "新增" if path not in before else "改写"
            print(f"  {state} {path}: {mask(path, before.get(path))} → {mask(path, after.get(path))}")
    if not changed:
        print("  无变更")

    merged_text = yaml.safe_dump(merged, allow_unicode=True, sort_keys=False, default_flow_style=False)
    if not args.apply:
        print("\n[dry-run] 未发布；加 --apply 执行")
        return

    stamp = f"{datetime.now():%Y%m%d%H%M%S}"
    backup = ROOT / "deploy" / "nacos" / f"backup-{args.data_id}-{stamp}.yml"
    backup.write_text(live_text)
    print(f"\n现网已备份到 {backup.relative_to(ROOT)}")

    result = nacos.publish(args.data_id, merged_text)
    if result == "__forbidden":
        # .env 里的 Nacos 账号对该命名空间只有读权限；把合并结果落盘，供控制台粘贴。
        merged_file = ROOT / "deploy" / "nacos" / f"merged-{args.data_id}-{stamp}.yml"
        merged_file.write_text(merged_text)
        sys.exit(
            "Nacos 返回 403 authorization failed：当前账号对该命名空间只读，无法发布。\n"
            f"合并结果已写入 {merged_file.relative_to(ROOT)}，"
            "可在 Nacos 控制台粘贴发布，或换一个有写权限的账号后重跑。\n"
            "⚠ 该文件含明文密钥（已被 .gitignore 覆盖），发布后请删除。"
        )
    print("发布结果:", result)
    if result.strip() != "true":
        sys.exit("Nacos 未返回 true，发布失败")


if __name__ == "__main__":
    main()
