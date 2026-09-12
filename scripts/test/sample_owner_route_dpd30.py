# -*- coding: utf-8 -*-
"""Owner 路由分流样本：保留 e2e200 中 DPD<=30，再新抽约 200，合并输出。"""
from pathlib import Path
import csv
import os
from collections import Counter, defaultdict

from dotenv import load_dotenv
from google.api_core.client_options import ClientOptions
from google.cloud import bigquery

load_dotenv(Path(r"d:/AI/gcloud") / ".env", override=False)
DATA = os.getenv("BQ_DATA_PROJECT", "fintech-all")
JOB = os.getenv("BQ_JOB_PROJECT", DATA)
QUOTA = os.getenv("BQ_QUOTA_PROJECT", JOB)
client = bigquery.Client(
    project=JOB,
    client_options=ClientOptions(quota_project_id=QUOTA),
    location="asia-southeast1",
)

OUT_DIR = Path(r"d:/AI/Intelligent-Collection-V1/docs/testing")
E2E200 = OUT_DIR / "records" / "e2e200_loans_20260829.csv"
E2E50 = OUT_DIR / "e2e50_loans_20260820.csv"

L1_LOANS = {
    517235,
    517379,
    523046,
    522830,
    522791,
    522778,
    522841,
    522758,
}

# 新抽配额：S0~S3，合计 200；精确关键日优先，空池同阶段回填
NEW_BUCKETS = [
    # bucket, preferred_today_dpd_list, n, stage
    ("S0_D-3", [-4, -5, -3], 20, "S0"),
    ("S0_D-2", [-3, -4, -2], 12, "S0"),
    ("S0_D-1", [-2, -3, -1], 12, "S0"),
    ("S0_D0", [-1, -2, 0], 20, "S0"),
    ("S1_D+1", [0, 1], 24, "S1"),
    ("S1_D+2", [1, 2], 20, "S1"),
    ("S2_D+4", [3, 4, 5], 24, "S2"),
    ("S2_D+7", [6, 7, 8], 20, "S2"),
    ("S3_D+16", [15, 16, 14], 24, "S3"),
    ("S3_D+23", [22, 23, 21, 24], 24, "S3"),
]


def amount_band(overdue, upcoming):
    v = overdue if overdue and overdue > 0 else upcoming
    if v is None:
        return "unknown"
    if v < 2000:
        return "low"
    if v < 8000:
        return "mid"
    return "high"


def product_type(product_id):
    if product_id in (3, 4):
        return "3installment"
    return "1installment"


def stage_of(dpd):
    if dpd is None:
        return None
    if dpd <= 0:
        return "S0"
    if dpd <= 3:
        return "S1"
    if dpd <= 15:
        return "S2"
    if dpd <= 30:
        return "S3"
    return "S4+"


def intended_open_dpd(row):
    """开测日目标 DPD：优先 tomorrow_dpd，否则 today+1。"""
    raw = row.get("tomorrow_dpd") or ""
    if raw in (">=91",):
        return 999
    try:
        return int(float(raw))
    except (TypeError, ValueError):
        try:
            return int(row["today_dpd"]) + 1
        except (TypeError, ValueError, KeyError):
            return 999


# ---------- 1) 保留 e2e200 中 DPD<=30 ----------
e2e200_rows = list(csv.DictReader(E2E200.open(encoding="utf-8-sig")))
all_e2e200_ids = {int(r["loan_id"]) for r in e2e200_rows}
retained = []
dropped = []
for r in e2e200_rows:
    open_dpd = intended_open_dpd(r)
    if open_dpd <= 30:
        retained.append(r)
    else:
        dropped.append(r)

print(f"e2e200 total={len(e2e200_rows)} retain_dpd_le_30={len(retained)} drop={len(dropped)}")
print("drop buckets", Counter(r["bucket"] for r in dropped))

# e2e50 ids
e2e50_ids = set()
if E2E50.exists():
    e2e50_ids = {int(r["loan_id"]) for r in csv.DictReader(E2E50.open(encoding="utf-8-sig"))}

exclude = sorted(all_e2e200_ids | e2e50_ids | L1_LOANS)
print("exclude_count", len(exclude))

# ---------- 2) BQ：当前未结清且 max_dpd in [-3,30] 的候选 ----------
sql = f"""
WITH asof AS (SELECT CURRENT_DATE('Asia/Manila') AS d),
bills AS (
  SELECT
    p.loan_id,
    p.user_id,
    p.product_id,
    DATE_DIFF((SELECT d FROM asof), DATE(p.due_date), DAY) AS bill_dpd,
    GREATEST(COALESCE(p.amount, 0) - COALESCE(p.paid_amount, 0), 0) AS residual
  FROM `{DATA}.detail.t_loan_repayment_plan` p
  WHERE p.clear_time IS NULL
    AND (COALESCE(p.amount, 0) - COALESCE(p.paid_amount, 0)) > 0
    AND p.loan_id NOT IN UNNEST({exclude})
),
loan AS (
  SELECT
    loan_id,
    ANY_VALUE(user_id) AS user_id,
    ANY_VALUE(product_id) AS product_id,
    MAX(bill_dpd) AS max_dpd,
    SUM(IF(bill_dpd >= 0, residual, 0)) AS overdue_amount,
    SUM(IF(bill_dpd BETWEEN -3 AND 0, residual, 0)) AS upcoming_amount,
    COUNT(*) AS uncleared_bills
  FROM bills
  GROUP BY loan_id
)
SELECT
  loan_id, user_id, product_id, max_dpd, overdue_amount, upcoming_amount,
  uncleared_bills, (SELECT d FROM asof) AS as_of_date
FROM loan
WHERE max_dpd BETWEEN -5 AND 30
QUALIFY ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY ABS(max_dpd), loan_id) = 1
"""

print("querying BigQuery…")
rows = list(client.query(sql).result())
as_of = str(rows[0].as_of_date) if rows else None
print("candidates", len(rows), "as_of", as_of)

by_dpd = defaultdict(list)
for r in rows:
    pid = int(r.product_id) if r.product_id is not None else 0
    rec = {
        "loan_id": int(r.loan_id),
        "user_id": int(r.user_id),
        "product_id": pid,
        "product": product_type(pid),
        "today_dpd": int(r.max_dpd),
        "overdue_amount": float(r.overdue_amount or 0),
        "upcoming_amount": float(r.upcoming_amount or 0),
        "uncleared_bills": int(r.uncleared_bills),
        "as_of_date": str(r.as_of_date),
        "band": amount_band(float(r.overdue_amount or 0), float(r.upcoming_amount or 0)),
        "stage": stage_of(int(r.max_dpd)),
    }
    by_dpd[rec["today_dpd"]].append(rec)

# 校验保留案今日状态
retain_ids = [int(r["loan_id"]) for r in retained]
sql_chk = f"""
WITH asof AS (SELECT CURRENT_DATE('Asia/Manila') AS d),
bills AS (
  SELECT p.loan_id, p.user_id, p.product_id,
    DATE_DIFF((SELECT d FROM asof), DATE(p.due_date), DAY) AS bill_dpd,
    GREATEST(COALESCE(p.amount,0)-COALESCE(p.paid_amount,0),0) AS residual
  FROM `{DATA}.detail.t_loan_repayment_plan` p
  WHERE p.loan_id IN UNNEST({retain_ids})
),
loan AS (
  SELECT loan_id, ANY_VALUE(user_id) user_id, ANY_VALUE(product_id) product_id,
    MAX(IF(clear_time IS NULL AND residual > 0, bill_dpd, NULL)) AS max_dpd_open,
    SUM(IF(clear_time IS NULL AND residual > 0 AND bill_dpd >= 0, residual, 0)) AS overdue_amount,
    COUNTIF(clear_time IS NULL AND residual > 0) AS uncleared_bills
  FROM (
    SELECT p.loan_id, p.user_id, p.product_id, p.clear_time,
      DATE_DIFF((SELECT d FROM asof), DATE(p.due_date), DAY) AS bill_dpd,
      GREATEST(COALESCE(p.amount,0)-COALESCE(p.paid_amount,0),0) AS residual
    FROM `{DATA}.detail.t_loan_repayment_plan` p
    WHERE p.loan_id IN UNNEST({retain_ids})
  )
  GROUP BY loan_id
)
SELECT loan_id, max_dpd_open, uncleared_bills, overdue_amount, (SELECT d FROM asof) AS as_of_date
FROM loan
"""
# rewrite simpler check
sql_chk = f"""
WITH asof AS (SELECT CURRENT_DATE('Asia/Manila') AS d),
bills AS (
  SELECT
    p.loan_id,
    DATE_DIFF((SELECT d FROM asof), DATE(p.due_date), DAY) AS bill_dpd,
    GREATEST(COALESCE(p.amount,0)-COALESCE(p.paid_amount,0),0) AS residual
  FROM `{DATA}.detail.t_loan_repayment_plan` p
  WHERE p.loan_id IN UNNEST({retain_ids})
    AND p.clear_time IS NULL
    AND (COALESCE(p.amount,0)-COALESCE(p.paid_amount,0)) > 0
),
loan AS (
  SELECT loan_id, MAX(bill_dpd) AS max_dpd, SUM(IF(bill_dpd>=0, residual, 0)) AS overdue_amount,
    COUNT(*) AS uncleared_bills
  FROM bills GROUP BY loan_id
)
SELECT loan_id, max_dpd, overdue_amount, uncleared_bills FROM loan
"""
print("checking retained live status…")
live = {int(r.loan_id): r for r in client.query(sql_chk).result()}
retained_live_ok = []
retained_stale = []
for r in retained:
    lid = int(r["loan_id"])
    info = live.get(lid)
    if info is None:
        retained_stale.append((lid, "SETTLED_OR_GONE", None))
        continue
    dpd = int(info.max_dpd)
    if dpd > 30:
        retained_stale.append((lid, "DPD_GT_30", dpd))
        continue
    if dpd < -3:
        # still pre-window; keep but flag
        retained_stale.append((lid, "DPD_LT_NEG3", dpd))
        continue
    nr = dict(r)
    nr["live_as_of"] = as_of
    nr["live_dpd"] = dpd
    nr["live_overdue_amount"] = float(info.overdue_amount or 0)
    nr["source"] = "e2e200_retained"
    retained_live_ok.append(nr)

print(
    f"retained_live_ok={len(retained_live_ok)} stale/out={len(retained_stale)} "
    f"reasons={Counter(x[1] for x in retained_stale)}"
)

# ---------- 3) 新抽 200 ----------
def pick(pool, n, used_loans, used_users, force_product=None):
    avail = [
        r
        for r in pool
        if r["loan_id"] not in used_loans and r["user_id"] not in used_users
    ]
    if force_product:
        forced = [r for r in avail if r["product"] == force_product]
        if forced:
            avail = forced
    avail.sort(key=lambda r: (r["product"], r["band"], r["loan_id"]))
    one = [r for r in avail if r["product"] == "1installment"]
    three = [r for r in avail if r["product"] == "3installment"]
    target_three = n // 2
    target_one = n - target_three
    chosen = []

    def take(src, k):
        out = []
        bands_seen = set()
        rest = []
        for r in src:
            if r["band"] not in bands_seen and len(out) < k:
                out.append(r)
                bands_seen.add(r["band"])
            else:
                rest.append(r)
        for r in rest:
            if len(out) >= k:
                break
            out.append(r)
        return out

    chosen.extend(take(three, target_three))
    chosen.extend(take(one, target_one))
    leftover = [r for r in avail if r not in chosen]
    for r in leftover:
        if len(chosen) >= n:
            break
        chosen.append(r)
    return chosen[:n]


used_loans = {int(r["loan_id"]) for r in retained_live_ok}
used_users = {int(r["user_id"]) for r in retained_live_ok}
# also exclude users from stale retained to avoid same user
used_users |= {int(r["user_id"]) for r in retained}
used_loans |= all_e2e200_ids | e2e50_ids | L1_LOANS

new_picked = []
print("\n=== new sample inventory ===")
for name, dpd_prefs, n, stage in NEW_BUCKETS:
    pool = []
    used_fb = False
    for d in dpd_prefs:
        if not pool:
            pool = list(by_dpd.get(d, []))
            if pool and d != dpd_prefs[0]:
                used_fb = True
        else:
            break
    # if still empty, widen within stage
    if not pool:
        used_fb = True
        stage_range = {
            "S0": range(-5, 1),
            "S1": range(1, 4),
            "S2": range(4, 16),
            "S3": range(16, 31),
        }[stage]
        for d in stage_range:
            pool.extend(by_dpd.get(d, []))
    print(f"{name:12s} need={n:2d} pool={len(pool):4d} fb={used_fb}")
    sel = pick(pool, n, used_loans, used_users)
    for r in sel:
        r = dict(r)
        r["bucket"] = name
        r["tomorrow_dpd"] = int(r["today_dpd"]) + 1
        r["intended_tomorrow_dpd"] = (
            dpd_prefs[0] + 1 if isinstance(dpd_prefs[0], int) else r["tomorrow_dpd"]
        )
        r["fallback"] = used_fb or int(r["today_dpd"]) != dpd_prefs[0]
        r["source"] = "new_20260903"
        r["live_as_of"] = as_of
        r["live_dpd"] = r["today_dpd"]
        r["live_overdue_amount"] = r["overdue_amount"]
        new_picked.append(r)
        used_loans.add(r["loan_id"])
        used_users.add(r["user_id"])
    if len(sel) < n:
        print(f"  SHORT {name}: got {len(sel)}/{n}")

print("new_picked", len(new_picked), Counter(r["bucket"] for r in new_picked))
print("new product", Counter(r["product"] for r in new_picked))

# ---------- 4) 合并输出 ----------
combined = []
for r in retained_live_ok:
    combined.append(
        {
            "loan_id": int(r["loan_id"]),
            "user_id": int(r["user_id"]),
            "source": "e2e200_retained",
            "bucket": r["bucket"],
            "sample_as_of": r.get("as_of_date"),
            "sample_today_dpd": r.get("today_dpd"),
            "sample_tomorrow_dpd": r.get("tomorrow_dpd"),
            "live_as_of": r.get("live_as_of"),
            "live_dpd": r.get("live_dpd"),
            "stage": stage_of(int(r["live_dpd"])),
            "product": r.get("product"),
            "product_id": r.get("product_id"),
            "overdue_amount": round(float(r.get("live_overdue_amount") or r.get("overdue_amount") or 0), 2),
            "upcoming_amount": round(float(r.get("upcoming_amount") or 0), 2),
            "amount_band": r.get("amount_band")
            or amount_band(float(r.get("overdue_amount") or 0), float(r.get("upcoming_amount") or 0)),
            "fallback": False,
        }
    )

for r in new_picked:
    combined.append(
        {
            "loan_id": r["loan_id"],
            "user_id": r["user_id"],
            "source": "new_20260903",
            "bucket": r["bucket"],
            "sample_as_of": r["as_of_date"],
            "sample_today_dpd": r["today_dpd"],
            "sample_tomorrow_dpd": r["tomorrow_dpd"],
            "live_as_of": r["live_as_of"],
            "live_dpd": r["live_dpd"],
            "stage": r["stage"],
            "product": r["product"],
            "product_id": r["product_id"],
            "overdue_amount": round(r["overdue_amount"], 2),
            "upcoming_amount": round(r["upcoming_amount"], 2),
            "amount_band": r["band"],
            "fallback": r["fallback"],
        }
    )

combined.sort(key=lambda r: (r["source"], r["bucket"], r["loan_id"]))
assert len({r["loan_id"] for r in combined}) == len(combined)

OUT_IDS = OUT_DIR / "records" / "owner_route_dpd30_loan_ids_20260903.csv"
OUT_FULL = OUT_DIR / "records" / "owner_route_dpd30_loans_20260903.csv"
OUT_DROPPED = OUT_DIR / "records" / "owner_route_e2e200_dropped_gt30_20260903.csv"

with OUT_IDS.open("w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["loan_id"])
    for r in combined:
        w.writerow([r["loan_id"]])

fields = list(combined[0].keys())
with OUT_FULL.open("w", newline="", encoding="utf-8-sig") as f:
    w = csv.DictWriter(f, fieldnames=fields)
    w.writeheader()
    w.writerows(combined)

with OUT_DROPPED.open("w", newline="", encoding="utf-8-sig") as f:
    w = csv.DictWriter(
        f,
        fieldnames=["loan_id", "user_id", "bucket", "today_dpd", "tomorrow_dpd", "drop_reason"],
    )
    w.writeheader()
    for r in dropped:
        w.writerow(
            {
                "loan_id": r["loan_id"],
                "user_id": r["user_id"],
                "bucket": r["bucket"],
                "today_dpd": r["today_dpd"],
                "tomorrow_dpd": r["tomorrow_dpd"],
                "drop_reason": "sample_open_dpd_gt_30",
            }
        )
    for lid, reason, dpd in retained_stale:
        orig = next(x for x in retained if int(x["loan_id"]) == lid)
        w.writerow(
            {
                "loan_id": lid,
                "user_id": orig["user_id"],
                "bucket": orig["bucket"],
                "today_dpd": orig["today_dpd"],
                "tomorrow_dpd": orig["tomorrow_dpd"],
                "drop_reason": f"live_{reason}" + (f"_dpd{dpd}" if dpd is not None else ""),
            }
        )

print("\nwrote", OUT_IDS, "n=", len(combined))
print("wrote", OUT_FULL)
print("wrote", OUT_DROPPED)
print("combined source", Counter(r["source"] for r in combined))
print("combined stage", Counter(r["stage"] for r in combined))
print("combined product", Counter(r["product"] for r in combined))
print(
    "retained_from_file",
    len(retained),
    "live_ok",
    len(retained_live_ok),
    "new",
    len(new_picked),
    "total",
    len(combined),
)
