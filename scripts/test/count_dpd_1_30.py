# -*- coding: utf-8 -*-
"""Count uncleared loans with max_dpd in [1,30] by product type."""
from pathlib import Path
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

TABLE = f"`{DATA}.detail.t_loan_repayment_plan`"

sql = f"""
WITH asof AS (SELECT CURRENT_DATE('Asia/Manila') AS d),
bills AS (
  SELECT
    p.loan_id,
    p.user_id,
    p.product_id,
    DATE_DIFF((SELECT d FROM asof), DATE(p.due_date), DAY) AS bill_dpd
  FROM {TABLE} p
  WHERE p.clear_time IS NULL
    AND (COALESCE(p.amount, 0) - COALESCE(p.paid_amount, 0)) > 0
    AND DATE(p.due_date) BETWEEN DATE_SUB((SELECT d FROM asof), INTERVAL 60 DAY)
                             AND DATE_ADD((SELECT d FROM asof), INTERVAL 5 DAY)
),
loan AS (
  SELECT
    loan_id,
    ANY_VALUE(user_id) AS user_id,
    ANY_VALUE(product_id) AS product_id,
    MAX(bill_dpd) AS max_dpd
  FROM bills
  GROUP BY loan_id
),
in_scope AS (
  SELECT
    loan_id,
    user_id,
    product_id,
    max_dpd,
    CASE WHEN product_id IN (3, 4) THEN '3installment' ELSE '1installment' END AS product_type
  FROM loan
  WHERE max_dpd BETWEEN 1 AND 30
)
SELECT
  (SELECT d FROM asof) AS as_of_date,
  COUNT(*) AS loan_cnt,
  COUNT(DISTINCT user_id) AS user_cnt,
  COUNTIF(product_type = '1installment') AS loan_1installment,
  COUNTIF(product_type = '3installment') AS loan_3installment,
  COUNT(DISTINCT CASE WHEN product_type = '1installment' THEN user_id END) AS user_1installment,
  COUNT(DISTINCT CASE WHEN product_type = '3installment' THEN user_id END) AS user_3installment
FROM in_scope
"""

r = list(client.query(sql).result())[0]
print("=== DPD 1-30 uncleared (loan-level max_dpd) ===")
print("as_of", r.as_of_date)
print("loan_total", r.loan_cnt)
print("user_total", r.user_cnt)
print("loan_1installment", r.loan_1installment, f"({100 * r.loan_1installment / r.loan_cnt:.1f}%)")
print("loan_3installment", r.loan_3installment, f"({100 * r.loan_3installment / r.loan_cnt:.1f}%)")
print("user_1installment", r.user_1installment)
print("user_3installment", r.user_3installment)

sql2 = f"""
WITH asof AS (SELECT CURRENT_DATE('Asia/Manila') AS d),
bills AS (
  SELECT p.loan_id, p.user_id, p.product_id,
    DATE_DIFF((SELECT d FROM asof), DATE(p.due_date), DAY) AS bill_dpd
  FROM {TABLE} p
  WHERE p.clear_time IS NULL
    AND (COALESCE(p.amount, 0) - COALESCE(p.paid_amount, 0)) > 0
    AND DATE(p.due_date) BETWEEN DATE_SUB((SELECT d FROM asof), INTERVAL 60 DAY)
                             AND DATE_ADD((SELECT d FROM asof), INTERVAL 5 DAY)
),
loan AS (
  SELECT loan_id, ANY_VALUE(user_id) AS user_id, ANY_VALUE(product_id) AS product_id,
    MAX(bill_dpd) AS max_dpd
  FROM bills GROUP BY loan_id
),
in_scope AS (
  SELECT *,
    CASE WHEN product_id IN (3, 4) THEN '3installment' ELSE '1installment' END AS product_type,
    CASE
      WHEN max_dpd BETWEEN 1 AND 3 THEN 'S1_D+1~3'
      WHEN max_dpd BETWEEN 4 AND 15 THEN 'S2_D+4~15'
      WHEN max_dpd BETWEEN 16 AND 30 THEN 'S3_D+16~30'
    END AS stage_bucket
  FROM loan WHERE max_dpd BETWEEN 1 AND 30
)
SELECT stage_bucket, product_type, COUNT(*) AS loans, COUNT(DISTINCT user_id) AS users
FROM in_scope
GROUP BY 1, 2
ORDER BY 1, 2
"""
print("\n=== by stage x product ===")
for row in client.query(sql2).result():
    print(f"{row.stage_bucket:12s} {row.product_type:13s} loans={int(row.loans):4d} users={int(row.users):4d}")

total = int(r.loan_cnt)
users = int(r.user_cnt)
print("\n=== routing reference ===")
for pct in [5, 10, 15, 20, 30]:
    print(f"{pct}% -> ~{round(total * pct / 100)} loans / ~{round(users * pct / 100)} users")
