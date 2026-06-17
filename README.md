# Intelligent-Collection-V1 — MOCASA 智能催收系统（Phase 1）

> MOCASA 催收系统升级 **Phase 1**，仅覆盖 **菲律宾市场**。
> 当前形态：**接口先行 + Mock SPI 全链路跑通**（编译期起点，多模块并行开发）。

| | |
|---|---|
| **版本** | Phase 1 · v1.0（约 50% 开发进度） |
| **技术栈** | Spring Boot 2.7.18 · **JDK 8** · MyBatis · Maven 多模块 |
| **配置中心** | Nacos（测试环境，连接信息由主架构负责人下发，**不入库**） |
| **数据库** | MySQL 8（Phase 1 新库 `ai_collection_db`） |
| **事件总线 / 幂等** | Phase 1 内存版（无需 Redis）；接口抽象，后续平滑切 Redis Stream |
| **文档总索引** | [`docs/README.md`](./docs/README.md) ｜ 进度交接：[`HANDOFF.md`](./HANDOFF.md) |

---

## 一、项目简介

催收引擎以「**事件驱动状态机 + 七步执行管线**」为核心：案件入案后，引擎建立联系计划并按节奏调度，经合规预检后调用各渠道 SPI（短信 / 语音 / Push / 邮件）触达，回填结果并推进，直至计划完成或被还款 / 阶段变更中断。Phase 1 用 **Mock SPI** 把全链路跑通，各模块对照 `collection-common` 的编译期契约并行开发，替换 Mock 即接续，主框架零改动。

---

## 二、模块职责一览表

| 模块 | 一句话职责 | Phase 1 状态 | 归属 |
|---|---|---|---|
| `collection-common` | 契约层：枚举 / 模型 / DTO / 5 个 SPI / EventBus / Gateway / Repository 接口 | 编译期契约（稳定基线） | 主架构 |
| `collection-engine` | ★主框架★ 事件分发 · 计划状态机 · 七步管线 · 预检 · 内存总线/幂等 | **真实实现** | 主架构 |
| `collection-ingestion` | 数据接入：发布领域事件（Mock 入案）+ DPD 日切 Job 占位 | Mock，待接真实 PubSub | 主架构 |
| `collection-admin` | 启动入口：REST 触发/查询 · Webhook · Trigger→Event 调度 · 装配全部模块 | 骨架 | 主架构 |
| `collection-channel` | 渠道编排：5 个 SPI + ChannelGateway 的实现 | Mock，待编排同事替换 | 编排同事 |
| `collection-service` | 持久化 + CaseService / ProfileService（映射旧库） | Mock，待服务同事映射 | 服务同事 |

> 依赖底线：底层 `collection-common` **不得反向依赖**任何上层业务模块（后续阶段将用 ArchUnit 硬卡）。

---

## 三、3 分钟本地一键拉起 & 运行

> 前置：**JDK 8**、**Maven 3.6+**、**Docker**。
> Nacos / MySQL 为**共享测试环境**，连接信息向主架构负责人获取（不入库）。

### Step 1 — 配置环境变量（30s）

```bash
cp .env.example .env
# 编辑 .env，填入主架构负责人下发的 Nacos 地址/命名空间/账号口令；
# 渠道密钥写在 Nacos，不要写进 .env（模板见 deploy/nacos/）。
```

### Step 2 — 一键打包 + 起容器（约 2 分钟）

```bash
# 脚本内部 = mvn -pl collection-admin -am clean package -DskipTests
#            + docker compose -f deploy/docker-compose.yml up -d --build
./deploy/start-docker.sh          # Git Bash / macOS / Linux
deploy\start-docker.cmd           # Windows CMD / PowerShell

# 跟踪日志
docker compose -f deploy/docker-compose.yml logs -f collection-admin
```

> 不想用 Docker？本机直接跑：
> `mvn clean package -DskipTests && java -jar collection-admin/target/collection-admin.jar`

### Step 3 — 冒烟验证全链路（30s）

```bash
# 注入新案件 → 引擎建 3 步计划并开始执行
curl -X POST "http://localhost:8080/mock/ingest?caseId=1001&userId=1001&stage=S1"

# 查询计划状态（含步骤序列）/ 触达时间线
curl "http://localhost:8080/plans/1"
curl "http://localhost:8080/plans/timeline/1001"
```

链路：`CASE_INGESTED → 建计划(PENDING) → 扫描发 PLAN_STEP_DUE → 七步管线 → Mock 渠道发送 → STEP_COMPLETED → 推进 → … → PLAN_COMPLETED`。
扫描间隔默认 5s（`collection.scan.interval-ms`），可调小加速观测。

> 首次建表与中断/回调验证：

```bash
# 建表（连接信息向主架构负责人获取；-p 后留空交互式输入密码）
mysql -h<HOST> -P<PORT> -u<USER> -p <DB_NAME> < db/schema.sql
mysql -h<HOST> -P<PORT> -u<USER> -p <DB_NAME> < db/mock-data.sql   # 可选 mock 数据

# 还款中断：取消该用户活跃计划（plan → CANCELLED, REPAID）
curl -X POST "http://localhost:8080/mock/repayment?userId=1001&caseId=1001"
# 阶段变更：取消旧阶段计划 + 建新阶段计划
curl -X POST "http://localhost:8080/mock/stage-changed?caseId=1001&stage=S2"
# 异步渠道回调（计划含 AI_CALL/TTS 等异步步骤时）
curl -X POST "http://localhost:8080/webhook/channel-callback?planId=1&stepId=3&result=ANSWERED"
```

常用容器命令：`docker compose -f deploy/docker-compose.yml down`（停） / `... logs -f`（看日志）。
更细的 Nacos 本地启动见 [`docs/操作说明_Nacos本地启动.md`](./docs/操作说明_Nacos本地启动.md)。

---

## 四、目录速览

| 路径 | 内容 |
|---|---|
| `collection-*/` | 6 个业务模块（见 §2） |
| `deploy/` | Docker / 启动脚本 / Nacos 配置模板（部署相关全在此）；`deploy/secrets/` 放本地密钥（已忽略） |
| `scripts/` | 本地联调辅助脚本（冒烟 / 环境校验 / 密钥发布） |
| `db/` | `schema.sql` / `mock-data.sql` / 测试 seed |
| `docs/` | 全部规格与协作文档，入口 [`docs/README.md`](./docs/README.md)；新增 `architecture/` `api/` `testing/` 给后续同学 |
| `.github/` | CI 工作流 + PR 模板 |

---

## 五、协作与契约红线

- `collection-common` 的 **SPI + DTO + 枚举 = 编译期契约**；任何改动牵一发动全身，须全量 `mvn -q test` 回归并通知 channel / service 同事。
- `docs/channel/` 由**编排同事**维护，本分支**只读**，勿改勿移（merge 冲突源）。
- 改动后必须按 [`.cursor/rules/ic-v1-validation.mdc`](./.cursor/rules/ic-v1-validation.mdc) 跑对应模块验证命令并回报结果。
- 数据库连接信息、渠道密钥**绝不入库**（向主架构负责人获取）。

---

## 六、Phase 1 已知简化（非生产实现）

内存事件总线 / 内存幂等锁 / SPI 仅异常兜底未启线程级硬超时 / `@Scheduled` 代替 XXL-Job / `CaseService`·`ProfileService` 为合成 Mock。生产化目标见 [`docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md`](./docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md)。
