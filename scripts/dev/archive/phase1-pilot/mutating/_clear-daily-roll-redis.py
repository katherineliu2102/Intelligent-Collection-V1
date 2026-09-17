#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Pilot: clear today's daily-roll completed/cursor keys so a manual tick can rescan."""
import os
import redis

host = os.environ["COLLECTION_REDIS_HOST"]
port = int(os.environ.get("COLLECTION_REDIS_PORT") or 6379)
db = int(os.environ.get("COLLECTION_REDIS_DB") or 0)
pw = os.environ.get("COLLECTION_REDIS_PASSWORD") or None
ssl = str(os.environ.get("COLLECTION_REDIS_SSL", "false")).lower() == "true"
r = redis.Redis(host=host, port=port, db=db, password=pw, ssl=ssl, socket_timeout=5)
keys = sorted(k.decode() for k in r.keys("collection:ingestion:daily-roll:*"))
print("daily-roll keys:", keys)
for k in keys:
    print(" ", k, "=", r.get(k))
deleted = r.delete(
    "collection:ingestion:daily-roll:2026-08-31:completed",
    "collection:ingestion:daily-roll:2026-08-31:cursor",
)
print("deleted", deleted)
print("remaining", [k.decode() for k in r.keys("collection:ingestion:daily-roll:*")])
