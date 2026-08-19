import {
  Button,
  Callout,
  Card,
  CardBody,
  CardHeader,
  Divider,
  Grid,
  H1,
  H2,
  H3,
  Pill,
  Row,
  Stack,
  Stat,
  Table,
  Text,
  useCanvasState,
  useHostTheme,
} from "cursor/canvas";

type SceneId =
  | "first"
  | "refresh"
  | "partial"
  | "settle"
  | "stage"
  | "cease";

type Scene = {
  id: SceneId;
  label: string;
  warehouse: string;
  persist: string;
  event: string;
  engine: string;
};

const SCENES: Scene[] = [
  {
    id: "first",
    label: "首次入催",
    warehouse: "同一条每日 caseEvent。数仓不管是否已在催，对 dpd≥-3 逐条发完整快照",
    persist: "新建案件投影并写收件箱，状态 IN_COLLECTION",
    event: "发布内部事件 CASE_INGESTED",
    engine: "按快照创建催收计划，开始触达",
  },
  {
    id: "refresh",
    label: "每日刷新",
    warehouse: "同一条每日 caseEvent。内容有变化才视为新快照",
    persist: "有变化才覆盖投影；无变化则略过，收件箱记 SKIPPED",
    event: "不发内部事件，避免重复建计划",
    engine: "无动作；当日日切窗口再比对阶段是否变化",
  },
  {
    id: "partial",
    label: "部分还款",
    warehouse: "repaymentEvent，账务落库后再等 360 秒",
    persist: "有变化则更新余额、DPD、到期日；仍为 IN_COLLECTION",
    event: "发布 CASE_BALANCE_UPDATED",
    engine: "只改活跃计划里的到期余额，不换阶段、不换模板",
  },
  {
    id: "settle",
    label: "整笔结清",
    warehouse: "repaymentEvent，isFullCleared=true，延迟 360 秒",
    persist: "投影改为 SETTLED，余额归零",
    event: "发布 REPAYMENT_RECEIVED",
    engine: "取消该案全部活跃计划，停止触达",
  },
  {
    id: "stage",
    label: "日切换阶段",
    warehouse: "数仓不发阶段事件；当日 caseEvent 已把最新 dpd/stage 写入 t_ai_collection",
    persist: "日切只读投影，不改案件表",
    event: "投影阶段与活跃计划不一致（含回退）才发 STAGE_CHANGED",
    engine: "取消旧阶段计划，按新阶段重建计划；无变化则不动作",
  },
  {
    id: "cease",
    label: "D+91 停催",
    warehouse: "每日快照把案件标成 CEASED，不另发停催消息",
    persist: "日切只读；投影已是 CEASED / dpd≥91",
    event: "仍有活跃计划时发 CASE_CEASED",
    engine: "取消计划且不再续建，完全停催",
  },
];

const PIPE_STEPS = [
  { n: "1", title: "数仓计算", body: "DPD、阶段、余额、结清状态、联系人一次算齐" },
  { n: "2", title: "消息发布", body: "只发两类事实：每日案件快照、成功还款" },
  { n: "3", title: "接入校验", body: "缺关键字段 poison ack 并告警；重复或无变化直接跳过" },
  { n: "4", title: "投影落库", body: "同一事务更新案件投影并写收件箱；有变化才覆盖" },
  { n: "5", title: "内部通知", body: "库写成功后才发内部事件，决定建计划或停催" },
];

export default function DataInboundFlowPm() {
  const theme = useHostTheme();
  const [sceneId, setSceneId] = useCanvasState<SceneId>("scene", "first");
  const scene = SCENES.find((item) => item.id === sceneId) ?? SCENES[0];

  return (
    <Stack gap={24} style={{ padding: 24, maxWidth: 1180 }}>
      <Stack gap={8}>
        <H1>新系统数据入站全流程</H1>
        <Text tone="secondary">
          数仓发布案件事实，接入层校验并落库，引擎按内部事件建计划或停催。Phase 1 仅菲律宾。
        </Text>
      </Stack>

      <Callout tone="info" title="分工原则">
        数仓只算、只发消息，不写催收业务库。接入层是案件表的唯一写入者。引擎不重算 DPD 和金额，只消费已经落库后的内部事件。
      </Callout>

      <Grid columns={4} gap={12}>
        <Stat value="2 类" label="外部事实消息" />
        <Stat value="1 个" label="案件表写入者" />
        <Stat value="5 种" label="内部业务事件" />
        <Stat value="06:00" label="批次消费后完成日切（PHT）" />
      </Grid>

      <H2>入站顺序</H2>
      <Grid columns={5} gap={8}>
        {PIPE_STEPS.map((step) => (
          <div
            key={step.n}
            style={{
              padding: 12,
              background: theme.fill.tertiary,
              border: `1px solid ${theme.stroke.secondary}`,
            }}
          >
            <Stack gap={6}>
              <Text
                size="small"
                weight="semibold"
                style={{ color: theme.accent.primary }}
              >
                {step.n}
              </Text>
              <Text weight="semibold">{step.title}</Text>
              <Text tone="secondary" size="small">
                {step.body}
              </Text>
            </Stack>
          </div>
        ))}
      </Grid>
      <Text tone="tertiary" size="small">
        来源：数据接入规格 §1–§3 · 数仓 Pub/Sub 交付契约 §1–§4
      </Text>

      <H2>职责分工</H2>
      <Grid columns={3} gap={12}>
        <Card>
          <CardHeader trailing={<Pill size="sm">不算催收策略</Pill>}>数仓</CardHeader>
          <CardBody>
            <Stack gap={8}>
              <Text>从信贷明细算出 DPD、阶段、到期余额、结清状态和联系方式。</Text>
              <Text tone="secondary" size="small">
                每天 03:00 PHT 前发完全量案件快照；每 15 分钟扫一次成功还款，账务落库后再等 360 秒才发。
              </Text>
            </Stack>
          </CardBody>
        </Card>
        <Card>
          <CardHeader trailing={<Pill size="sm" active>唯一写案件表</Pill>}>
            接入层
          </CardHeader>
          <CardBody>
            <Stack gap={8}>
              <Text>收消息、校验、有变化才写入案件投影，再发内部事件。</Text>
              <Text tone="secondary" size="small">
                不做「催谁、用什么渠道」的决策，也不直接发短信或外呼。
              </Text>
            </Stack>
          </CardBody>
        </Card>
        <Card>
          <CardHeader trailing={<Pill size="sm">不重算金额</Pill>}>引擎 / 渠道</CardHeader>
          <CardBody>
            <Stack gap={8}>
              <Text>收到内部事件后建计划、换阶段、取消计划，再执行触达。</Text>
              <Text tone="secondary" size="small">
                入案时冻结一份快照；部分还款只改到期余额，对外文案发送前会再读一次最新案件。
              </Text>
            </Stack>
          </CardBody>
        </Card>
      </Grid>

      <H2>两条管道</H2>
      <Grid columns="1fr 1fr" gap={16}>
        <Card>
          <CardHeader trailing={<Pill size="sm">数仓发布</Pill>}>
            案件消息管道 · 写库
          </CardHeader>
          <CardBody>
            <Stack gap={8}>
              <Text>
                Topic：`intelligent-collection-cases-v1` · 订阅：`intelligent-collection-cases-v1-sub`
              </Text>
              <Text tone="secondary" size="small">
                联调 Topic：`intelligent-collection-cases-test1` · 订阅：`intelligent-collection-cases-test1-sub`
              </Text>
              <Text>
                数仓只发两类完整快照：每日 caseEvent、成功 repaymentEvent。
              </Text>
              <Text tone="secondary" size="small">
                接入层写入 t_ai_collection 后，才决定是否通知引擎。
              </Text>
            </Stack>
          </CardBody>
        </Card>
        <Card>
          <CardHeader trailing={<Pill size="sm">应用发布</Pill>}>
            调度 Topic · 3 个任务
          </CardHeader>
          <CardBody>
            <Stack gap={8}>
              <Text>
                Topic：`intelligent-collection-schedule-v1` · 订阅：`intelligent-collection-schedule-v1-sub`
              </Text>
              <Text tone="secondary" size="small">
                联调 Topic：`intelligent-collection-schedule-test1` · 订阅：`intelligent-collection-schedule-test1-sub`
              </Text>
              <Text>
                应用 Cloud Scheduler 按 cron 往该 Topic 发 tick。日切 tick 的属性为 job=dailyRoll，不含案件快照。
              </Text>
              <Text tone="secondary" size="small">
                dailyRoll 收到后分页读 t_ai_collection。只读、不改案件表；它独占产生阶段变化和 D+91 停催。
              </Text>
            </Stack>
          </CardBody>
        </Card>
      </Grid>
      <Callout tone="info" title="调度 tick">
        tick 是时钟消息，不是案件事实。GCP Cloud Scheduler 到点往调度 Topic 发一条 Pub/Sub，body 可为空，靠属性 job 区分任务：dailyRoll / planStepDue / callbackTimeout。同一订阅按 job 路由。
      </Callout>
      <Callout tone="warning" title="阶段与停催由日切独占">
        外部 Topic 若违规投递阶段或停催，会与 dailyRoll 冲突并反复取消重建计划。外部消息只报事实。
      </Callout>

      <H2>业务场景</H2>
      <Row gap={8} wrap>
        {SCENES.map((item) => (
          <span key={item.id}>
            <Button
              variant={item.id === sceneId ? "primary" : "secondary"}
              onClick={() => setSceneId(item.id)}
            >
              {item.label}
            </Button>
          </span>
        ))}
      </Row>
      <Card>
        <CardHeader trailing={<Pill active>{scene.label}</Pill>}>
          处理结果
        </CardHeader>
        <CardBody>
          <Grid columns={2} gap={16}>
            <Stack gap={4}>
              <Text size="small" tone="tertiary">
                数仓发出
              </Text>
              <Text>{scene.warehouse}</Text>
            </Stack>
            <Stack gap={4}>
              <Text size="small" tone="tertiary">
                落库结果
              </Text>
              <Text>{scene.persist}</Text>
            </Stack>
            <Stack gap={4}>
              <Text size="small" tone="tertiary">
                内部事件
              </Text>
              <Text>{scene.event}</Text>
            </Stack>
            <Stack gap={4}>
              <Text size="small" tone="tertiary">
                催收计划
              </Text>
              <Text>{scene.engine}</Text>
            </Stack>
          </Grid>
        </CardBody>
      </Card>

      <H2>更新机制</H2>
      <Callout tone="info" title="快照来源">
        每日刷新的快照来自数仓当天重算后推过来的 caseEvent，不是读 t_ai_collection。案件表是被覆盖的目标；日切只读这张表。
      </Callout>

      <H3>数仓发布规则</H3>
      <Table
        headers={["动作", "规则", "说明"]}
        rows={[
          [
            "每日发布",
            "dpd≥-3 的候选案逐条发完整 caseEvent；不管新系统有没有在催",
            "首次入催和每日刷新走同一条发送管道，差别在接入层写完之后",
          ],
          [
            "成功还款",
            "每 15 分钟扫一次；仅成功正向还款；账务落库后再等 360 秒",
            "只处理成功正向还款，用来改余额或整笔停催",
          ],
          [
            "不发布",
            "不发换阶段、停催；D-4 及更早不发",
            "阶段和停催由新系统日切读案件表后自己判断",
          ],
        ]}
        rowTone={["info", "info", "warning"]}
        striped
      />

      <H3>接入层落库与事件</H3>
      <Table
        headers={["更新方式", "频率", "是否写投影", "是否建计划", "说明"]}
        columnAlign={["left", "left", "left", "left", "left"]}
        rows={[
          [
            "每日案件快照",
            "每天 03:00 PHT 前发完",
            "会。有变化则覆盖 DPD / 余额 / 到期日；无变化不覆盖",
            "仅首次入催会建；已在催只刷新",
            "保证案件表每天对齐数仓，修历史、补漏发",
          ],
          [
            "成功还款",
            "每 15 分钟扫一次，再等 360 秒",
            "会。有变化则更新余额和结清状态",
            "部分还款不建计划；整笔结清取消计划",
            "还款要等账务稳定后再改催收，避免刚还又催",
          ],
          [
            "日切比对",
            "03:35–05:55 PHT，06:00 前完成",
            "不改。只读当天已刷好的案件表",
            "换阶段会取消旧计划再建；停催只取消",
            "阶段、停催由新系统自己判断，不听数仓指挥",
          ],
        ]}
        rowTone={["info", "info", "neutral"]}
        striped
      />
      <Text tone="tertiary" size="small">
        来源：数仓契约 §4.2 / §4.5 / §5 / §6 · 数据接入规格 §3.1 / §4
      </Text>

      <H2>幂等与版本</H2>
      <Grid columns="1fr 1fr" gap={16}>
        <Stack gap={8}>
          <H3>eventId</H3>
          <Text>
            数仓发出前就生成稳定编号。重试、重投、按时间点重放都复用同一个编号，接入层认过就跳过。
          </Text>
        </Stack>
        <Stack gap={8}>
          <H3>caseVersion</H3>
          <Text>
            标识该案快照是否变化，保证同一内容唯一。有变化则刷新投影，无变化则略过。当前约定不会乱序到达。
          </Text>
        </Stack>
      </Grid>

      <H2>落库机制</H2>
      <Table
        headers={["存储", "写入方", "内容", "说明"]}
        rows={[
          [
            "t_ai_collection 案件投影",
            "仅接入层",
            "该案当前最新事实：DPD、阶段、余额、联系人、催收状态",
            "新系统眼里的「当前案件」。日切、守卫、对客金额都读它",
          ],
          [
            "t_ai_collection_inbox 收件箱",
            "仅接入层",
            "每条上游消息的处理收据：已发布 / 待补发 / 已跳过",
            "投影写库和内部通知不是同一处存储，收据用来防止「库写了但引擎没收到」",
          ],
          [
            "内部事件总线",
            "接入层发布，引擎消费",
            "CASE_INGESTED / 还款 / 换阶段 / 停催",
            "催收动作的开关。没事件，就不会新建或取消计划",
          ],
          [
            "t_contact_plan 催收计划",
            "引擎",
            "本周期怎么催：阶段、步骤、冻结快照",
            "真正对客触达的执行单。接入层不写这张表",
          ],
        ]}
        striped
      />

      <H3>写入顺序</H3>
      <Text>
        同一事务先更新案件投影，再写收件箱；提交成功后才发布内部事件。引擎消费事件后才创建、更新或取消催收计划，最后向 GCP 确认消息已处理。如果事件没发出去，收件箱停在「待补发」，重来时只补通知、不再改案件表。
      </Text>

      <H3>收件箱状态</H3>
      <Grid columns={3} gap={12}>
        <Card>
          <CardHeader>已发布</CardHeader>
          <CardBody>
            <Text>投影已更新，内部事件也发出。同一条消息再来，整条跳过。</Text>
          </CardBody>
        </Card>
        <Card>
          <CardHeader>待补发</CardHeader>
          <CardBody>
            <Text>投影已写入，但引擎还没收到通知。重来时只补发事件。</Text>
          </CardBody>
        </Card>
        <Card>
          <CardHeader>已跳过</CardHeader>
          <CardBody>
            <Text>内容无变化，或已在催案件的每日刷新。库可能已更新，但不会重复建计划。</Text>
          </CardBody>
        </Card>
      </Grid>

      <Divider />

      <H2>日时间表（PHT）</H2>
      <Table
        headers={["时间", "执行方", "结果"]}
        rows={[
          ["03:00 前", "数仓", "把当日全部在催和状态变化案件的完整快照发完"],
          ["接到即处理", "接入层", "有变化则刷新案件表；新案才通知引擎建计划"],
          ["03:35–05:55", "日切任务", "只读案件表，分页比对阶段 / 停催"],
          ["06:00 前", "运维门禁", "日切必须跑完；批次没消费完则推迟日切并告警"],
          ["白天每 15 分钟", "数仓 + 接入", "成功还款延迟 360 秒后更新余额或结清停催"],
        ]}
        striped
      />

      <H2>入催边界</H2>
      <Grid columns="1fr 1fr" gap={16}>
        <Stack gap={10}>
          <Text weight="semibold">入催</Text>
          <Text>DPD 从 D-3 到 D+90，且贷款仍有未结清账单。</Text>
          <Text>Push 没 token 仍会入案，发送时自动改走短信。</Text>
          <Text>D+91 候选也会先入库，由日切发停催，引擎拒建新计划。</Text>
        </Stack>
        <Stack gap={10}>
          <Text weight="semibold">不入催</Text>
          <Text>D-4 及更早：数仓根本不发。</Text>
          <Text>已整笔结清：计划取消后不再重新入催。</Text>
          <Text>数仓发来的「换阶段 / 停催」消息：当错误数据丢掉并告警。</Text>
        </Stack>
      </Grid>

      <Text tone="tertiary" size="small">
        依据：数仓 Pub/Sub 交付契约（2026-08-14）· 数据接入规格（2026-08-12）· 架构设计文档 §1.2 · 核心引擎规格 §2 / §4
      </Text>
    </Stack>
  );
}
