#!/usr/bin/env python3
"""调度链 GCP 资源的幂等自助开通（基础设施 §5.5 O2 / O6，T3o 前置）。

与 provision-l4-pubsub.py 同源：本机 gcloud 交互登录态在本项目上无权限
（keliu 账号 pubsub.topics.list / cloudscheduler.jobs.list 均 IAM_PERMISSION_DENIED），
而仓库根 ADC 的 refresh token 有效，因此直接换 access token 走 REST。

处理内容：
  1. 两条订阅的参数纠偏
     - 调度订阅（O2/O5）：现网建成时 ackDeadline=10s、保留 7 天，规范要求 60s / 10m。
     - 案件接入订阅：现网 ackDeadline=10s，而应用侧 collection.ingestion.ack-deadline-seconds=60，
       数据接入规格 §2.1 明确要求「须与 Subscription 的 ack deadline 一致」。
     ack 过短的实际危害是处理还没结束租约就到期、消息被重复投递：幂等能保证正确性，
     但会持续刷 IN_FLIGHT / 重复消费，把真实积压埋进噪音里。
     两条订阅还都带 expirationPolicy.ttl=31 天（**无活动即自动删除订阅**）。对尚未部署消费者的
     Pilot 来说这是静默数据丢失的雷：订阅一旦过期被删，数仓继续往 topic 发的消息无处投递、
     直接丢弃且没有任何报错。故一并改为永不过期。
  2. 四条 Cloud Scheduler Job（O6），逐字对齐 T5 手册模板，建于 asia-northeast1
     （本项目既有 telemarket_out_reach_push 亦在此）。

**Job 可以分布在多个 location**：本项目在 asia-southeast1 还有一批（数仓 push 系列 +
运维早前建的三条调度 Job）。曾误以为「Scheduler location 在项目内唯一」，导致只查
asia-northeast1、把运维那三条当成了来源不明的发布者，绕了一整轮。排查发布者务必按
gcloud scheduler locations list 逐 location 扫，别只看一个。

命名用**生产名**而非 -pilot 后缀：topic 与订阅本就是生产名，只有 Job 缺失。若运维日后
重复创建，生产名会以 ALREADY_EXISTS 显性失败；而 -pilot 并存则是两套 Job 同时向一个
topic 发 tick，静默双发、只能靠指标异常事后发现——显性失败是更安全的失败方式。

归属（2026-08-21 定）：运维已给 keliu 账号开通 Scheduler 权限，**四条 Job 由我方持有**；
运维在 asia-southeast1 的三条同功能 Job 已 PAUSED（attributes 为空、job 写在 body 里，
应用判 UNKNOWN_JOB 丢弃；其 dailyRoll 还用 */5 3-5 从 03:00 起跑，早于 03:35 缓冲）。
待运维确认无其他用途后删除。--delete-jobs 保留给「归属回退给运维」的场景。

用法：
  python3 scripts/test/provision-scheduler.py --dry-run            # 只打印将执行的动作
  python3 scripts/test/provision-scheduler.py                      # 执行
  python3 scripts/test/provision-scheduler.py --verify             # 打印证据（归档进 T5 手册 §8）
  python3 scripts/test/provision-scheduler.py --pause              # 临时停掉四条 Job（可恢复）
  python3 scripts/test/provision-scheduler.py --delete-jobs        # 撤除重叠的两条每分钟 Job
  python3 scripts/test/provision-scheduler.py --delete-jobs --all  # 连 dailyRoll 两条一并撤除
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
LOCATION = "asia-northeast1"
SCHEDULE_TOPIC = "intelligent-collection-schedule-v1"
SCHEDULE_SUB = "intelligent-collection-schedule-v1-sub"
CASES_SUB = "intelligent-collection-cases-v1-sub"

ACK_DEADLINE_SECONDS = 60
RETENTION = "600s"  # 10m：调度 tick 过期即无业务价值，长保留只会在重启后堆积陈旧消息

# 期望参数。案件订阅的保留期不动（按数仓契约 7 天，用于 replay）；只纠 ack 与过期策略。
SUBSCRIPTION_TARGETS = {
    SCHEDULE_SUB: {"ackDeadlineSeconds": ACK_DEADLINE_SECONDS, "messageRetentionDuration": RETENTION},
    CASES_SUB: {"ackDeadlineSeconds": ACK_DEADLINE_SECONDS},
}
NEVER_EXPIRE: dict = {}  # expirationPolicy 置空对象 = 永不过期
TIME_ZONE = "Asia/Manila"
MESSAGE_BODY = "scheduled-tick"  # 不参与路由，路由只看 job attribute

# (Job 名, cron, job attribute)
JOBS = [
    ("collection-plan-step-due", "* * * * *", "planStepDue"),
    ("collection-callback-timeout", "* * * * *", "callbackTimeout"),
    ("collection-daily-roll-open", "35,40,45,50,55 3 * * *", "dailyRoll"),
    ("collection-daily-roll-continue", "*/5 4-5 * * *", "dailyRoll"),
]

# 与运维既有发布者重叠的两条（其 tick 只覆盖 planStepDue / callbackTimeout）
OVERLAPPING_JOBS = ["collection-plan-step-due", "collection-callback-timeout"]

PUBSUB = "https://pubsub.googleapis.com/v1"
SCHEDULER = "https://cloudscheduler.googleapis.com/v1"


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
            return {"__status": e.code, "__detail": e.read().decode()[:400]}


def job_path(name: str) -> str:
    return f"projects/{PROJECT}/locations/{LOCATION}/jobs/{name}"


def job_body(name: str, schedule: str, job_attr: str) -> dict:
    return {
        "name": job_path(name),
        "schedule": schedule,
        "timeZone": TIME_ZONE,
        "pubsubTarget": {
            "topicName": f"projects/{PROJECT}/topics/{SCHEDULE_TOPIC}",
            "data": base64.b64encode(MESSAGE_BODY.encode()).decode(),
            "attributes": {"job": job_attr},
        },
    }


def fix_subscription(api: Api, name: str) -> None:
    resource = f"{PUBSUB}/projects/{PROJECT}/subscriptions/{name}"
    current = api.call("GET", resource)
    if "__status" in current:
        print(f"  ! 读取订阅失败 {name}：{current['__status']} {current['__detail']}")
        return

    desired = dict(SUBSCRIPTION_TARGETS[name])
    drift = {k: v for k, v in desired.items() if current.get(k) != v}
    # expirationPolicy 的「永不过期」在 API 上表现为字段缺失或空对象
    if current.get("expirationPolicy"):
        drift["expirationPolicy"] = NEVER_EXPIRE

    if not drift:
        print(
            f"  = 已合规 {name}：ack={current.get('ackDeadlineSeconds')} "
            f"retention={current.get('messageRetentionDuration')} 永不过期"
        )
        return
    print(
        f"  ~ {name} 当前 ack={current.get('ackDeadlineSeconds')} "
        f"retention={current.get('messageRetentionDuration')} "
        f"expiration={current.get('expirationPolicy')} → 待改 {sorted(drift)}"
    )
    if api.dry_run:
        print(f"  + [dry-run] 将 PATCH {name}")
        return

    mask = ",".join(sorted(drift))
    body = dict(drift)
    body["name"] = f"projects/{PROJECT}/subscriptions/{name}"
    result = api.call(
        "PATCH",
        f"{resource}?updateMask={mask}",
        {"subscription": body, "updateMask": mask},
    )
    if "__status" in result:
        print(f"  ! PATCH 失败 {name}：{result['__status']} {result['__detail']}")
    else:
        print(
            f"  + 已纠偏 {name}：ack={result.get('ackDeadlineSeconds')} "
            f"retention={result.get('messageRetentionDuration')} "
            f"expiration={result.get('expirationPolicy') or '永不过期'}"
        )


def create_jobs(api: Api) -> None:
    for name, schedule, job_attr in JOBS:
        url = f"{SCHEDULER}/{job_path(name)}"
        existing = api.call("GET", url)
        if "__status" not in existing:
            target = existing.get("pubsubTarget", {})
            print(
                f"  = 已存在，跳过：{name}（schedule={existing.get('schedule')} "
                f"tz={existing.get('timeZone')} attrs={target.get('attributes')}）"
            )
            continue
        body = job_body(name, schedule, job_attr)
        if api.dry_run:
            print(f"  + [dry-run] 将创建：{name} schedule='{schedule}' attrs=job={job_attr}")
            continue
        result = api.call(
            "POST",
            f"{SCHEDULER}/projects/{PROJECT}/locations/{LOCATION}/jobs",
            body,
        )
        if "__status" in result:
            print(f"  ! 创建失败：{name} → {result['__status']} {result['__detail']}")
        else:
            print(f"  + 已创建：{name} state={result.get('state')}")


def delete_jobs(api: Api, only: list) -> None:
    """移交运维后撤除本脚本创建的 Job。只删 Job，两条订阅的参数保持不动。

    默认只删与运维既有发布者**重叠**的两条每分钟 Job；`dailyRoll` 两条保留，因为无证据表明
    运维侧已配（其窗口在凌晨，白天观测不到），删掉会让日终升档与 D91 停催无人触发。
    确认运维已覆盖 dailyRoll 后再用 --all 一并撤除。
    """
    targets = [j for j in JOBS if not only or j[0] in only]
    for name, _, _ in targets:
        url = f"{SCHEDULER}/{job_path(name)}"
        if "__status" in api.call("GET", url):
            print(f"  = 不存在，跳过：{name}")
            continue
        if api.dry_run:
            print(f"  - [dry-run] 将删除：{name}")
            continue
        result = api.call("DELETE", url)
        print(
            f"  - 已删除：{name}"
            if "__status" not in result
            else f"  ! 删除失败：{name} → {result['__status']} {result['__detail']}"
        )


def pause_jobs(api: Api) -> None:
    for name, _, _ in JOBS:
        if api.dry_run:
            print(f"  - [dry-run] 将暂停：{name}")
            continue
        result = api.call("POST", f"{SCHEDULER}/{job_path(name)}:pause", {})
        print(
            f"  - 已暂停：{name}"
            if "__status" not in result
            else f"  ! 暂停失败：{name} → {result['__status']}"
        )


def verify(api: Api) -> None:
    """证据输出：订阅参数与四条 Job 的关键字段，供 T5 手册 §8 归档与运维逐字段比对。"""
    for name in SUBSCRIPTION_TARGETS:
        sub = api.call("GET", f"{PUBSUB}/projects/{PROJECT}/subscriptions/{name}")
        print(f"subscription {name}")
        print(f"  topic={sub.get('topic','?').split('/')[-1]}")
        print(f"  ackDeadlineSeconds={sub.get('ackDeadlineSeconds')}")
        print(f"  messageRetentionDuration={sub.get('messageRetentionDuration')}")
        print(f"  expirationPolicy={sub.get('expirationPolicy') or '永不过期'}")
        print(f"  deadLetterPolicy={sub.get('deadLetterPolicy')}")
    for name, _, _ in JOBS:
        job = api.call("GET", f"{SCHEDULER}/{job_path(name)}")
        if "__status" in job:
            print(f"job {name}: 不存在（{job['__status']}）")
            continue
        target = job.get("pubsubTarget", {})
        print(f"job {name}")
        print(f"  schedule={job.get('schedule')!r} timeZone={job.get('timeZone')}")
        print(f"  state={job.get('state')} topic={target.get('topicName','?').split('/')[-1]}")
        print(f"  attributes={target.get('attributes')}")
        print(f"  lastAttemptTime={job.get('lastAttemptTime','-')} status={job.get('status','-')}")
    scan_all_publishers(api)


def list_jobs(api: Api, location: str) -> tuple:
    """列出某 location 的全部 Job，返回 (jobs, error)。

    **必须翻页**：Cloud Scheduler 的 List 会返回「本页 0 条但带 nextPageToken」的响应
    （asia-southeast1 实测如此）。只取首页会得出「该 location 没有 Job」的错误结论——
    这正是漏看运维那三条的直接原因。
    """
    jobs: list = []
    token = None
    while True:
        url = f"{SCHEDULER}/projects/{PROJECT}/locations/{location}/jobs?pageSize=100"
        if token:
            url += f"&pageToken={urllib.parse.quote(token)}"
        page = api.call("GET", url)
        if "__status" in page:
            return [], page["__status"]
        jobs.extend(page.get("jobs", []))
        token = page.get("nextPageToken")
        if not token:
            return jobs, None


def scan_all_publishers(api: Api) -> None:
    """逐 location 扫全项目，列出所有向调度 Topic 发 tick 的 Job。

    这一步存在的理由：Job 可分布在多个 location，只看 LOCATION 会漏掉别处的重复发布者
    （2026-08-21 就因此把 asia-southeast1 的三条误判成来源不明）。同一 job 双发布者会双发
    tick，靠指标只能事后察觉，所以每次复验都强制扫一遍。
    """
    print("--- 调度 Topic 的全部发布者（逐 location 扫）---")
    locations = api.call("GET", f"{SCHEDULER}/projects/{PROJECT}/locations")
    if "__status" in locations:
        print(f"  ! 无法列出 location（{locations['__status']}），本项跳过")
        return
    found = 0
    denied = []
    for loc in locations.get("locations", []):
        loc_id = loc.get("locationId")
        jobs, error = list_jobs(api, loc_id)
        if error:
            # 不能静默跳过：漏看一个 location 就等于漏看一个可能的重复发布者
            denied.append(f"{loc_id}({error})")
            continue
        for job in jobs:
            target = job.get("pubsubTarget", {})
            if not target.get("topicName", "").endswith(f"/{SCHEDULE_TOPIC}"):
                continue
            found += 1
            attrs = target.get("attributes")
            body = base64.b64decode(target.get("data", "") or "").decode("utf8", "replace")
            flag = "" if attrs else "  ← attributes 为空，会被判 UNKNOWN_JOB 丢弃"
            print(f"  [{loc_id}] {job['name'].split('/')[-1]} state={job.get('state')}")
            print(f"      schedule={job.get('schedule')!r} attributes={attrs}{flag}")
            if not attrs:
                print(f"      body={body!r}")
    print(
        f"  合计 {found} 条。期望的 ENABLED 集合恰好是本脚本的四条："
        "planStepDue / callbackTimeout 各一条；dailyRoll 两条但 cron 窗口不重叠"
        "（03 时段 / 04-05 时段）。任何其他 ENABLED 条目都意味着同一 job 被双发。"
    )
    if denied:
        print(f"  ! 以下 location 未能列出，结论不完整：{', '.join(denied)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的动作")
    parser.add_argument("--verify", action="store_true", help="只打印现状证据")
    parser.add_argument("--pause", action="store_true", help="暂停四条 Job（不删除）")
    parser.add_argument(
        "--delete-jobs",
        action="store_true",
        help="移交运维后撤除重叠的两条每分钟 Job（订阅参数不动）",
    )
    parser.add_argument(
        "--all", action="store_true", help="配合 --delete-jobs：连 dailyRoll 两条一并撤除"
    )
    args = parser.parse_args()

    api = Api(access_token(), args.dry_run)
    print(f"project={PROJECT} location={LOCATION} dry_run={args.dry_run}")
    if args.verify:
        verify(api)
        return
    if args.pause:
        print("[1/1] 暂停 Job")
        pause_jobs(api)
        return
    if args.delete_jobs:
        only = [] if args.all else OVERLAPPING_JOBS
        print(
            "[1/1] 撤除 Job（移交运维）："
            + ("全部四条" if args.all else "仅与运维发布者重叠的两条每分钟 Job")
        )
        delete_jobs(api, only)
        return
    print("[1/2] 订阅参数纠偏（调度 O2/O5 + 案件接入 ack 对齐）")
    for name in SUBSCRIPTION_TARGETS:
        fix_subscription(api, name)
    print("[2/2] 四条 Cloud Scheduler Job（O6）")
    create_jobs(api)


if __name__ == "__main__":
    main()
