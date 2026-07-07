"""Apply db/schema-admin.sql to ai_collection_db.

Usage (PowerShell):
  $env:DB_PASSWORD='<from Nacos spring.datasource.password>'
  python scripts/dev/apply-schema-admin.py
"""
import os
import re
import sys
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
SQL_FILE = ROOT / "db" / "schema-admin.sql"


def load_statements(text: str) -> list[str]:
    """Split SQL file; keep DELIMITER ... // blocks intact."""
    statements: list[str] = []
    normal_buf: list[str] = []
    proc_buf: list[str] | None = None
    proc_delim = "//"

    for line in text.splitlines():
        stripped = line.strip()
        if stripped.upper().startswith("DELIMITER "):
            if normal_buf:
                chunk = "\n".join(normal_buf).strip()
                if chunk:
                    statements.extend(split_semicolon_statements(chunk))
                normal_buf = []
            proc_delim = stripped.split()[1]
            proc_buf = []
            continue
        if proc_buf is not None:
            proc_buf.append(line)
            if stripped.endswith(proc_delim):
                body = "\n".join(proc_buf).strip()
                if body.endswith(proc_delim):
                    body = body[: body.rfind(proc_delim)].strip()
                if body:
                    statements.append(body)
                proc_buf = None
            continue
        normal_buf.append(line)

    if normal_buf:
        chunk = "\n".join(normal_buf).strip()
        if chunk:
            statements.extend(split_semicolon_statements(chunk))
    return [s for s in statements if s and not s.lstrip().startswith("--")]


def split_semicolon_statements(chunk: str) -> list[str]:
    parts = []
    for piece in re.split(r";\s*\n", chunk):
        piece = piece.strip()
        if piece:
            parts.append(piece)
    return parts


def main():
    password = os.environ.get("DB_PASSWORD")
    if not password:
        print("Set DB_PASSWORD (from Nacos intelligent-collection-local.yml)", file=sys.stderr)
        sys.exit(1)
    host = os.environ.get("DB_HOST", "34.124.218.94")
    user = os.environ.get("DB_USER", "ai_collection")
    sql = SQL_FILE.read_text(encoding="utf-8")
    conn = pymysql.connect(
        host=host, port=3306, user=user, password=password,
        database="ai_collection_db", connect_timeout=20,
    )
    conn.autocommit(True)
    cur = conn.cursor()
    for i, stmt in enumerate(load_statements(sql)):
        try:
            cur.execute(stmt)
        except Exception as e:
            print(f"FAIL block {i}: {type(e).__name__}: {e}", file=sys.stderr)
            print(stmt[:400], file=sys.stderr)
            sys.exit(1)
    cur.execute("SHOW TABLES")
    tables = sorted(r[0] for r in cur.fetchall())
    required = {
        "t_contact_plan_template", "t_script_template", "t_strategy_rule",
        "t_compliance_rule", "t_channel_config", "t_config_change_log",
        "t_admin_case_freeze", "t_ops_exception",
    }
    missing = required - set(tables)
    if missing:
        print("MISSING_TABLES", sorted(missing), file=sys.stderr)
        sys.exit(1)
    cur.execute("SHOW COLUMNS FROM t_contact_plan_step LIKE 'config_version'")
    if not cur.fetchone():
        print("MISSING snapshot column on t_contact_plan_step", file=sys.stderr)
        sys.exit(1)
    print("SCHEMA_ADMIN_OK", len(tables), "tables")
    conn.close()


if __name__ == "__main__":
    main()
