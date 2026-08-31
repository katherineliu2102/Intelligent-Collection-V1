# 催收系统升级 — Pilot 发版手册

> 环境：Pilot 机 `bdp01`；对外域名 `https://collection-admin.mocasa.com`
> 容器 `collection-admin`，镜像 `intelligent-collection-admin:pilot`
> **登录地址与账号见 `docs/ops/生产访问凭据.local.md`（已 gitignore，不入库）**，下文一律用 `$PILOT_HOST` 代指。

本手册只讲「怎么把新包发上去」。容器的启动参数、必填环境变量与就绪校验以 [`deploy/pilot-run.sh`](../../deploy/pilot-run.sh) 为准，本文不复述，避免两处口径漂移。

---

## 0. 先记住

| 窗口 | 做什么 |
|---|---|
| **本机** | 编译、`scp` |
| **SSH 服务器** | 换构建上下文、打镜像、用 `pilot-run.sh` 起容器 |

- 不要在服务器上 `mvn package`（那边没有源码）。
- 不要手写 `docker run --env-file`（会把 JDBC/Nacos 搞乱 → 502）。起容器只用 `pilot-run.sh`。
- 不要 `docker logs -f` 挂着不管（窗口像死机；看完 **Ctrl+C**，容器还在）。
- `deploy/Dockerfile` 的 `COPY` 路径是**仓库根相对**的，构建上下文必须同时具备 `collection-admin/target/collection-admin.jar` 与 `deploy/certs/`。
- **CI 与本机必须是 JDK 8**。GitHub Actions 跑 Temurin 8 + Spotless `google-java-format 1.7`（AOSP，相对 `origin/main` 增量）。本机用 11/17/21 编过不代表 CI 能过：`List.of` / `var` 会 `cannot find symbol`；在 Java 21 上跑 `spotless:apply` 会因 GJF 1.7 的 `removeUnusedImports` 直接失败。

---

## 1. 本机

本机工具（用户级环境变量，新开的 PowerShell 才看得到）：

| 项 | 值 |
|---|---|
| JDK | Temurin **8u502** `C:\Users\voghion\java\jdk8u502-b07`（`JAVA_HOME`） |
| Maven | **3.9.11** `C:\Users\voghion\apache-maven-3.9.11`（`MAVEN_HOME`） |
| PATH | 上述两个 `bin` |

推 GitHub / 发 Pilot **之前**先过门禁（不要 `--no-verify`，不要只在 Pilot 上编）：

```powershell
$env:JAVA_HOME = "C:\Users\voghion\java\jdk8u502-b07"
$env:Path = "$env:JAVA_HOME\bin;C:\Users\voghion\apache-maven-3.9.11\bin;$env:Path"
mvn -version   # 必须是 Java 1.8

cd <仓库根>
git checkout <发版分支>
mvn -B -ntp spotless:apply
mvn -B -ntp spotless:check
mvn -B -ntp test
```

`spotless:apply` 只改空格、换行、import，**不改逻辑**。CI 相对 `main` 改过的 Java 文件都会查，漏跑就会在 `collection-common` 等模块红。

编包（上面已经全绿时才允许跳过测试）：

```bash
cd <仓库根>
git checkout <发版分支>

mvn -pl collection-admin -am clean package -DskipTests -Dspotless.check.skip=true
ls -lh collection-admin/target/collection-admin.jar   # 约 80–90MB

scp collection-admin/target/collection-admin.jar ubuntu@$PILOT_HOST:/tmp/collection-admin.jar
scp deploy/Dockerfile ubuntu@$PILOT_HOST:/tmp/Dockerfile
scp deploy/certs/valubo-voice-test.crt ubuntu@$PILOT_HOST:/tmp/valubo-voice-test.crt
```

Windows 上 jar 被本机 Java 占用导致 `clean` 失败时，先 `taskkill //IM java.exe //F`。

> 证书那一份是新增的必带项：镜像会把 Valubo 测试 Facade 的自签名证书导入 TrustStore，漏传则 `docker build` 直接在 `COPY deploy/certs/...` 这一层失败。上线换正规域名证书后，`deploy/Dockerfile` 里的 `keytool` 段和这一步一起删。

---

## 2. 服务器

```bash
ssh ubuntu@$PILOT_HOST

# 备份现有 jar
cd /opt/app/build
mkdir -p collection-admin/target deploy/certs
cp collection-admin/target/collection-admin.jar \
   /opt/app/build/collection-admin.jar.bak.$(date +%Y%m%d%H%M) 2>/dev/null || true

# 构建上下文按仓库根的布局摆放
cp /tmp/collection-admin.jar        collection-admin/target/collection-admin.jar
cp /tmp/Dockerfile                  Dockerfile
cp /tmp/valubo-voice-test.crt       deploy/certs/valubo-voice-test.crt
ls -lh collection-admin/target/collection-admin.jar deploy/certs/valubo-voice-test.crt

# 禁止走旧 COPY 缓存
docker build --no-cache -t intelligent-collection-admin:pilot .

# 用现成脚本起容器（会 stop/rm/run，带对的 env 并做必填项校验）
/opt/app/build/pilot-run.sh
```

`docker build` 成功标志：

- `COPY collection-admin/target/collection-admin.jar` **不是 CACHED**
- `transferring context` 约 **86MB**，不是 147B
- `keytool -importcert` 那一层输出 `Certificate was added to keystore`

脚本若在「等待就绪」刷日志：**Ctrl+C**（只停跟日志）。

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
cd /opt/app/build
cp /opt/app/build/collection-admin.jar.bak.YYYYMMDDHHMM \
   collection-admin/target/collection-admin.jar
docker build --no-cache -t intelligent-collection-admin:pilot .
/opt/app/build/pilot-run.sh
```

`deploy/certs/` 与 `Dockerfile` 已在上下文里，回滚不必重传。

---

## 4. 常见失败

| 现象 | 原因 | 处理 |
|---|---|---|
| Maven `Failed to delete ...jar` | 本机 Java 占用 jar | `taskkill //IM java.exe //F` 再编 |
| CI `cannot find symbol: method of(List)` | 用了 Java 9+ 的 `List.of` | 本机切回 JDK 8 后改成 `Collections.emptyList()` / `singletonList` |
| CI Spotless 红、本机 Java 21 `spotless:apply` 炸 | GJF 1.7 只能在 JDK 8 跑 | `$env:JAVA_HOME` 指到 8u502 再 `mvn -B -ntp spotless:apply` |
| `COPY deploy/certs/valubo-voice-test.crt: not found` | 构建上下文缺证书 | 按 §1/§2 补传到 `/opt/app/build/deploy/certs/` |
| 发版后接口仍 404 | COPY 路径错 / 走了缓存 | 拷到 `collection-admin/target/` + `--no-cache` |
| health 502、容器 `Up 16 seconds` 循环 | 启动闸门失败或 JDBC | `docker logs --tail 80` 看 `Caused by`；用 `pilot-run.sh`，不要手写 `docker run` |
| `Pilot requires collection.case-service=ai` | 该实例配的不是 `ai`，或发的是未合并 AI Call 的旧包 | 闸门**只接受 `ai`**（`AiCollectionCaseService`）。核对 `pilot.env` 与 jar 内嵌 `application-pilot.yml` 是否都是 `ai`，两者不一致就是发了半合并的包 |
| `plan_id cannot be null`（回调审计） | 表约束 | 已对 `t_channel_callback_audit.plan_id/step_id` 放宽为可空，执行 `db/schema.sql` 的迁移过程 |
| Nacos `common.yml is empty` | 启动瞬间或 env 乱 | 以是否 `Tomcat started` 为准；不要在 common 里手写 JDBC |

---

## 5. 这台机的运行口径

- Profile：`pilot`
- `COLLECTION_SCHEDULER_ENABLED=false`：**本实例只接入、不触达**
- 配置：`/opt/app/pilot.env` + Nacos（`NACOS_NAMESPACE` / `NACOS_GROUP` 见 `pilot.env`）
- 库：`ai_collection_db`（与线上催收同一套）

> 配置生效优先级：**环境变量 > `application-pilot.yml` > Nacos 导入配置**。写在 `application-pilot.yml` 里的键（含 `spring.redis.*`）无法由 Nacos 覆盖，改这些值要改 `pilot.env` 并重启容器。详见 [T5 Pilot 准备与演练手册 §4.1](../testing/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md#41-连接信息落位与变更方式)。
