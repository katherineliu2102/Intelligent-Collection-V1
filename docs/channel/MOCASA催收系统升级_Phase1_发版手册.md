# 催收系统升级 — Pilot 发版手册

> 环境：`ubuntu@34.87.136.20`（`bdp01`）  
> 对外域名：`https://collection-admin.mocasa.com`  
> 容器：`collection-admin`，镜像：`intelligent-collection-admin:pilot`  
> 日期：2026-08-24

---

## 0. 先记住

| 窗口 | 做什么 |
|---|---|
| **本机 Git Bash** | 编译、`scp` |
| **SSH 服务器** | 换 jar、打镜像、用 `pilot-run.sh` 起容器 |

- 不要在服务器上 `scp collection-admin/target/...`（那边没有源码）。
- 不要 `docker run --env-file`（会把 JDBC/Nacos 搞乱 → 502）。
- 不要 `docker logs -f` 挂着不管（窗口像死机；看完 **Ctrl+C**，容器还在）。
- Dockerfile 拷的是 **`collection-admin/target/collection-admin.jar`**，不是 `/opt/app/build/collection-admin.jar` 这一层。

---

## 1. 本机

```bash
cd /d/AI/Intelligent-Collection-V1
git checkout <发版分支>
taskkill //IM java.exe //F          # jar 被占用导致 clean 失败时

mvn -pl collection-admin -am clean package -DskipTests
ls -lh collection-admin/target/collection-admin.jar   # 约 80–90MB

scp collection-admin/target/collection-admin.jar ubuntu@34.87.136.20:/tmp/collection-admin.jar
```

---

## 2. 服务器

```bash
ssh ubuntu@34.87.136.20

# 备份 + 放到 Dockerfile 真正 COPY 的路径
mkdir -p /opt/app/build/collection-admin/target
cp /opt/app/build/collection-admin/target/collection-admin.jar \
   /opt/app/build/collection-admin.jar.bak.$(date +%Y%m%d%H%M) 2>/dev/null || true
cp /tmp/collection-admin.jar /opt/app/build/collection-admin/target/collection-admin.jar
ls -lh /opt/app/build/collection-admin/target/collection-admin.jar

# 禁止走旧 COPY 缓存
cd /opt/app/build
docker build --no-cache -t intelligent-collection-admin:pilot .

# 用现成脚本起容器（会 stop/rm/run，带对的 env）
/opt/app/build/pilot-run.sh
```

`docker build` 成功标志：

- `COPY collection-admin/target/collection-admin.jar` **不是 CACHED**
- `transferring context` 约 **86MB**，不是 147B

脚本若在「等待就绪」刷日志： **Ctrl+C**（只停跟日志）。

```bash
sleep 60
docker ps --filter name=collection-admin --format 'table {{.Names}}\t{{.Status}}'
docker logs --tail 30 collection-admin
```

成功：`Up` 且时间在增长（不是每十几秒重启），有 `Tomcat started on port(s): 8080`。

```bash
curl -s -o /dev/null -w "health:%{http_code}\n" http://127.0.0.1:8080/actuator/health
curl -s -o /dev/null -w "https:%{http_code}\n" https://collection-admin.mocasa.com/actuator/health
```

期望均为 **200**。

---

## 3. 回滚

```bash
cp /opt/app/build/collection-admin.jar.bak.YYYYMMDDHHMM \
   /opt/app/build/collection-admin/target/collection-admin.jar
cd /opt/app/build
docker build --no-cache -t intelligent-collection-admin:pilot .
/opt/app/build/pilot-run.sh
```

---

## 4. 常见失败

| 现象 | 原因 | 处理 |
|---|---|---|
| Maven `Failed to delete ...jar` | 本机 Java 占用 jar | `taskkill //IM java.exe //F` 再编 |
| 发版后接口仍 404 | COPY 路径错 / 走了缓存 | 拷到 `collection-admin/target/` + `--no-cache` |
| health 502、容器 `Up 16 seconds` 循环 | 启动闸门失败或 JDBC | `docker logs --tail 80` 看 `Caused by`；用 `pilot-run.sh`，不要手写 `docker run` |
| `Pilot requires collection.case-service=real` | 现网是 `ai` | 闸门须同时接受 `ai`（已在后续包修复） |
| `plan_id cannot be null`（回调审计） | 表约束 | 已对 `t_channel_callback_audit.plan_id/step_id` 放宽为可空 |
| Nacos `common.yml is empty` | 启动瞬间或 env 乱 | 以是否 `Tomcat started` 为准；不要在 common 里手写 JDBC |

---

## 5. 这台机的运行口径

- Profile：`pilot`
- `COLLECTION_SCHEDULER_ENABLED=false`：**本实例只接入、不触达**
- 配置：`/opt/app/pilot.env` + Nacos `mocasa-dps` / Group `bdp`
- 库：`ai_collection_db`（与线上催收同一套）
