#!/usr/bin/env bash
# 把 14:30 波次的观测排到后台，采三次样：起批后 1 分钟、5 分钟、15 分钟。
# 用 nohup 落在 Pilot 上跑，本地终端断开也不影响取证。
set -eu

OUT=/tmp/wave1430-watch.log

pkill -f wave1430-runner.sh 2>/dev/null || true

cat > /tmp/wave1430-runner.sh <<'RUNNER'
#!/usr/bin/env bash
# 宿主机是 UTC，业务时刻是 PHT；不显式设 TZ 会把 14:31 当成 UTC 等到晚上。
export TZ=Asia/Manila
OUT=/tmp/wave1430-watch.log
: > "$OUT"

wait_until() {
  local target="$1"
  local now_s target_s
  target_s=$(date -d "$target" +%s)
  now_s=$(date +%s)
  if [ "$target_s" -gt "$now_s" ]; then
    sleep $((target_s - now_s))
  fi
}

for t in "14:31" "14:35" "14:45"; do
  wait_until "$t"
  {
    echo "################ 采样 $t（$(date '+%F %T %Z')）################"
    python3 /tmp/watch-wave1430.py 2>&1
    echo
  } >> "$OUT"
done
echo "DONE $(date '+%F %T')" >> "$OUT"
RUNNER

chmod +x /tmp/wave1430-runner.sh
nohup /tmp/wave1430-runner.sh > /dev/null 2>&1 &
echo "已排程，输出写入 $OUT"
date '+now=%F %T %Z'
