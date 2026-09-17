#!/usr/bin/env bash
# 探针：往开放波次集合里塞一个没有案件的波次。
#
# 只要聚合开关生效且 flusher 在跑，isDue() 会发现该波次 HLEN=0 并把它从集合里摘掉。
# 成员在 15 秒内消失 = 配置已绑定 + 定时器在转；成员还在 = 开关没生效或 flusher 没起来。
# 探针波次没有案件哈希，不会触发任何 Facade 调用。
set -eu

ENV_FILE=/opt/app/pilot.env
HOST=$(grep -E '^COLLECTION_REDIS_HOST=' "$ENV_FILE" | cut -d= -f2- | tr -d '"')
PORT=$(grep -E '^COLLECTION_REDIS_PORT=' "$ENV_FILE" | cut -d= -f2- | tr -d '"')
PASS=$(grep -E '^COLLECTION_REDIS_PASSWORD=' "$ENV_FILE" | cut -d= -f2- | tr -d '"')
DB=$(grep -E '^COLLECTION_REDIS_DB=' "$ENV_FILE" | cut -d= -f2- | tr -d '"')

R="redis-cli -h $HOST -p $PORT -a $PASS -n $DB --no-auth-warning"

echo "redis=$HOST:$PORT db=$DB"
$R SADD channel:facade:waves probe-00000000-0000#1
echo "投放后: $($R SMEMBERS channel:facade:waves)"

for i in 1 2 3; do
  sleep 5
  LEFT=$($R SMEMBERS channel:facade:waves)
  echo "第 $((i * 5)) 秒: [$LEFT]"
  if [ -z "$LEFT" ]; then
    echo 'RESULT=FLUSHER_ALIVE 聚合已生效且定时器在跑'
    exit 0
  fi
done

$R SREM channel:facade:waves probe-00000000-0000#1 > /dev/null
echo 'RESULT=NO_PICKUP 探针未被摘除，聚合开关或定时器未生效（已自行清理探针）'
exit 1
