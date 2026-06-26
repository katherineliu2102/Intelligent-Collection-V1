"""Load db/seed/email-e2e-test-cases.sql into ai_collection_db."""
import os
import sys
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parent.parent.parent


def load_dotenv():
    env_path = ROOT / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def fetch_db_from_nacos():
    import urllib.parse
    import urllib.request

    params = urllib.parse.urlencode({
        "dataId": "intelligent-collection-local.yml",
        "group": os.environ["NACOS_GROUP"],
        "tenant": os.environ["NACOS_NAMESPACE"],
        "username": os.environ["NACOS_USERNAME"],
        "password": os.environ["NACOS_PASSWORD"],
    })
    url = f"http://{os.environ['NACOS_SERVER_ADDR']}/nacos/v1/cs/configs?{params}"
    yaml_text = urllib.request.urlopen(url, timeout=15).read().decode("utf-8")
    host = port = db = user = password = None
    for line in yaml_text.splitlines():
        s = line.strip()
        if s.startswith("url:") and "jdbc:mysql://" in s:
            part = s.split("url:", 1)[1].strip()
            # jdbc:mysql://host:port/db?...
            body = part.split("jdbc:mysql://", 1)[1]
            host_port, db_part = body.split("/", 1)
            host, port = host_port.split(":", 1)
            db = db_part.split("?", 1)[0]
        elif s.startswith("username:"):
            user = s.split(":", 1)[1].strip()
        elif s.startswith("password:"):
            password = s.split(":", 1)[1].strip()
    return host, int(port), db, user, password


def main():
    load_dotenv()
    host, port, db, user, password = fetch_db_from_nacos()
    sql_path = ROOT / "db" / "seed" / "email-e2e-test-cases.sql"
    raw = sql_path.read_text(encoding="utf-8")
    # strip line comments
    lines = []
    for line in raw.splitlines():
        if line.strip().startswith("--"):
            continue
        lines.append(line)
    blob = "\n".join(lines)
    statements = [s.strip() for s in blob.split(";") if s.strip()]

    conn = pymysql.connect(
        host=host, port=port, user=user, password=password,
        database=db, connect_timeout=15,
    )
    try:
        with conn.cursor() as cur:
            for stmt in statements:
                cur.execute(stmt)
            conn.commit()
            cur.execute("SELECT COUNT(*) FROM t_email_e2e_registry")
            print(f"registry rows: {cur.fetchone()[0]}")
            cur.execute(
                "SELECT case_id, script_slot FROM t_email_e2e_registry "
                "WHERE phase1_active=1 ORDER BY case_id"
            )
            for row in cur.fetchall():
                print(f"  ACTIVE {row[0]} {row[1]}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
