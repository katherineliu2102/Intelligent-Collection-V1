#!/usr/bin/env bash
# 确认波次聚合配置已绑定到 ChannelProperties，以及容器内确实收到了环境变量。
set -eu

echo '=== 容器内环境变量 ==='
docker exec collection-admin env | grep -i BATCH_AGGREGATION || echo '(未注入)'

echo '=== actuator 暴露的端点 ==='
curl -s http://127.0.0.1:8080/actuator | head -c 800
echo

echo '=== configprops: channel.facade ==='
curl -s http://127.0.0.1:8080/actuator/configprops | python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
except Exception as e:
    print("configprops 不可用:", e); raise SystemExit
ctxs = d.get("contexts", d)
found = False
for ctx in ctxs.values():
    for name, bean in ctx.get("beans", {}).items():
        if "channel" == bean.get("prefix"):
            facade = bean.get("properties", {}).get("facade", {})
            print(json.dumps(facade.get("batchAggregation"), ensure_ascii=False, indent=2))
            found = True
if not found:
    print("未找到 prefix=channel 的配置 bean")'
