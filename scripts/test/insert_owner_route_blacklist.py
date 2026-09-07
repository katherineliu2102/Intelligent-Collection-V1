# -*- coding: utf-8 -*-
"""Insert owner-route DPD<=30 sample into risk_intelligent_collection_blacklist_case."""
from pathlib import Path
import csv
import os

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

SRC = Path(
    r"d:/AI/Intelligent-Collection-V1/docs/testing/records/owner_route_dpd30_loans_20260903.csv"
)
TABLE = f"{DATA}.risk_model.risk_intelligent_collection_blacklist_case"
OPERATOR = "Intelligent Collection System"

asof = list(client.query("SELECT CURRENT_DATE('Asia/Manila') AS d").result())[0].d
now = list(
    client.query(
        "SELECT DATETIME(CURRENT_TIMESTAMP(), 'Asia/Manila') AS t"
    ).result()
)[0].t
print("cal_dt", asof, "dw_update_time", now)

rows = list(csv.DictReader(SRC.open(encoding="utf-8-sig")))
seen = set()
payload = []
for r in rows:
    cid = int(r["loan_id"])
    if cid in seen:
        continue
    seen.add(cid)
    payload.append(
        {
            "cal_dt": asof.isoformat() if hasattr(asof, "isoformat") else str(asof),
            "case_id": cid,
            "user_id": int(r["user_id"]),
            "operator": OPERATOR,
            "status": 1,
            "dw_update_time": (
                now.strftime("%Y-%m-%d %H:%M:%S")
                if hasattr(now, "strftime")
                else str(now)[:19]
            ),
        }
    )
print("payload_n", len(payload), "sample", payload[0])

# skip if already present for same case_id
existing = {
    int(r.case_id)
    for r in client.query(
        f"SELECT DISTINCT case_id FROM `{TABLE}` WHERE case_id IS NOT NULL"
    ).result()
}
to_insert = [p for p in payload if p["case_id"] not in existing]
print("already_in_table", len(existing & seen), "to_insert", len(to_insert))

if not to_insert:
    print("nothing to insert")
else:
    job_config = bigquery.LoadJobConfig(
        schema=[
            bigquery.SchemaField("cal_dt", "DATE"),
            bigquery.SchemaField("case_id", "INTEGER"),
            bigquery.SchemaField("user_id", "INTEGER"),
            bigquery.SchemaField("operator", "STRING"),
            bigquery.SchemaField("status", "INTEGER"),
            bigquery.SchemaField("dw_update_time", "DATETIME"),
        ],
        write_disposition=bigquery.WriteDisposition.WRITE_APPEND,
    )
    load_job = client.load_table_from_json(to_insert, TABLE, job_config=job_config)
    print("load_job", load_job.job_id)
    load_job.result()
    if load_job.errors:
        print("errors", load_job.errors)
        raise SystemExit(1)
    print("load done")

t = client.get_table(TABLE)
print("num_rows_after", t.num_rows)

for r in client.query(
    f"""
    SELECT status, COUNT(*) n, COUNT(DISTINCT case_id) cases,
           MIN(cal_dt) min_dt, MAX(cal_dt) max_dt, ANY_VALUE(operator) op
    FROM `{TABLE}`
    GROUP BY status
    """
).result():
    print(dict(r))

print("peek")
for r in client.query(
    f"SELECT * FROM `{TABLE}` WHERE status=1 ORDER BY case_id LIMIT 5"
).result():
    print(dict(r))
