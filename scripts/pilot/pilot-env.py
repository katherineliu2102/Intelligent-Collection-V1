#!/usr/bin/env python3
"""Pilot env 文件（/opt/app/pilot.env）维护工具。

三个子命令，都先备份再改、只动目标键、写回后打印结果：

  clean             清掉既不是注释也不是 KEY=VALUE 的残行
                    （残行会被 pilot-run.sh 当命令执行，撞 set -e 中断发版）
  clear-whitelists  清空 COLLECTION_PILOT_LOAN_IDS / COLLECTION_SCAN_CASE_IDS，
                    让应用消费 Pub/Sub 真实下发的案件
  clear-redirects   注释掉渠道测试重定向键（SMS/Push/SendGrid 测试收件人/令牌），
                    恢复真实客户收发

用法：
  ./scripts/pilot/pilot-env.py clean
  ./scripts/pilot/pilot-env.py --env /path/pilot.env clear-whitelists
"""
import argparse
import os
import shutil
import time

WHITELIST_KEYS = ("COLLECTION_PILOT_LOAN_IDS", "COLLECTION_SCAN_CASE_IDS")
REDIRECT_KEYS = (
    "CHANNEL_FACADE_TEST_CALLEE",
    "CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT",
    "CHANNEL_SENDGRID_TEST_RECIPIENT",
    "CHANNEL_NOTIFICATION_PUSH_TEST_TOKEN",
)


def backup(path):
    bak = path + ".bak." + time.strftime("%Y%m%d%H%M%S")
    shutil.copy2(path, bak)
    return bak


def read_lines(path):
    return open(path, encoding="utf-8").read().splitlines(True)


def write_lines(path, lines):
    with open(path, "w", encoding="utf-8") as f:
        f.writelines(lines)


def key_of(line):
    raw = line.strip()
    if not raw or raw.lstrip().startswith("#") or "=" not in raw:
        return None
    return raw.split("=", 1)[0].strip()


def cmd_clean(path):
    kept, dropped = [], []
    for idx, line in enumerate(read_lines(path), start=1):
        body = line.rstrip("\n").rstrip("\r")
        if body.strip() == "" or body.lstrip().startswith("#") or "=" in body:
            kept.append(body)
        else:
            dropped.append((idx, body))
    write_lines(path, [l + "\n" for l in kept])
    print("dropped:", dropped if dropped else "none")


def cmd_clear_whitelists(path):
    out, seen = [], set()
    for line in read_lines(path):
        k = key_of(line)
        if k in WHITELIST_KEYS:
            out.append(k + "=\n")
            seen.add(k)
        else:
            out.append(line)
    for k in WHITELIST_KEYS:
        if k not in seen:
            out.append(k + "=\n")
    write_lines(path, out)
    print("cleared:", ",".join(WHITELIST_KEYS))


def cmd_clear_redirects(path):
    out, cleared = [], []
    for line in read_lines(path):
        k = key_of(line)
        if k in REDIRECT_KEYS:
            out.append("# CLEARED_FOR_TRUE_CUSTOMER " + k + "=\n")
            cleared.append(k)
        else:
            out.append(line)
    write_lines(path, out)
    print("cleared:", ",".join(sorted(cleared)) or "<none>")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["clean", "clear-whitelists", "clear-redirects"])
    p.add_argument("--env", default=os.environ.get("PILOT_ENV", "/opt/app/pilot.env"))
    args = p.parse_args()

    bak = backup(args.env)
    print("backup:", bak)
    {
        "clean": cmd_clean,
        "clear-whitelists": cmd_clear_whitelists,
        "clear-redirects": cmd_clear_redirects,
    }[args.cmd](args.env)


if __name__ == "__main__":
    main()
