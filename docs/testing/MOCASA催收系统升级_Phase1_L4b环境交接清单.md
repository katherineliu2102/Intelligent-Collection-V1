# L4b 环境交接清单

> 给运维 / SRE / 主架构 / 信贷协调。开发项见「开发门禁」；本文聚焦**环境、配置、联调操作**。
> 联调方案定稿：**影子并行 + 六案闭环**（2026-06-30）；**L4b 输入定稿：方案 B 独立测试 topic**（2026-07-07）。

---

## 联调方案（运维须知晓）

| 维度 | L4b 联调（当前） | 生产并行 / 切量后 |
|---|---|---|
| **Topic** | **`collection-cases-test1`**（独立测试 topic，与生产隔离） | `collection-cases`（与线上一致） |
| **新系统订阅** | **`collection-cases-test1-sub`** | `collection-cases-ai-v1-sub`（**禁止**复用 `collection-cases-sub`） |
| **旧系统** | **不参与**（不同 topic，零污染） | 继续消费 `collection-cases-sub`，保持运行 |
| **测试消息来源** | 主架构侧 `scripts/test/l4b-pubsub/publish-test-messages.sh` 发合成案 | 信贷主系统日常 PubSub |
| **白名单** | `99000000`–`99000005`（`IC_TEST_*` 合成案，**非真实用户**） | 灰度切片（切量前再论） |
| **触达地址** | phone **`+639451374358`**，email **`wzynju@126.com`**（写入 payload + seed） | 同左（联调期） |
| **Push** | `push-test-token` = **`1a0018970bf0c19de04`**（强制覆盖，不触达真人） | 联调期保留；生产置空 |
| **SMS** | `sms-test-mode=true`（testSend） | 生产改 false |
| **Email** | **无全局沙箱**；payload 须为 **126 邮箱** | 同左 |
| **日切（L4b）** | **`POST /mock/daily-roll` 手动触发**（见 §日切手动验收） | XXL-Job `dailyRoll` 0:05 PHT（上线前接 `@XxlJob`） |
| **内部事件总线** | `collection.eventbus=memory`（缺省即可） | 切量多实例前改 Redis |
| **安全红线** | **禁止**向生产 topic `collection-cases` 发测试消息（旧系统会消费） | — |

> **为何不用生产 topic + 信贷配合**：信贷暂不能改 PubSub 发布逻辑；向共享 topic 自发合成消息会污染旧系统。独立测试 topic 可验完整 GCP 收发 + field-map，上线仅把 Nacos `subscription` 指回 `collection-cases-ai-v1-sub`，**零生产代码回退**。

---

## 职责分工（5 步落地）

| 步骤 | 负责 | 内容 |
|---|---|---|
| **1. 测试 topic + 订阅 + IAM** | 🔴 **运维** | 建 `collection-cases-test1` / `collection-cases-test1-sub`；给联调 Google 账号 publisher + subscriber |
| **2. Nacos 配置** | 🟢 **主架构** | `collection.ingestion.*` + 渠道沙箱；L4b 阶段 `subscription` 指向测试订阅 |
| **3. 旧库 seed** | 🟢 **主架构 / 服务** | `db/seed-test-cases.sql` + `seed-device-token.sql` → `ai_collection_db` |
| **4. 起服务 + 发消息** | 🟢 **主架构** | `start-local.sh` + `publish-test-messages.sh` |
| **5. 验收** | 🟢 **主架构** | 日志 `[Ingestion]` / `[DpdStageRollHandler]` + `db/l4b-assert.sql` |

**运维仅阻塞第 1 步**；2–5 主架构可独立完成（DDL 五表已建 ✅）。

---

## 运维待办

| # | 待办 | 目的 / 验收 | L4b |
|---|---|---|---|
| O1 | 建生产扇出订阅 `collection-cases-ai-v1-sub`（挂 topic `collection-cases`） | 并行期新/旧系统互不抢消息 | ✅ 已完成 |
| **O7** | **建 L4b 测试 topic + 订阅**（见 §运维对接草稿） | 独立环境，不向生产 topic 发测试消息 | 🔴 **待办** |
| O2 | 下发 GCP 凭证 + 确认 `fintech-all` 项目权限 | 应用 Subscriber / 发布脚本鉴权 | ✅ 已下发 `credentials.json`（**用户 ADC**，见下） |
| O3 | 注册 XXL-Job `dailyRoll` | **生产**日切调度 | ⬜ **L4b 不阻塞**（L4b 用手动 `/mock/daily-roll`） |
| O4 | Nacos 白名单 + 渠道沙箱（见下 YAML） | 范围隔离 + Push/SMS 沙箱 | 🟢 主架构可 `--apply` |
| O5 | `collection.ingestion.enabled=true` | 打开 PubSub 消费 | 含于 O4 |
| O6 | 通知中心 `appKey` + SMS 额度 | fallback 真入队 | 🟡 testSend 主路径不阻塞 |

### GCP 凭证说明（2026-07-07）

当前 `credentials.json` 为 **`authorized_user`**（`gcloud auth application-default login` 产物），**非**服务账号 JSON。应用与发布脚本均以**某个 Google 用户账号**身份访问 PubSub（本机 gcloud 活跃账号，请主架构与运维对齐具体邮箱）。运维 IAM 须对该**用户账号**授 publisher/subscriber，而非某个 `@*.iam.gserviceaccount.com`（除非后续改用 SA）。

### O4 + O5 Nacos 片段（L4b 阶段：指向测试订阅）

```yaml
collection:
  case-service: real
  ingestion:
    enabled: true
    project-id: fintech-all
    subscription: collection-cases-test1-sub   # L4b 联调；上线改回 collection-cases-ai-v1-sub
    loan-id-whitelist: [99000000, 99000001, 99000002, 99000003, 99000004, 99000005]
    case-push:
      field-map: { caseId: loanID, userId: userID, name: realName, product: appName }
channel:
  notification:
    sms-test-mode: true
    push-test-token: 1a0018970bf0c19de04
```

发布方式（主架构本地，凭证读 `.env`，无需手输密码）：

```bash
# 1) 编辑 deploy/nacos/l4b-collection.publish.yml 中 subscription 为测试订阅
# 2) 预览 → 发布
./scripts/dev/publish-l4b-config-to-nacos.sh
./scripts/dev/publish-l4b-config-to-nacos.sh --apply
```

> **环境变量**：`GCP_PUBSUB_PROJECT=fintech-all`、`GCP_PUBSUB_SUBSCRIPTION=collection-cases-test1-sub`（L4b）、`GOOGLE_APPLICATION_CREDENTIALS`=credentials.json 绝对路径。
> **本地便捷**：`source scripts/test/l4b-env.local.sh` 后启动 `collection-admin`。
> **金融字段**：`dpd`/金额不在 `case_push` payload → `case-service=real` 读 `t_collection.overdue_days`（混合方案，见 [接入 C-I](../MOCASA催收系统升级_Phase1_数据接入规格.md#c-i-入案字段与-pubsub-映射)）。

---

## 运维对接草稿（O7，可复制发送）

```
【L4b 新催收系统联调 — PubSub 测试环境申请】

背景：L4b 需要真实 PubSub 收发链路验证 field-map 与 Consumer，但为避免向生产共享
topic `collection-cases` 发测试消息（旧系统同 topic 会消费、污染生产），申请独立
测试 topic + 订阅。测试消息为合成案 loan_id 99000000~99000005（非真人），联系方式
为测试地址，不会触达真实用户。

项目：fintech-all

请协助：

1) 建测试 topic：
   gcloud pubsub topics create collection-cases-test1 --project fintech-all

2) 建测试订阅：
   gcloud pubsub subscriptions create collection-cases-test1-sub \
     --topic collection-cases-test1 --project fintech-all --ack-deadline 60

3) 给联调 Google 账号 <请填写：credentials 对应用户邮箱，如 keliu@indiacashgo.com> 授权：
   - topic collection-cases-test1 → roles/pubsub.publisher
   - subscription collection-cases-test1-sub → roles/pubsub.subscriber

验收：
   - gcloud pubsub subscriptions describe collection-cases-test1-sub --project fintech-all
   - 我方 publish 后应用日志出现 [Ingestion] PubSub consumer / case_push 消费记录

说明：生产 topic/subscription 不受影响；上线时我方仅把 Nacos subscription 改回
collection-cases-ai-v1-sub，本测试环境可保留作回归。
```

---

## 主架构联调操作（O7 完成后）

### 1. 旧库造数

```bash
mysql -h<HOST> -P3306 -u<USER> -p ai_collection_db < db/seed-test-cases.sql
mysql -h<HOST> -P3306 -u<USER> -p ai_collection_db < db/seed-device-token.sql
```

### 2. 起服务

```bash
source scripts/test/l4b-env.local.sh    # GCP_PUBSUB_* + GOOGLE_APPLICATION_CREDENTIALS
./scripts/dev/start-local.sh --detach
./scripts/test/l4b-preflight.sh --strict
```

### 3. 发测试 PubSub 消息（方案 B）

```bash
export GCP_PUBSUB_TEST_TOPIC=collection-cases-test1
./scripts/test/l4b-pubsub/publish-test-messages.sh case            # 6 案 case_push
./scripts/test/l4b-pubsub/publish-test-messages.sh repay 99000001    # L4b-2 还款
```

样例 JSON：`scripts/test/l4b-pubsub/case_push.sample.json`、`repayment.sample.json`（结构与信贷真实 `case_push` 同构，field-map 已对齐）。

> **护栏**：脚本内置拒绝向 `collection-cases` 发布。

### 4. 日切手动验收（L4b-3 / L4b-4 / L4b-8）

L4b **不接 XXL-Job**；用测试脚手架手动触发真实 `DpdStageRollHandler.dailyRoll()`：

```bash
curl -X POST http://localhost:8888/mock/daily-roll
# 日志：[DpdStageRollHandler] daily roll 完成 scanned=.. stageChanged=.. ceased=..
```

| 用例 | loan_id | 造数要点（`t_collection.overdue_days`） | 期望 |
|---|---|---|---|
| **L4b-3 升档** | 99000002 | 入案前 dpd=**1**（S1 建 plan）→ 改 dpd=**6**（S2）→ daily-roll | 旧 plan `STAGE_UPGRADE` 取消 + 新 S2 plan |
| **L4b-4 停催** | 99000005 | 入案前 dpd=**30**（≤90 才建 plan）→ 改 dpd=**95** → daily-roll | 活跃 plan `CEASED` 取消，不重建 |
| **L4b-8 幂等** | 99000002 | L4b-3 完成后 **连打两次** daily-roll | 第二次 `stageChanged=0`，无重复升档 |

> seed 默认 99000002=dpd4、99000005=dpd95 **不能直接**验日切，须按上表临时 UPDATE。

### 5. SQL 验收

```bash
mysql ... < db/l4b-assert.sql
```

---

## 六案与用例映射

| loan_id | 测试消息 / 操作 | L4b 用例 |
|---|---|---|
| 99000000 | `publish-test-messages.sh case`（或单案） | L4b-1 入案建计划 |
| 99000001 | case → `repay 99000001` | L4b-2 还款取消 |
| 99000002 | case + 日切造数（见上） | L4b-3 升档、L4b-8 幂等 |
| 99000003 | case + 等 TriggerScanner | L4b-6 扫描执行 |
| 99000004 | case | 高 dpd 触达 |
| 99000005 | case + 日切造数（见上） | L4b-4 停催 |

---

## 文档与配置焦点

| 附录 | L4b 优先级 | 说明 |
|---|---|---|
| **A.3** | 🔴 必做 | GCP、ingestion.enabled、白名单、field-map |
| **A.4** | 🟡 知晓 | 并行期 `PARALLEL`；不切 `NEW` |
| **A.5** | ⚪ 跳过 | Phase 1 dedup 内存版 |
| **A.6** | 🟡 部分签字 | L4b 闭合 **#1/#2/#3/#6/#7/#8** |
| **A.1 / A.2.1** | 🟢 缺省即可 | — |

---

## 开发门禁（L4b 前置）

| 项 | 状态 |
|---|---|
| L4a 已通过 | 门禁 |
| B1 `PubSubCaseConsumer` | ✅ 代码就绪 |
| B2 `DpdStageRollHandler.dailyRoll()` | ✅ 代码就绪；L4b 经 `/mock/daily-roll` 触发 |
| L3 MyBatis 落库 | ✅ DDL 已建 + mapper 已实现 |
| 方案 B 发布脚本 | ✅ `scripts/test/l4b-pubsub/` |
| Nacos collection 段 | ⬜ 待 `--apply` |
| O7 测试 topic | ⬜ 待运维 |

---

## 生产切量附加门禁（L4b 不阻塞）

| 项 | 说明 |
|---|---|
| **D1/D2** Redis 事件总线 / 步骤幂等 | 切量多实例前必论 |
| **XXL-Job dailyRoll** | 上线前接 `@XxlJob` + 执行器；L4b 用手动触发 |
| **Nacos subscription** | 改回 `collection-cases-ai-v1-sub` |
| **A.6 #4/#5/#9** | 灰度 / replay / topic 终态 |
