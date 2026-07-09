# 分案流程全链路 & LTH 外呼每日生命周期

*深度拆解 · 基于 collection_rebuild 源码*

---

## 一、分案流程全链路

| 指标 | 值 |
|------|-----|
| 分案入口 | 2 |
| 分案算法 (avg / pct) | 2 |
| 落库阶段 | 3 |

### 1.1 两条触发路径

| 路径 | 触发方式 | 入口代码 | 联动 WhatsApp | 联动 LTH |
|------|----------|----------|----------------|-----------|
| 自动分案 | GCP Pub/Sub assign_singal 信号 | CollectionService.makeDecision | 有 — whatsapp_push 查库后推 WSCRM | 无 |
| 手工分案 | Console 后台 → 单笔/批量分配 | CollectionAssignController → CollectionAssignService | 无 | 有 — TmpLthMobile + dealLthTask |

> **设计差异**：自动分案走 API 进程、手工分案走 Console 进程。两者共享 CaseAssignFunction 的分案算法，但后续联动完全不同：自动分案推 WhatsApp 不碰 LTH；手工分案写 LTH 任务不推 WhatsApp。

### 1.2 自动分案链路（Pub/Sub → 分案完成）

**自动分案执行序列**（API 进程）

1. **Step 1 — 消息入口**  
   CollecitonSubConfig 订阅 collection-cases-sub → collectionService.makeDecision(payload) → 解析 dataType

2. **Step 2 — 分配策略 (TAllocationStrategy)**  
   按 country 匹配 + strategyStatus = ENABLED + priority 排序 → 逐条执行 caseAssignFunction.executeAllocationStrategy(strategyId)

3. **Step 3 — 规则分案 (TCaseAssignRule)**  
   按 levelNo 升序取规则 → 每条规则的 ownerSearchSql / caseSearchSql 通过 TSqlEngineRule 引擎执行 → 得到催收员列表 + 案件列表 → runCaseAssign

4. **Step 4 — WhatsApp 推送**  
   执行 whatsapp_push 规则 SQL → 结果传 WscrmFunction.pushWhatsappsInfo（新线程）→ 按 system_user_id 查该坐席未结案 → 调 WSCRM API

5. **Step 5 — 异常告警**  
   整个流程 try-catch 后通过钉钉发送「分案异常报警」

### 1.3 策略配置模型（3 张表）

| 表 | 核心字段 | 说明 |
|----|----------|------|
| TAllocationStrategy | strategyName, timeRule, priority, strategyStatus, allocationMechanism (average/percent), country | 策略主表；启用 + 国别匹配 + 按优先级排序执行 |
| TAllocationStrategyCondition | strategyId, propCode, operationSymbol (EQ/NE/GE/LE), propValue | 案件筛选条件；转成 paramMap 查 TCollection，另固定加 notFinishedFlag=Y + appNameList |
| TAllocationStrategyUser | strategyId, departmentId/Code/Name, percent | 部门权重；非绑定具体坐席，坐席从 tSystemUserVManager.getCollectorDepartment 拉取 |

TCaseAssignRule 与策略表无关，是独立的「纯 SQL 拉案件/拉人 + 轮询分配」规则，按 levelNo 排序执行。

### 1.4 分案算法详解（CaseAssignFunction）

| 阶段 | 方法 | 逻辑 |
|------|------|------|
| 部门切案 | assignToRoles | 按 allocationMechanism 将案件切分到各部门 |
| 部门内到人 | runRandomAssign | 案件按 payCount 升序 + 催收员 shuffle → 轮询分配 |
| 落库 | submitToDb | 写 TCollectionAssignRecord + 执行 save_assign_result SQL |

**assignToRoles — 部门切案细节**

- **平均模式 (average)**：numPerRole = caseSize / roleSize → 每部门分配 numPerRole 件（随机取下标）→ 余量：随机选一个仍存在的部门塞入，并移除该部门槽位  
- **比例模式 (percent)**：numPerRole = (caseSize * percent) / 100（整数除法）→ 余量：全部分配给 templist 最后一个部门  

**runRandomAssign — 到人分配细节**

1. 案件按 payCount（还款次数）升序排序  
2. 催收员列表 Collections.shuffle 随机打乱  
3. 依次从队头取案件，按 systemUserList[i] 分配，i 循环 0..size-1  

*注：老逻辑（随机均分 + 余量随机）已注释掉，当前为纯轮询模式。*

### 1.5 落库与状态变更

| 操作 | 内容 |
|------|------|
| 内存设值 | TCollection: ownerId/Name, ownerDepartmentId/Name |
| 状态设值 | 默认 → COLLECTION_UNHANDLED (3)；若 ownerId == lastCollectorId → COLLECTION_HANDLING (2) |
| 批量写记录 | TCollectionAssignRecord 每 1000 条一批 |
| 动态 SQL | 取 TSqlEngineRule 中 engine=save_assign_result 的首条规则，替换 #assignId 后 executeUpdateSql |

> **关键：save_assign_result SQL 不在代码中**  
> Java 侧仅写分配记录和设置内存状态。实际 t_collection 表的 owner_*、status 等字段是否更新，完全取决于数据库 t_sql_engine_rule 表中配置的 save_assign_result SQL 内容。

### 1.6 手工分案（Console 侧）

| 接口 | 方法 | 特殊逻辑 |
|------|------|----------|
| POST /collection/assignCollectors | assignCollectors | 解析 userIds + collectionIds → runCaseAssign → 写 TmpLthMobile → @Async dealLthTask |
| POST /collection/batchAssignCollectors | batchAssignCollectors | 按 departmentIds 取坐席 + 多维筛选条件 → runCaseAssign → TmpLthMobile → dealLthTaskDepartment |

手工分案的 assignType = `"manual"`，allocationMechanism = `"average"`，共用 CaseAssignFunction.runCaseAssign 算法。

### 1.7 案件状态速查

| 值 | 枚举 | 含义 | 分案时何时设置 |
|----|------|------|----------------|
| 1 | COLLECTION_NOT_DISTRIBUTED | 未分配 | 初始状态（入案时） |
| 2 | COLLECTION_HANDLING | 处理中 | ownerId == lastCollectorId（同人续案） |
| 3 | COLLECTION_UNHANDLED | 未处理 | 默认分案结果 |
| 4 | COLLECTION_FINISHED | 已结清 | 还款后置 |
| 5 | COLLECTION_BLACKLIST | 拉黑 | 后台操作 |

---

## 二、LTH 外呼每日生命周期

| 指标 | 值 |
|------|-----|
| XXL-JOB 任务 | 5+ |
| LTH 相关表 | 6 |
| LthFunction API | 10+ |
| 回调接口 | 1 |

### 2.1 LthFunction — 与 LTH 平台的 API 封装

LthFunction 位于 function 模块，通过 RestTemplate 发送 HTTP 请求到 LTH 呼叫中心平台。URL 配置在 SystemVariable 中。

| 方法 | 能力 |
|------|------|
| createTask | 创建新的 LTH 外呼任务 |
| addNumberToTask | 向现有任务追加号码 |
| stopTask | 停止 LTH 任务 |
| filterNumber | 向任务添加过滤号码（如已还款用户） |
| queryLthTaskStatus | 查询任务状态 |
| agentLogin / agentOutbound | 坐席登录和手动外呼（WebPhone 集成） |
| sendSms | 发送短信 |
| voiceNotification | 创建语音通知任务（TTS / 预录音） |

*说明：更细的实现（如 console 侧 `LthFunction`、`TLthFilterNumber.type`、XXL Cron 注释等）以仓库内最新代码及运营侧 XXL-JOB 配置为准。*

### 2.2 LTH 数据模型

| 表 | 用途 |
|----|------|
| TDepartmentLthGroupMapping | 部门 ↔ LTH 任务组映射，决定哪个部门对应 LTH 的哪个组 |
| TCollectionLthTask | 每天创建的 LTH 任务记录：taskId、departmentId、lthGroupId、状态、日期 |
| TLthCallRecord | LTH 平台返回的通话记录详情 |
| TLthSmsSendRecord | LTH 发送短信的记录 |
| TLthFilterNumber | 从 LTH 任务中过滤掉的号码记录 |
| TmpLthMobile | 临时表，Console 手工分案时批量向 LTH 追加号码的中间存储 |

### 2.3 每日完整时间线

**Phase 1 — 创建当日任务**（凌晨 / 早上）

- XXL-JOB: `lthCreateTask`
- 遍历 TDepartmentLthGroupMapping（部门→LTH 组映射）
- 对每个部门 + LTH 组：查 TCollectionLthTask 获取当日任务
- 若不存在或过期 → 筛案件（未处理 + 非黑名单 + 未还清 + 未在 LTH 任务中）
- 调 LthFunction.createTask 创建任务 → addNumberToTask 追加号码 → 更新 inLthTask 状态

**Phase 2 — 动态维护（4 个日内 Job）**（日间持续运行）

- **addPaidNumberFilter**：已还清过滤 — 查当天已还清案件 → 获取所有活跃 LTH 任务 → 对每个号码调 filterNumber 移除  
- **addPTPNumber**：PTP 逾期追拨 — 查已承诺还款但逾期未还的案件 → 追加号码到对应 LTH 任务（自动回拨）  
- **recallTagRPC**：二次召回 — 查 RPC 标记 + 满足通话时间条件的案件 → 追加号码到 LTH 任务  
- **addNumberForMoveTask**：DPD 阶段换组 — 查即将进入下一 DPD 阶段的案件 → 从当前任务 filterNumber → 追加到新阶段任务 addNumberToTask  

**Phase 3 — 通话回调**（实时）

- `POST /lth/agentCallBack`
- LTH 平台通话结束后实时推送话单和状态 → CollectionAssignCallBackController 接收
- 解析回调内容（通话结果、时长、录音文件等）→ 落库 TLthCallRecord → 更新案件催收状态

**Phase 4 — 停止任务**（晚上）

- XXL-JOB: `stopLthTask`
- 遍历所有活跃 LTH 任务 → 调 LthFunction.stopTask 停止
- 清空 TCollectionLthTask 当天的 taskId → 复位 TCollection 的 inLthTask 状态  
- 为次日 lthCreateTask 重新建任务做准备

**Phase 5 — 语音通知（独立链路）**（按需）

- XXL-JOB: `lthVoice`
- LthVoiceNotificationCallTask 根据业务配置（特定 DPD 阶段）筛选案件
- 调 LthFunction.voiceNotification 创建语音通知任务或追加号码

### 2.4 LTH 与分案的联动矩阵

| 分案路径 | LTH 联动方式 | WhatsApp 联动 |
|----------|--------------|----------------|
| Pub/Sub 自动分案 | 源码中未直接调用 LTH — 依赖 lthCreateTask Job 建任务时纳入 | 有：whatsapp_push → WSCRM API |
| Console 手工分案（单笔） | 写 TmpLthMobile → @Async dealLthTask → createTask 或 addNumberByCollections | 无 |
| Console 手工分案（批量） | 写 TmpLthMobile → dealLthTaskDepartment → 按部门创建/追加 LTH 任务 | 无 |

> **自动分案 ↔ LTH 的「间接」关系**  
> 自动分案（assign_singal）完成后，案件已绑定催收员但 inLthTask 仍为 N。次日凌晨 lthCreateTask 执行时，会筛选出这些已分配但未加入 LTH 任务的案件，统一创建外呼任务。因此，自动分案到 LTH 外呼之间存在「T+1 延迟」。

---

## 三、分案 + LTH 联合流转图

| 时间段 | 自动分案侧 | LTH 外呼侧 | 数据流 |
|--------|------------|------------|--------|
| T 日 — Pub/Sub 信号 | makeDecision → 策略 + 规则分案 → 写 TCollectionAssign + save_assign_result | — | 案件绑定催收员；WhatsApp 推送 |
| T 日 — Console 手工 | assignCollectors / batchAssignCollectors → runCaseAssign | TmpLthMobile → dealLthTask 即时创建/追加 LTH 任务 | 案件绑定 + LTH 号码入池 |
| T+1 凌晨 | — | lthCreateTask：按部门→LTH 组映射建当日任务，纳入前日分案但未入池的案件 | inLthTask = Y |
| T+1 日间 | — | addPaidNumberFilter / addPTPNumber / recallTagRPC / addNumberForMoveTask | 动态增减号码 |
| T+1 实时 | — | /lth/agentCallBack 话单回写 | TLthCallRecord + 案件状态更新 |
| T+1 晚间 | — | stopLthTask：停止任务 + 清空 taskId + inLthTask = N | 为 T+2 重建做准备 |

---

## 四、当前设计的几个观察点

### WhatsApp 与 LTH 联动割裂

自动分案推 WhatsApp 但不碰 LTH；手工分案联动 LTH 但不推 WhatsApp。两条触达通道的联动逻辑分布在不同模块，缺乏统一的「触达编排层」。

### 自动分案 → LTH 存在 T+1 延迟

Pub/Sub 自动分案后案件不会立即加入 LTH 外呼池，需等到次日 lthCreateTask 才会被纳入。若分案发生在工作时间内，当天不会有 LTH 外呼触达。

### save_assign_result 是黑盒

分案结果是否真正回写 t_collection 取决于数据库中的 TSqlEngineRule 配置，Java 代码里不可见。任何分案行为的变更都需要同时对齐 DB 中这条 SQL。

### 余量分配策略不一致

平均模式下余量案件随机分配到各部门；比例模式下余量全部给最后一个部门。这可能导致比例模式下某部门负载偏高。

---

*基于 collection_rebuild 仓库源码的只读分析。配置中的敏感信息已省略；TSqlEngineRule 中的动态 SQL 内容需在数据库中核对。*

*来源 Canvas：`case-assign-and-LTH-lifecycle.canvas.tsx`*
