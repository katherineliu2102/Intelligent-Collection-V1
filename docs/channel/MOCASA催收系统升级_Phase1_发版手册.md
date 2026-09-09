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
- **§1 的 JDK/Maven 路径是这台 Windows 工作站的约定**（`C:\Users\voghion\...`）。别人照抄会失败；换成自己机器上的 **JDK 8 + Maven 3.9.x** 即可，版本要求不变。
- **`$PILOT_HOST`、登录账号、密码不写进本手册。** 真值只在 gitignore 的 `docs/ops/生产访问凭据.local.md`。
- **触达是否已切生产，以容器 `printenv` + `[PilotReadiness]` 为准，不要只看 Git 里的 yml。** `pilot-run.sh` 读的是机上 `/opt/app/pilot.env`：新 jar 里即使 `sms-test-mode: false`，env 残留 `true` 仍走 `/v1/sms/testSend`。
- **触达开关：GitHub 入库缺省必须等于 Pilot 正在跑的口径。** 禁止只改 `/opt/app/pilot.env`、不改仓库。2026-09-09 事故：当时仓库仍写 `sms-test-mode=true`、Pilot 只靠 env 切生产，版本对不上；`true` 走 `/v1/sms/testSend`，通知中心测试账号会路由到 **QHSms**（正式 `mocasa` 账号没有这条供应商），08:00 一次失败 157 条。yml 缺省现已改为 `false`，发版仍须确认 **env 不是 `true`**（环境变量优先于 yml）。

---

## 0.1 短信 / Push：仓库与 Pilot 必须同为生产口径

发版前核对下面四项。缺省值写在 `application-pilot.yml` 与 `application-local.yml`（均为 `false` / 空）。**环境变量优先于 yml**：`pilot.env` 里若仍是 `CHANNEL_NOTIFICATION_SMS_TEST_MODE=true`，新包里的 `false` **不会生效**。改开关必须「yml 入库 + `pilot.env` 同步 + 提交后再打包含该 yml 的 jar」。

| 开关 | 生产（入库缺省 / Pilot） | 禁止当生产用 |
|---|---|---|
| `channel.notification.sms-test-mode` / `CHANNEL_NOTIFICATION_SMS_TEST_MODE` | `false` → 签名 `POST /v1/sms/send` | `true` → 免签 `/v1/sms/testSend`，仍打真实运营商，且可能进 QHSms |
| `sms-test-recipient` / `CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT` | 空（发借款人） | 非空 = 全部改投自持号 |
| `push-sync-mode` / `CHANNEL_NOTIFICATION_PUSH_SYNC_MODE` | `false` → 异步 `/v1/app_notification/send` | `true` 仅联调看极光同步回执 |
| `push-test-token` / `CHANNEL_NOTIFICATION_PUSH_TEST_TOKEN` | 空（按用户 token） | 非空 = 全部改投测试机 |

L4a / 零真实触达：只在本机用环境变量临时打开上表「禁止」列，**不要把 true / 测试 token 写回 yml 再 push**。

发版后在 Pilot 上确认：env 为 `false` 或未设置（yml 缺省 `false`），且 `[PilotReadiness]` **没有** `sms-test-mode=true`，并写「无触达测试开关生效」：

```bash
docker exec collection-admin printenv CHANNEL_NOTIFICATION_SMS_TEST_MODE
docker logs --since 3m collection-admin 2>&1 | grep -E 'PilotReadiness|sms-test-mode|TEST mode'
```

`printenv` 空着也可以（键未写入 env 时走 yml `false`）。**不能是 `true`。** 08:00 槽不应再出现 `[NotificationSmsAdapter] TEST mode → /v1/sms/testSend`。

只看仓库 yml 不够：镜像里的缺省可以被 `/opt/app/pilot.env` 盖掉。发版检查顺序是 **容器 `printenv` → 启动日志 `[PilotReadiness]` → 再对一次 Git 缺省**，三处同向才算切到生产。

---

## 1. 本机

本机工具（用户级环境变量，新开的 PowerShell 才看得到）：

| 项 | 值 |
|---|---|
| JDK | Temurin **8u502** `C:\Users\voghion\java\jdk8u502-b07`（`JAVA_HOME`） |
| Maven | **3.9.11** `C:\Users\voghion\apache-maven-3.9.11`（`MAVEN_HOME`） |
| PATH | 上述两个 `bin` |

上表路径只适用于当前这台本机。换机器时把 `JAVA_HOME` / `MAVEN_HOME` 改成该机的 JDK 8 与 Maven 3.9.x，**不要**把别人的 `C:\Users\...` 原样贴进脚本。

推 GitHub / 发 Pilot **之前**先过门禁（不要 `--no-verify`，不要只在 Pilot 上编）。yml / 开关改动必须先 **commit** 再 `package`：打进镜像的是 jar 内嵌的 `application-*.yml`，未提交的本机修改 scp 过去也不会在 Git 上留下对应版本。

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

> 证书那一份是新增的必带项：镜像会把 Valubo **测试** Facade 的自签名证书（`valubo-voice-test.crt`）导入 TrustStore，漏传则 `docker build` 直接在 `COPY deploy/certs/...` 这一层失败。这与**现网 Pilot 镜像**一致，不是笔误。上线换正规域名证书后，`deploy/Dockerfile` 里的 `keytool` 段、本步 `scp`、以及服务器上 `deploy/certs/` 里的测试证一起删。

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

`YYYYMMDDHHMM` 是**占位符**，不要原样粘贴。先 `ls /opt/app/build/collection-admin.jar.bak.*`，换成真实备份文件名。

```bash
cd /opt/app/build
ls -lh /opt/app/build/collection-admin.jar.bak.*
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
| 短信大量 `NOTIFICATION_SMS_REJECTED` 且日志 `channel=QHSms` / `TEST mode → /testSend` | Pilot 仍 `sms-test-mode=true`，与仓库/正式账号不一致 | 仓库 yml 与 `pilot.env` 都改 `CHANNEL_NOTIFICATION_SMS_TEST_MODE=false`，提交后再发版；失败案补发走生产 `/send`，不要复活已推进的计划步骤 |

---

## 5. 这台机的运行口径

- Profile：`pilot`
- `COLLECTION_SCHEDULER_ENABLED=true`（`application-pilot.yml` 缺省即 true）：**接入 + 调度触达都开**。日槽 08:00 SMS / 09:15 AI / 12:00 Push / 14:00 Email / 14:30 AI 由 Cloud Scheduler → 调度订阅驱动。不要按旧口径改成 `false`（那会变成只接入、当天不再触达）。
- 触达开关：`CHANNEL_NOTIFICATION_SMS_TEST_MODE=false`，Push 无 test-token / 异步 `/send`，见 §0.1。
- 配置：`/opt/app/pilot.env` + Nacos（`NACOS_NAMESPACE` / `NACOS_GROUP` 见 `pilot.env`）
- 库：`ai_collection_db`（与线上催收同一套）

> 配置生效优先级：**环境变量 > `application-pilot.yml` > Nacos 导入配置**。`application-pilot.yml` 里写死、没有 `${ENV:}` 的键（例如部分 `spring.redis.*`）Nacos 盖不掉，改它们要改 yml 再发版。带 `${CHANNEL_*:}` 的键以 `pilot.env` 为准，env 与 Git 缺省必须同向，见 §0.1。详见 [T5 Pilot 准备与演练手册 §4.1](../testing/runbooks/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md#41-连接信息落位与变更方式)。
