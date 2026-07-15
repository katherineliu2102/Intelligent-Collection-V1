"""Validate dashboard data/logic consistency."""
import os, urllib.parse, urllib.request
from pathlib import Path
import pymysql

ROOT = Path(__file__).resolve().parents[2]
for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line: continue
    k, v = line.split("=", 1); os.environ.setdefault(k.strip(), v.strip())

params = urllib.parse.urlencode({
    "dataId": "intelligent-collection-local.yml",
    "group": os.environ["NACOS_GROUP"],
    "tenant": os.environ["NACOS_NAMESPACE"],
    "username": os.environ["NACOS_USERNAME"],
    "password": os.environ["NACOS_PASSWORD"],
})
yaml = urllib.request.urlopen(
    f"http://{os.environ['NACOS_SERVER_ADDR']}/nacos/v1/cs/configs?{params}", timeout=15
).read().decode("utf-8")
host = port = db = user = password = None
for line in yaml.splitlines():
    s = line.strip()
    if s.startswith("url:") and "jdbc:mysql://" in s:
        body = s.split("url:", 1)[1].strip().split("jdbc:mysql://", 1)[1]
        hp, dbp = body.split("/", 1); host, port = hp.split(":", 1); db = dbp.split("?", 1)[0]
    elif s.startswith("username:"): user = s.split(":", 1)[1].strip()
    elif s.startswith("password:"): password = s.split(":", 1)[1].strip()

conn = pymysql.connect(host=host, port=int(port), user=user, password=password, database=db, connect_timeout=15)
cur = conn.cursor()

print("=== 1. timeline result 全集（近7日 OUT）===")
cur.execute("""
SELECT COALESCE(result,'NULL') r, COUNT(*) c
FROM t_contact_timeline
WHERE direction='OUT' AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY result ORDER BY c DESC
""")
for r in cur.fetchall():
    print(f"  {r[0]}: {r[1]}")

print("\n=== 2. sent vs delivered+failed+skipped 是否闭合 ===")
cur.execute("""
SELECT channel,
  COUNT(*) sent,
  SUM(result IN ('DELIVERED','SENT','ACCEPTED')) delivered,
  SUM(result IN ('FAILED','REJECTED','BOUNCED')) failed,
  SUM(result='SKIPPED') skipped,
  COUNT(*) - SUM(result IN ('DELIVERED','SENT','ACCEPTED'))
    - SUM(result IN ('FAILED','REJECTED','BOUNCED'))
    - SUM(result='SKIPPED') other
FROM t_contact_timeline
WHERE direction='OUT' AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY channel
""")
for r in cur.fetchall():
    print(f"  {r[0]}: sent={r[1]} del={r[2]} fail={r[3]} skip={r[4]} other={r[5]}")

print("\n=== 3. plan_id 为空导致 Stage=UNKNOWN ===")
cur.execute("""
SELECT COUNT(*) FROM t_contact_timeline t
LEFT JOIN t_contact_plan p ON p.id=t.plan_id
WHERE t.direction='OUT' AND t.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
AND COALESCE(p.stage,'UNKNOWN')='UNKNOWN'
""")
print(f"  UNKNOWN stage rows: {cur.fetchone()[0]}")

print("\n=== 4. template_id 无 script_template 映射 ===")
cur.execute("""
SELECT t.template_id, COUNT(*) c
FROM t_contact_timeline t
LEFT JOIN t_script_template st ON st.id=t.template_id
WHERE t.direction='OUT' AND t.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
AND st.id IS NULL
GROUP BY t.template_id
""")
rows = cur.fetchall()
print(f"  unmapped template groups: {len(rows)}")
for r in rows:
    print(f"    template_id={r[0]} count={r[1]}")

print("\n=== 5. 测试案件 DPD 漂移检查 ===")
cur.execute("SELECT loan_id, overdue_days, id FROM t_collection WHERE id LIKE 'IC_TEST_%' ORDER BY loan_id")
for r in cur.fetchall():
    print(f"  case={r[0]} dpd={r[1]} id={r[2]}")

print("\n=== 6. STEP_EXECUTING 残留 ===")
cur.execute("SELECT COUNT(*) FROM t_contact_plan WHERE status='STEP_EXECUTING'")
print(f"  stuck plans: {cur.fetchone()[0]}")

conn.close()
