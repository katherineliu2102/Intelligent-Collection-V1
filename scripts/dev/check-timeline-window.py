import os, urllib.parse, urllib.request
from pathlib import Path
import pymysql

ROOT = Path(__file__).resolve().parents[2]
for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    os.environ.setdefault(k.strip(), v.strip())

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
        hp, dbp = body.split("/", 1)
        host, port = hp.split(":", 1)
        db = dbp.split("?", 1)[0]
    elif s.startswith("username:"):
        user = s.split(":", 1)[1].strip()
    elif s.startswith("password:"):
        password = s.split(":", 1)[1].strip()

conn = pymysql.connect(
    host=host, port=int(port), user=user, password=password,
    database=db, connect_timeout=15,
)
cur = conn.cursor()
for d in [7, 14, 30, 90]:
    cur.execute(
        "SELECT COUNT(*) FROM t_contact_timeline "
        "WHERE direction='OUT' AND created_at>=DATE_SUB(NOW(), INTERVAL %s DAY)",
        (d,),
    )
    print(f"last {d}d OUT rows: {cur.fetchone()[0]}")
cur.execute(
    "SELECT MIN(created_at), MAX(created_at) FROM t_contact_timeline WHERE direction='OUT'"
)
print("range:", cur.fetchone())
conn.close()
