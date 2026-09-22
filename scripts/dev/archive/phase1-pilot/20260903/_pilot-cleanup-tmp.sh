#!/usr/bin/env bash
# One-shot Pilot /tmp script archive + cleanup (2026-09-03).
# Safe: no cron/service depends on /tmp/_*. MUTATING scripts are archived only, not re-run.
set -euo pipefail

STAMP=20260903
ARCHIVE_DIR=/opt/app/logs/script-archive
OPS_DIR=/opt/app/scripts/pilot
TGZ="${ARCHIVE_DIR}/pilot-tmp-${STAMP}.tgz"
MANIFEST="${ARCHIVE_DIR}/pilot-tmp-${STAMP}.manifest.txt"
KEEP_LIST="${ARCHIVE_DIR}/pilot-ops-keep-${STAMP}.txt"

echo "=== PRECHECK ==="
echo "host=$(hostname)"
echo "tmp_count=$(ls /tmp/_*.sh /tmp/_*.py 2>/dev/null | wc -l)"
du -ch /tmp/_*.sh /tmp/_*.py 2>/dev/null | tail -1 || true
crontab -l 2>/dev/null | grep -E '/tmp/_' && { echo "ABORT: cron references /tmp/_"; exit 1; } || echo "cron: no /tmp/_"
grep -n '/tmp/_' /opt/app/build/pilot-run.sh 2>/dev/null && { echo "ABORT: pilot-run references /tmp/_"; exit 1; } || echo "pilot-run: clean"
docker inspect collection-admin --format 'Cmd={{json .Config.Cmd}} Status={{.State.Status}}' 2>/dev/null || echo "container inspect skipped"

# Runtime essentials must exist
for p in /opt/app/pilot.env /opt/app/build/pilot-run.sh /opt/app/build/collection-admin.jar; do
  test -e "$p" || { echo "ABORT: missing $p"; exit 1; }
done
echo "runtime essentials: OK"

echo "=== ARCHIVE ==="
sudo mkdir -p "$ARCHIVE_DIR" "$OPS_DIR"
ls -1 /tmp/_*.sh /tmp/_*.py 2>/dev/null | sed 's|.*/||' | sort | sudo tee "$MANIFEST" >/dev/null
sudo tar czf "$TGZ" -C /tmp $(ls /tmp/_*.sh /tmp/_*.py 2>/dev/null | xargs -n1 basename)
sudo chmod 644 "$TGZ" "$MANIFEST"
ls -lh "$TGZ"
echo "manifest_lines=$(wc -l < "$MANIFEST")"
echo "tar_entries=$(tar -tzf "$TGZ" | wc -l)"

echo "=== INSTALL OPS COPIES (read-only audit helpers under /opt/app/scripts/pilot) ==="
# Keep reusable audit tools outside /tmp for ops; still prefer repo scripts/pilot as source of truth.
OPS_KEEP=(
  _pilot-daily-report.sh
  _pilot-daily-final.sh
  _pilot-noplan-rootcause.sh
  _pilot-noplan-cancel.sh
  _pilot-noplan-breakdown.sh
  _pilot-ai-failed.sh
  _pilot-ai-failed2.sh
  _pilot-firm-check.py
  _pilot-post-consume-check.py
  _pilot-schema.sh
  _pilot-s0-poison-check.py
  _pilot-env-lens.sh
  _pilot-env-lens.py
  _pilot-list-secrets.sh
  _pilot-post-deploy-check.py
  _pilot-ready-check.py
  _pilot-check-wave-cfg.sh
)
: | sudo tee "$KEEP_LIST" >/dev/null
for f in "${OPS_KEEP[@]}"; do
  if [[ -f "/tmp/$f" ]]; then
    sudo cp -a "/tmp/$f" "$OPS_DIR/$f"
    echo "$f" | sudo tee -a "$KEEP_LIST" >/dev/null
  fi
done
sudo chmod -R a+rX "$OPS_DIR" || true
echo "ops_kept=$(wc -l < "$KEEP_LIST")"
ls -1 "$OPS_DIR" | sort

echo "=== CLEAN /tmp (_*.sh / _*.py only) ==="
# Delete only underscore scripts we archived; leave unrelated /tmp alone.
DELETED=0
while IFS= read -r name; do
  rm -f "/tmp/$name"
  DELETED=$((DELETED+1))
done < "$MANIFEST"
echo "deleted=$DELETED"
echo "remaining_tmp_underscore=$(ls /tmp/_*.sh /tmp/_*.py 2>/dev/null | wc -l)"

echo "=== POSTCHECK ==="
test -f "$TGZ"
test -s "$TGZ"
test -f /opt/app/pilot.env
test -f /opt/app/build/pilot-run.sh
test -f /opt/app/build/collection-admin.jar
docker inspect collection-admin --format 'Status={{.State.Status}}' 2>/dev/null || true
echo "DONE stamp=$STAMP"
