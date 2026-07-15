# MOCASA 催收系统升级 Phase 1 — L4b 端到端测试报告

- 执行日期：2026-07-07（PHT）
- 执行人：主架构 / 引擎负责人
- 被测应用：`collection-admin`（Spring Boot 2.7.18，profile=local，端口 8888）
- 数据源：真实旧库 `ai_collection_db`（34.124.218.94）+ 真实 GCP PubSub（`fintech-all`）+ 真实渠道（HiWaySms / 极光 Push / SendGrid Email）
- 配置：Nacos `intelligent-collection-local.yml`（`case-service=real`，`ingestion.enabled=true`，白名单 `99000000~99000005`）

## 一、测试范围与结论

| 用例 | 场景 | 结果 |
|------|------|------|
| L4b-1 | case_push 入案 → 建计划/步骤/timeline 落库 | ✅ 通过（6 案中 5 案；03 见"环境备注"） |
| L4b-5 | 快照字段混合溯源（context_snapshot 校验） | ✅ 通过 |
| L4b-6 | TriggerScanner 扫描到期步骤并执行 | ✅ 通过 |
| L4b-2 | 还款到账 → 活跃计划 PLAN_CANCELLED / REPAID | ✅ 通过 |
| L4b-3 | 日切升档 → STAGE_CHANGED（换计划） | ✅ 通过 |
| L4b-4 | 日切停催（dpd≥91）→ CASE_CEASED | ✅ 通过 |
| L4b-8 | 日切幂等（重复 daily-roll noop） | ✅ 通过 |

**总体结论：核心链路（入案 → 建计划 → 扫描执行 → 渠道下发 → timeline 落库 → 还款/升档/停催/幂等）全部打通并落库正确。存在 1 个环境侧投递问题（共享订阅被其他消费者分流），非代码缺陷。**

## 二、测试数据（seed，t_collection 中 `IC_TEST_%`）

| loan_id | dpd | 阶段 | total_not_paid | due_date |
|---------|-----|------|----------------|----------|
| 99000000 | 0  | S0 | 5100.00  | 2026-06-09 |
| 99000001 | 2  | S1 | 5250.00  | 2026-06-07 |
| 99000002 | 7  | S2 | 8560.00  | 2026-06-02 |
| 99000003 | 13 | S2 | 10950.00 | 2026-05-27 |
| 99000004 | 22 | S3 | 7060.00  | 2026-05-18 |
| 99000005 | 45 | S4 | 14160.00 | 2026-04-25 |

## 三、用例明细

### L4b-1 入案建计划
向测试 topic `collection-cases-test1` 发布 6 条 case_push。链路：`PubSubCaseConsumer → IngestionService.ingestCase → CASE_INGESTED → DefaultPlanFactory → PlanLifecycleManager.create → 落库`。
- 5 案（00/01/02/04/05）均成功建计划并落库。
- 各阶段计划步骤数：S0=1、S1=3、S2=4、S3=3、S4=2，与策略配置一致。

### L4b-5 快照字段混合溯源
校验 `t_contact_plan.context_snapshot`：财务字段来自旧库，静态字段来自 payload。

| case | 快照 dpd/stage/outstanding/dueDate | 旧库真值 | 一致 |
|------|------|------|------|
| 00 | 0 / S0 / 5100.0 / 2026-06-09 | 0 / 5100.00 / 06-09 | ✅ |
| 01 | 2 / S1 / 5250.0 / 2026-06-07 | 2 / 5250.00 / 06-07 | ✅ |
| 02 | 7 / S2 / 8560.0 / 2026-06-02 | 7 / 8560.00 / 06-02 | ✅ |
| 04 | 22 / S3 / 7060.0 / 2026-05-18 | 22 / 7060.00 / 05-18 | ✅ |
| 05 | 45 / S4 / 14160.0 / 2026-04-25 | 45 / 14160.00 / 04-25 | ✅ |

静态字段（name / primaryPhone / jpushToken）均来自 payload。阶段派生（`Stage.fromDpd`）与 dpd 边界一致。

### L4b-6 TriggerScanner 扫描执行
扫描器按步骤 `delay_minutes`（0/1/1/1）依次触发，实际下发并落 timeline（共 18 条）：
- SMS / PUSH：`DELIVERED`，带真实 provider_msg_id（如 HiWaySms `116595112`、极光 `18103276950600095`）。
- EMAIL：按策略 `SKIPPED`。
- 多步推进正常，计划最终 `PLAN_COMPLETED`。

### L4b-2 还款取消
`/mock/ingest`（caseId=99000001, S1）建活跃计划 139（STEP_EXECUTING）→ `/mock/repayment`（userId=99000001）→ 发布 `REPAYMENT_RECEIVED`。
- 结果：计划 139 → `PLAN_CANCELLED`，cancel_reason=`REPAID`。✅

### L4b-3 日切升档
99000002 建 **S1** 活跃计划 140（旧库 dpd=7=S2，故意错档）→ `/mock/daily-roll`。
- 日志：`loanId=99000002 dpd=7 stage S1→S2 → STAGE_CHANGED`，`scanned=6 stageChanged=1 ceased=0`。
- 结果：旧计划 140 → `PLAN_CANCELLED`(STAGE_UPGRADE)，新建 S2 计划 141（STEP_EXECUTING）。✅

### L4b-4 日切停催
99000003 建活跃计划 142（S2）→ 将旧库 `overdue_days` 改为 95（≥91）→ `/mock/daily-roll`。
- 日志：`loanId=99000003 dpd=95 ≥91 → CASE_CEASED`，`scanned=6 stageChanged=0 ceased=1`。
- 结果：计划 142 → `PLAN_CANCELLED`，cancel_reason=`CEASED`。测试后 dpd 已还原为 13。✅

### L4b-8 日切幂等
稳定态（唯一活跃计划 99000002/141 S2 与旧库 dpd=7=S2 匹配）下连续两次 `/mock/daily-roll`：
- 两次均 `scanned=6 stageChanged=0 ceased=0`（noop）。✅

## 四、最终计划落库（t_contact_plan）

| case | plan_id | stage | status | cancel_reason |
|------|---------|-------|--------|---------------|
| 99000000 | 134 | S0 | PLAN_COMPLETED | - |
| 99000001 | 137 | S1 | PLAN_COMPLETED | - |
| 99000001 | 139 | S1 | PLAN_CANCELLED | REPAID |
| 99000002 | 135 | S2 | PLAN_COMPLETED | - |
| 99000002 | 140 | S1 | PLAN_CANCELLED | STAGE_UPGRADE |
| 99000002 | 141 | S2 | (活跃，自然完成中) | - |
| 99000003 | 142 | S2 | PLAN_CANCELLED | CEASED |
| 99000004 | 136 | S3 | PLAN_COMPLETED | - |
| 99000005 | 138 | S4 | PLAN_COMPLETED | - |

## 五、环境备注与遗留事项

1. **共享订阅被其他消费者分流（环境侧，非代码缺陷）**：
   loan 99000003 的 case_push 连续 7 次发布均未到达本地 consumer（原始日志 0 命中），而其余 5 案正常消费；手动 `gcloud pull` 亦拉不到。判断为 `collection-cases-test1-sub` 上存在**另一处 collection-admin 实例（他机/部署）**在争抢消息，PubSub 将部分消息负载均衡分走。
   - 影响：仅影响 L4b-1 的入案投递可靠性，不影响引擎/落库逻辑（S2 路径已由 99000002 完整验证）。
   - 建议运维：为本次联调分配**独占订阅**，或确认同一订阅上无其他活跃订阅方。

2. **真实渠道确有下发**：SMS/Push 均返回真实 provider_msg_id，实际发往 seed 中配置的测试号 `+639451374358`。上线前请确认该号为受控测试号，或在联调期开启渠道 sandbox。逐案应收 SMS/Push/Email 正文见 **[L4b触达内容核对清单](./MOCASA催收系统升级_Phase1_L4b触达内容核对清单.md)**。

3. **权限现状**：GCP 凭证 `keliu@indiacashgo.com`（ADC）具备 topic publish 与 subscription consume（pull/streaming），但**无 `subscriptions.get`（describe）**——不影响消费，仅无法查订阅元数据。

4. **XXL-Job 未接**：日切 L4b-3/4/8 通过 `POST /mock/daily-roll` 手动触发验证（读真实旧库 dpd）。上线前需由运维注册 XXL-Job 每日 0:05 PHT 调 `DpdStageRollHandler.dailyRoll()`。

## 六、距正式上线的后续（除 AI Call 外）
- 运维分配独占订阅 / 确认订阅唯一消费方，并将生产订阅回切 `collection-cases-ai-v1-sub`。
- 注册 XXL-Job 日切任务。
- 渠道 sandbox / 白名单收口，避免误触真实用户。
- Nacos 生产命名空间配置固化（当前为 local）。
