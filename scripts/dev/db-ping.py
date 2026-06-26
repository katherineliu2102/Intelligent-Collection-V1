import os
import sys

import pymysql

host = os.environ["DB_HOST"]
port = int(os.environ["DB_PORT"])
user = os.environ["DB_USER"]
password = os.environ["DB_PASS"]
database = os.environ.get("DB_NAME")

try:
    conn = pymysql.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        database=database,
        connect_timeout=10,
    )
except pymysql.err.OperationalError as e:
    print(f"CONNECT_FAIL errno={e.args[0]} msg={e.args[1]}")
    sys.exit(1)

try:
    with conn.cursor() as cur:
        cur.execute("SELECT DATABASE()")
        db = cur.fetchone()[0]
        cur.execute("SHOW TABLES LIKE 't_contact_plan'")
        has_plan = cur.fetchone() is not None
    print(f"CONNECT_OK database={db} t_contact_plan={'YES' if has_plan else 'NO'}")
finally:
    conn.close()
