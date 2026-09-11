import {
  Alert,
  Button,
  Card,
  Col,
  Flex,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  message
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

/* ============================= 类型 ============================= */

type ViewKey = "today" | "review";

type OutreachRow = {
  channel?: string;
  records: number;
  attempted: number;
  delivered: number;
  failed: number;
  skipped: number;
  other: number;
  deliveryRate: number | null;
};

type SlotRow = {
  slot: string;
  channel: string;
  records?: number;
  attempted?: number;
  delivered?: number;
  failed?: number;
  skipped?: number;
  deliveryRate?: number | null;
  stageBreakdown?: { stage: string; delivered: number }[];
  slotBreakdown?: { scriptSlot: string; delivered: number; skipped?: number }[];
  zeroSendNormal?: boolean;
  pending?: boolean;
  batchId?: string;
  waveKey?: string;
  planned?: number;
  completed?: number;
  ringing?: number;
  lineAnswered?: number;
  answered?: number;
  snr?: number;
  mailbox?: number;
  aiConnected?: number;
  human?: number;
  effective?: number;
  busy?: number;
  noAnswer?: number;
  calleeOther?: number;
  answerRate?: number | null;
  failedRate?: number | null;
  missing?: boolean;
  answeredLabels?: { label: string; count: number }[];
};

type AnsweredRow = {
  sessionId: string;
  caseId: number;
  waveKey?: string;
  answeredAt?: string | null;
  receivedAt?: string | null;
  party?: string | null;
  effectiveConversation?: boolean | number | null;
  rightParty?: string | null;
  resultLabel?: string;
  summary?: string;
  stageSnapshot?: string | null;
  dpdSnapshot?: number | null;
};

type AiCallDetailItem = AnsweredRow;

type TodayData = {
  layer: string;
  freshness: string;
  phtDate: string;
  from: string;
  asOf: string;
  slots: SlotRow[];
  outreach: {
    byChannel: OutreachRow[];
    skipReasons: { channel: string; reason: string; count: number }[];
  };
  answered: AnsweredRow[];
  roll: {
    routedToLegacy: { count: number; caseIds: number[] };
    deliveredAfterRoute: { count: number; caseIds: number[]; pass: boolean };
    stageUpgrade: { count: number; caseIds: number[] };
    inbox: { caseEvent?: number; repaymentEvent?: number };
    newPlans: number;
    ownerReconcile?: Record<string, unknown>;
    cancels?: Record<string, number>;
  };
  risk: {
    hanging: { stepId: number; planId: number; caseId?: number; dispatchedAt?: string }[];
    guardBlocked?: { guardBlocked: number };
    guardByChannel?: { channel: string; count: number }[];
    executingAi?: number;
    pendingDueAi?: number;
    exceptions?: Record<string, number>;
    disconnect?: { invalidNumber: number };
    highSensitivity?: { sessionId: string; caseId: number; result_label?: string; resultLabel?: string; summary?: string; received_at?: string; receivedAt?: string }[];
  };
};

type OutreachData = {
  from: string;
  to: string;
  summary: {
    totalRecords: number;
    totalAttempted: number;
    delivered: number;
    failed: number;
    skipped: number;
    other: number;
    deliveryRate: number | null;
  };
  byChannel: OutreachRow[];
  byResult: { result: string; count: number }[];
  exceptions: Record<string, number | string>;
  plans: Record<string, number | string>;
};

type PortfolioData = {
  asOf: string;
  businessDate?: string;
  freshness?: string;
  workset: {
    cases: number;
    openingOutstanding: number;
    repaidCases: number;
    repaidAmount: number;
  };
  byStage: {
    stage: string;
    cases: number;
    openingOutstanding?: number;
    repaidCases?: number;
    repaidAmount?: number;
  }[];
};

type AiCallData = {
  funnel: {
    dispatched: number;
    ringing?: number;
    answered: number;
    liveAnswered?: number;
    human?: number;
    aiConnected: number;
    effective?: number;
    rpc?: number;
    ptp?: number;
    invalid: number;
  };
  labelDistribution: { label: string; count: number; bucket: string }[];
  sipDistribution: { sipCode: string; count: number }[];
  failureStructure?: { failureClass?: string; reason: string; label?: string; count: number }[];
  waves?: {
    waveKey: string;
    slot?: string;
    completed: number;
    lineAnswered?: number;
    human?: number;
    effective?: number;
    mailbox?: number;
    answered: number;
    snr: number;
    busy: number;
    noAnswer: number;
    calleeOther?: number;
    failed: number;
    answerRate: number | null;
    failedRate: number | null;
  }[];
};

type MatrixRow = {
  channel: string;
  stage: string;
  records: number;
  attempted: number;
  delivered: number;
};

type DailyChannelPoint = {
  day: string;
  channel: string;
  records: number;
  attempted: number;
  delivered: number;
};

type AiAnswerPoint = {
  day: string;
  completed: number;
  answered: number;
};

type RiskData = {
  highSensitivity: {
    sessionId?: string;
    session_id?: string;
    caseId?: number;
    resultLabel?: string;
    result_label?: string;
    summary: string;
    receivedAt?: string;
    received_at?: string;
  }[];
  disconnect: { invalidNumber: number };
  guardBlocked: { guardBlocked: number };
  hanging: {
    stepId: number;
    planId: number;
    dispatchedAt?: string;
    executedAt?: string;
  }[];
};

/* ============================= 工具 ============================= */

function pctRate(v?: number | null) {
  if (v == null || Number.isNaN(Number(v))) return "—";
  return `${(Number(v) * 100).toFixed(1)}%`;
}

function money(v?: number | string) {
  const n = Number(v);
  if (v == null || v === "" || Number.isNaN(n)) return "—";
  return "₱" + n.toLocaleString("en-PH", { maximumFractionDigits: 0 });
}

function formatWave(wave?: string | null) {
  if (!wave) return "—";
  if (wave === "UNKNOWN") return "未分波次";
  const m = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})$/.exec(wave);
  if (!m) return wave;
  return `${m[1]}-${m[2]}-${m[3]}  ${m[4]}:${m[5]}`;
}

function fmtTs(v?: string | null) {
  if (!v) return "—";
  return String(v).slice(0, 19).replace("T", " ");
}

function dash(v?: string | number | null) {
  if (v == null || v === "") return "—";
  return String(v);
}

function yn(v?: boolean | number | string | null) {
  if (v == null || v === "") return "—";
  if (v === true || v === 1 || v === "1") return "是";
  if (v === false || v === 0 || v === "0") return "否";
  return String(v);
}

function firstFilter(v?: (string | number | boolean)[] | null) {
  if (!v || v.length === 0) return undefined;
  return String(v[0]);
}

function uniqueFilterOptions(
  rows: AnsweredRow[],
  pick: (r: AnsweredRow) => string | null | undefined,
  format?: (v: string) => string
) {
  const seen = new Map<string, string>();
  for (const row of rows) {
    const raw = pick(row);
    const value = raw == null || raw === "" ? "" : String(raw);
    if (!seen.has(value)) {
      seen.set(value, format ? format(value) : value === "" ? "未回传" : value);
    }
  }
  return [...seen.entries()].map(([value, text]) => ({ text, value }));
}

function facetOptions(values: string[] | undefined, format?: (v: string) => string) {
  return (values || []).map((value) => ({
    text: format ? format(value) : value === "" ? "未回传" : value,
    value
  }));
}

function answeredColumns(opts: {
  clientRows?: AnsweredRow[];
  facets?: { labels?: string[]; stages?: string[]; waves?: string[]; connectKinds?: string[] };
  filteredValue?: {
    resultLabel?: string[] | null;
    stageSnapshot?: string[] | null;
    waveKey?: string[] | null;
  };
}): ColumnsType<AnsweredRow> {
  const client = !!opts.clientRows;
  const labelFilters = client
    ? uniqueFilterOptions(opts.clientRows || [], (r) => r.resultLabel)
    : facetOptions(opts.facets?.labels);
  const stageFilters = client
    ? uniqueFilterOptions(opts.clientRows || [], (r) => r.stageSnapshot)
    : facetOptions(opts.facets?.stages);
  const waveFilters = client
    ? uniqueFilterOptions(opts.clientRows || [], (r) => r.waveKey, (v) => (v ? formatWave(v) : "未分波次"))
    : facetOptions(opts.facets?.waves, (v) => (v ? formatWave(v) : "未分波次"));
  const partyFilters = client
    ? uniqueFilterOptions(opts.clientRows || [], (r) => r.party)
    : facetOptions(["human", "voicemail", "call_screening"]);
  return [
    { title: "案件", dataIndex: "caseId", width: 90 },
    {
      title: "Stage",
      dataIndex: "stageSnapshot",
      width: 90,
      render: (v: string) => dash(v),
      filters: stageFilters.length ? stageFilters : undefined,
      filterMultiple: false,
      filteredValue: opts.filteredValue?.stageSnapshot,
      onFilter: client
        ? (value, record) => (record.stageSnapshot || "") === String(value)
        : undefined
    },
    {
      title: "DPD",
      dataIndex: "dpdSnapshot",
      width: 70,
      align: "right",
      render: (v: number | null) => dash(v)
    },
    {
      title: "波次",
      dataIndex: "waveKey",
      width: 170,
      render: (v: string) => formatWave(v),
      filters: waveFilters.length ? waveFilters : undefined,
      filterMultiple: false,
      filteredValue: opts.filteredValue?.waveKey,
      onFilter: client ? (value, record) => (record.waveKey || "") === String(value) : undefined
    },
    {
      title: "回调时间",
      dataIndex: "receivedAt",
      width: 165,
      render: (v: string) => fmtTs(v)
    },
    {
      title: "party",
      dataIndex: "party",
      width: 110,
      render: (v: string) => dash(v),
      filters: client && partyFilters.length ? partyFilters : undefined,
      filterMultiple: false,
      onFilter: client ? (value, record) => (record.party || "") === String(value) : undefined
    },
    {
      title: "effective_conversation",
      dataIndex: "effectiveConversation",
      width: 170,
      render: (v: boolean | number | null) => yn(v)
    },
    {
      title: "right_party",
      dataIndex: "rightParty",
      width: 100,
      render: (v: string) => dash(v)
    },
    {
      title: "disposition",
      dataIndex: "resultLabel",
      width: 170,
      render: (v: string) => (v ? <Tag color={labelColor(labelBucketOf(v))}>{v}</Tag> : "—"),
      filters: labelFilters.length ? labelFilters : undefined,
      filterMultiple: false,
      filteredValue: opts.filteredValue?.resultLabel,
      onFilter: client
        ? (value, record) => (record.resultLabel || "") === String(value)
        : undefined
    },
    {
      title: "摘要",
      dataIndex: "summary",
      render: (v: string) => <SummaryCell text={v} />
    }
  ];
}

function SummaryCell({ text }: { text?: string | null }) {
  if (!text) return <>{"—"}</>;
  return (
    <Typography.Paragraph ellipsis={{ rows: 2, tooltip: text }} style={{ marginBottom: 0 }}>
      {text}
    </Typography.Paragraph>
  );
}

function AiTodayConnectSummary({ slots }: { slots: SlotRow[] }) {
  const ai = slots.filter((s) => s.channel === "AI_CALL" && !s.pending);
  if (ai.length === 0) return null;
  const line = ai.reduce((n, s) => n + Number(s.lineAnswered ?? 0), 0);
  const human = ai.reduce((n, s) => n + Number(s.human ?? s.aiConnected ?? 0), 0);
  const mailbox = ai.reduce((n, s) => n + Number(s.mailbox ?? s.snr ?? 0), 0);
  const unrecognized = Math.max(0, line - human - mailbox);
  return (
    <Typography.Text type="secondary">
      今日线路接通 {line} · 真人 {human} · 信箱/筛选 {mailbox} · 未识别对方 {unrecognized}
    </Typography.Text>
  );
}

function resultColor(v?: string): string {
  switch (v) {
    case "DELIVERED":
    case "SENT":
    case "ACCEPTED":
      return "green";
    case "SKIPPED":
      return "orange";
    case "FAILED":
    case "REJECTED":
    case "BOUNCED":
      return "red";
    default:
      return "default";
  }
}

function labelColor(bucket: string): string {
  if (bucket === "合规风险") return "red";
  if (bucket === "业务结果") return "green";
  return "default";
}

function BarList({
  data,
  labelKey,
  valueKey
}: {
  data: { [k: string]: string | number }[];
  labelKey: string;
  valueKey: string;
}) {
  const max = Math.max(1, ...data.map((d) => Number(d[valueKey]) || 0));
  return (
    <Flex vertical gap={6} style={{ width: "100%", minWidth: 520 }}>
      {data.map((d, i) => (
        <Flex key={i} align="center" gap={8}>
          <div
            style={{
              width: 220,
              textAlign: "right",
              flexShrink: 0,
              fontSize: 12,
              wordBreak: "break-all"
            }}
          >
            {d[labelKey]}
          </div>
          <div
            style={{
              flex: 1,
              background: "#f0f0f0",
              borderRadius: 3,
              height: 18,
              overflow: "hidden"
            }}
          >
            <div
              style={{
                width: `${((Number(d[valueKey]) || 0) / max) * 100}%`,
                background: "#1677ff",
                height: "100%",
                borderRadius: 3
              }}
            />
          </div>
          <div style={{ width: 56, flexShrink: 0, fontSize: 12 }}>{d[valueKey]}</div>
        </Flex>
      ))}
    </Flex>
  );
}

function TrendSpark({
  series
}: {
  series: { day: string; attempted: number; delivered: number; records: number }[];
}) {
  const max = Math.max(1, ...series.map((d) => Number(d.attempted) || 0));
  return (
    <Flex align="flex-end" gap={4} style={{ height: 56 }}>
      {series.map((d, i) => {
        const attempted = Number(d.attempted) || 0;
        const delivered = Number(d.delivered) || 0;
        const h = (attempted / max) * 100;
        const dh = attempted > 0 ? (delivered / attempted) * 100 : 0;
        return (
          <Tooltip
            key={i}
            title={`${String(d.day).slice(0, 10)} · attempted ${attempted} · sent ${delivered} · records ${d.records}`}
          >
            <div
              style={{
                width: 18,
                height: "100%",
                display: "flex",
                flexDirection: "column",
                justifyContent: "flex-end",
                cursor: "default"
              }}
            >
              <div style={{ height: `${h}%`, display: "flex", flexDirection: "column" }}>
                <div style={{ height: `${dh}%`, background: "#52c41a", borderRadius: "2px 2px 0 0" }} />
                <div
                  style={{
                    height: `${100 - dh}%`,
                    background: "#1677ff",
                    borderRadius: dh === 0 ? "2px 2px 0 0" : "0 0 2px 2px",
                    minHeight: attempted > 0 && delivered === 0 ? 2 : 0
                  }}
                />
              </div>
            </div>
          </Tooltip>
        );
      })}
    </Flex>
  );
}

function ChannelStageMatrix({ rows }: { rows: MatrixRow[] }) {
  const stages = ["S0", "S1", "S2", "S3", "S4"];
  const channelOrder = ["SMS", "PUSH", "EMAIL", "AI_CALL", "AI_CALL_HUMAN"];
  const channelLabel = (ch: string) => {
    if (ch === "AI_CALL") return "AI Call 线路接通";
    if (ch === "AI_CALL_HUMAN") return "AI Call 真人接通";
    return ch;
  };
  const channels = Array.from(new Set(rows.map((r) => r.channel)));
  channels.sort((a, b) => {
    const ia = channelOrder.indexOf(a);
    const ib = channelOrder.indexOf(b);
    return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
  });
  const cell = (channel: string, stage: string) =>
    rows.find((r) => r.channel === channel && r.stage === stage);
  const maxByRow = new Map<string, number>();
  for (const ch of channels) {
    const vals = stages.map((s) => Number(cell(ch, s)?.attempted) || 0);
    maxByRow.set(ch, Math.max(1, ...vals));
  }
  return (
    <Table
      rowKey={(r) => String(r)}
      size="small"
      pagination={false}
      locale={{ emptyText: "无数据" }}
      dataSource={channels}
      columns={[
        {
          title: "渠道 \\ Stage",
          dataIndex: "",
          width: 150,
          render: (v: string) => channelLabel(v)
        },
        ...stages.map((s) => ({
          title: s,
          align: "right" as const,
          width: 92,
          render: (_: unknown, channel: string) => {
            const c = cell(channel, s);
            const attempted = Number(c?.attempted) || 0;
            const delivered = Number(c?.delivered) || 0;
            const alpha = attempted === 0 ? 0 : 0.15 + 0.75 * (attempted / (maxByRow.get(channel) || 1));
            const rate = attempted > 0 ? delivered / attempted : null;
            const rateHint =
              channel === "AI_CALL"
                ? "线路接通（含真人/信箱等）"
                : channel === "AI_CALL_HUMAN"
                  ? "真人接通 party=human"
                  : "发送率";
            return (
              <span
                title={attempted > 0 ? rateHint : undefined}
                style={{
                  display: "inline-block",
                  minWidth: 48,
                  padding: "1px 6px",
                  borderRadius: 3,
                  background: attempted > 0 ? `rgba(22,119,255,${alpha.toFixed(2)})` : "transparent",
                  color: alpha > 0.55 ? "#fff" : undefined
                }}
              >
                {attempted === 0 ? "—" : `${attempted} / ${pctRate(rate)}`}
              </span>
            );
          }
        }))
      ]}
    />
  );
}

const metricCols: ColumnsType<OutreachRow> = [
  {
    title: <Tooltip title="timeline 全部 OUT 行（含 SKIPPED）">Records</Tooltip>,
    dataIndex: "records",
    width: 84,
    align: "right"
  },
  {
    title: <Tooltip title="实际发起发送（DELIVERED+FAILED 等，不含 SKIPPED）">Attempted</Tooltip>,
    dataIndex: "attempted",
    width: 92,
    align: "right"
  },
  {
    title: (
      <Tooltip title="催收系统成功发出（SENT / DELIVERED / ACCEPTED），不是供应商「已送达用户」回执">Sent</Tooltip>
    ),
    dataIndex: "delivered",
    width: 84,
    align: "right"
  },
  { title: "Failed", dataIndex: "failed", width: 68, align: "right" },
  { title: "Skipped", dataIndex: "skipped", width: 76, align: "right" },
  {
    title: <Tooltip title="result 不在 SENT/DELIVERED/FAILED/SKIPPED 等标准枚举">未归类</Tooltip>,
    dataIndex: "other",
    width: 68,
    align: "right"
  },
  {
    title: <Tooltip title="Sent / Attempted；分母为 0 显示 —，禁止记 0%">Rate</Tooltip>,
    dataIndex: "deliveryRate",
    width: 72,
    align: "right",
    render: (v: number | null) => pctRate(v)
  }
];

function CaseIds({ ids }: { ids?: number[] }) {
  if (!ids || ids.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Flex wrap="wrap" gap={4}>
      {ids.map((id) => (
        <Typography.Text key={id} copyable>
          {id}
        </Typography.Text>
      ))}
    </Flex>
  );
}

/* ============================= 组件 ============================= */

export function DashboardPage() {
  const [view, setView] = useState<ViewKey>("today");
  const [loading, setLoading] = useState(false);
  const [days, setDays] = useState(7);

  const [today, setToday] = useState<TodayData | null>(null);
  const [data, setData] = useState<OutreachData | null>(null);
  const [portfolio, setPortfolio] = useState<PortfolioData | null>(null);
  const [matrix, setMatrix] = useState<{ rows: MatrixRow[] } | null>(null);
  const [dailyByChannel, setDailyByChannel] = useState<{
    series: DailyChannelPoint[];
    aiAnswerRate: AiAnswerPoint[];
  } | null>(null);
  const [aicall, setAicall] = useState<AiCallData | null>(null);
  const [aicallDays, setAicallDays] = useState(7);
  const [aicallDetail, setAicallDetail] = useState<{
    total: number;
    items: AiCallDetailItem[];
    facets?: { labels?: string[]; stages?: string[]; waves?: string[]; connectKinds?: string[] };
  } | null>(null);
  const [aicallDetailPage, setAicallDetailPage] = useState(1);
  const [aicallDetailFilters, setAicallDetailFilters] = useState<{
    resultLabel?: string;
    stage?: string;
    waveKey?: string;
  }>({});
  const [risk, setRisk] = useState<RiskData | null>(null);

  const loadAll = useCallback(async () => {
    setLoading(true);
    const named: Array<[string, Promise<{ data?: unknown }>]> = [
      ["today", api.dashboardToday()],
      ["outreach", api.dashboardOutreachRealtime(days)],
      ["portfolio", api.dashboardPortfolio()],
      ["matrix", api.dashboardMatrix(days)],
      ["daily", api.dashboardDailyByChannel(days)],
      ["aicall", api.dashboardAicallRealtime(aicallDays)],
      ["aicallDetail", api.dashboardAicallDetail(aicallDetailPage, 25, aicallDays, false, aicallDetailFilters)],
      ["risk", api.dashboardRisk()]
    ];
    const results = await Promise.allSettled(named.map(([, p]) => p));
    const failed: string[] = [];
    results.forEach((result, i) => {
      const label = named[i][0];
      if (result.status === "rejected") {
        failed.push(label);
        return;
      }
      const payload = result.value.data;
      if (label === "today") setToday(payload as TodayData);
      if (label === "outreach") setData(payload as OutreachData);
      if (label === "portfolio") setPortfolio(payload as PortfolioData);
      if (label === "matrix") setMatrix(payload as { rows: MatrixRow[] });
      if (label === "daily") {
        setDailyByChannel(payload as { series: DailyChannelPoint[]; aiAnswerRate: AiAnswerPoint[] });
      }
      if (label === "aicall") setAicall(payload as AiCallData);
      if (label === "aicallDetail") {
        setAicallDetail(
          payload as {
            total: number;
            items: AiCallDetailItem[];
            facets?: { labels?: string[]; stages?: string[]; waves?: string[]; connectKinds?: string[] };
          }
        );
      }
      if (label === "risk") setRisk(payload as RiskData);
    });
    if (failed.length) {
      message.error(`部分看板接口失败：${failed.join("、")}`);
    }
    setLoading(false);
  }, [days, aicallDays, aicallDetailPage, aicallDetailFilters]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const smsPushEmail = (today?.outreach?.byChannel || []).filter((r) =>
    ["SMS", "PUSH", "EMAIL"].includes(String(r.channel))
  );

  return (
    <div style={{ width: "100%", minWidth: 0 }}>
      <Space direction="vertical" size="large" style={{ width: "100%" }}>
        <Card styles={{ body: { padding: 12 } }}>
          <Flex justify="space-between" align="center" wrap="wrap" gap={12}>
            <Flex align="center" gap={12} wrap="wrap">
              <Typography.Text strong style={{ fontSize: 15 }}>
                催收看板
              </Typography.Text>
              <Segmented<ViewKey>
                value={view}
                onChange={setView}
                options={[
                  { label: "今日执行", value: "today" },
                  { label: "复盘", value: "review" }
                ]}
              />
            </Flex>
            <Flex align="center" gap={8} wrap="wrap">
              <Tag color="green">HOT · 打开即查</Tag>
              <Typography.Text type="secondary">
                PHT {today?.phtDate || "—"} · 手动刷新，不自动刷
              </Typography.Text>
              <Button type="primary" onClick={loadAll} loading={loading}>
                刷新
              </Button>
            </Flex>
          </Flex>
        </Card>

        {view === "today" ? (
          <>
            <Card>
              <Flex vertical gap={12}>
                <div>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    今日触达时间线
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    按计划时刻列出今日各渠道结果；未到点显示「尚未到时间」。AI Call 看六层：线路接通 / 真人 / 有效沟通；FAILED 仅线路与我方。
                  </Typography.Text>
                </div>
                <Table
                  rowKey={(r) => `${r.slot}-${r.channel}`}
                  size="small"
                  pagination={false}
                  loading={loading && !today}
                  locale={{ emptyText: "无时段数据" }}
                  dataSource={today?.slots || []}
                  columns={[
                    { title: "时段", dataIndex: "slot", width: 80 },
                    { title: "渠道", dataIndex: "channel", width: 90 },
                    {
                      title: "结果",
                      render: (_: unknown, r: SlotRow) => {
                        if (r.pending) {
                          return <Typography.Text type="secondary">尚未到时间</Typography.Text>;
                        }
                        if (r.channel === "AI_CALL") {
                          const failHot = (r.failedRate ?? 0) > 0.15 && (r.completed ?? 0) >= 20;
                          return (
                            <Flex vertical gap={2}>
                              <span>
                                实拨 {dash(r.completed)} · 线路接通 {dash(r.lineAnswered)} · 真人{" "}
                                {dash(r.human ?? r.aiConnected)} · 有效沟通 {dash(r.effective)}
                              </span>
                              <span>
                                忙线(BUSY) {dash(r.busy)} · 未接(NO_ANSWER) {dash(r.noAnswer)} · 对方侧其他{" "}
                                {dash(r.calleeOther)} · 失败{" "}
                                <Typography.Text type={failHot ? "danger" : undefined}>
                                  {dash(r.failed)} ({pctRate(r.failedRate)})
                                </Typography.Text>{" "}
                                · 信箱/筛选 {dash(r.mailbox ?? r.snr)}
                              </span>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                wave {dash(r.waveKey)} · batch {dash(r.batchId)}
                              </Typography.Text>
                            </Flex>
                          );
                        }
                        const empty = (r.records ?? 0) === 0;
                        if (r.channel === "EMAIL" && r.zeroSendNormal) {
                          return <Tag>正常零发送</Tag>;
                        }
                        return (
                          <span>
                            DELIVERED {empty ? "—" : dash(r.delivered)} / attempted {empty ? "—" : dash(r.attempted)}{" "}
                            · SKIPPED {dash(r.skipped)} · Rate {pctRate(r.deliveryRate)}
                          </span>
                        );
                      }
                    },
                    {
                      title: "下钻（仅本渠道）",
                      render: (_: unknown, r: SlotRow) => {
                        if (r.pending) return "—";
                        if (r.answeredLabels?.length) {
                          return r.answeredLabels.map((s) => (
                            <Tag key={s.label} style={{ marginBottom: 4 }}>
                              {s.label}×{s.count}
                            </Tag>
                          ));
                        }
                        if (r.stageBreakdown?.length) {
                          return r.stageBreakdown.map((s) => (
                            <Tag key={s.stage} style={{ marginBottom: 4 }}>
                              {s.stage}×{s.delivered}
                            </Tag>
                          ));
                        }
                        if (r.slotBreakdown?.length) {
                          return r.slotBreakdown.map((s) => (
                            <Tag key={s.scriptSlot} style={{ marginBottom: 4 }}>
                              {s.scriptSlot}×{s.delivered}
                            </Tag>
                          ));
                        }
                        return "—";
                      }
                    }
                  ]}
                />
              </Flex>
            </Card>

            <Card>
              <Flex vertical gap={12}>
                <div>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    今日触达执行
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    每渠道独立；禁止跨渠道合并送达率。AI Call 口径见上表与线路接通明细。
                  </Typography.Text>
                </div>
                <Row gutter={[16, 16]}>
                  {["SMS", "PUSH", "EMAIL"].map((ch) => {
                    const row = smsPushEmail.find((r) => r.channel === ch);
                    return (
                      <Col xs={24} md={8} key={ch}>
                        <Card size="small" title={ch}>
                          <Statistic title="发送 (records)" value={row?.records ?? 0} />
                          <Typography.Paragraph style={{ marginTop: 8, marginBottom: 0 }}>
                            送达 {dash(row?.delivered)} · 失败 {dash(row?.failed)} · 跳过 {dash(row?.skipped)} ·
                            送达率 {pctRate(row?.deliveryRate)}
                          </Typography.Paragraph>
                        </Card>
                      </Col>
                    );
                  })}
                </Row>
                <Card size="small" title="跳过原因（Guard / CONNECT_AND_STOP / 其他）">
                  <Table
                    rowKey={(r) => `${r.channel}-${r.reason}`}
                    size="small"
                    pagination={false}
                    locale={{ emptyText: "无跳过" }}
                    dataSource={today?.outreach?.skipReasons || []}
                    columns={[
                      { title: "渠道", dataIndex: "channel", width: 100 },
                      { title: "原因", dataIndex: "reason" },
                      { title: "件数", dataIndex: "count", align: "right", width: 80 }
                    ]}
                  />
                </Card>
              </Flex>
            </Card>

            <Card>
              <Flex vertical gap={12}>
                <div>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    AI Call 今日线路接通
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    默认含信箱与未识别对方。列名与 VALUBO 回传一致（party / effective_conversation）。disposition 只在
                    right_party=yes 时有值。
                  </Typography.Text>
                  <AiTodayConnectSummary slots={today?.slots || []} />
                </div>
                <Table
                  rowKey="sessionId"
                  size="small"
                  pagination={false}
                  locale={{ emptyText: "今日无线路接通" }}
                  dataSource={today?.answered || []}
                  columns={answeredColumns({ clientRows: today?.answered || [] })}
                />
              </Flex>
            </Card>

            <Card>
              <Flex vertical gap={12}>
                <div>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    日切与分流断言
                  </Typography.Title>
                  <Typography.Text type="secondary">迁出后再 DELIVERED 必须为 0</Typography.Text>
                </div>
                <Row gutter={[16, 16]}>
                  <Col xs={12} md={6}>
                    <Card size="small">
                      <Statistic title="今日 inbox caseEvent" value={Number(today?.roll?.inbox?.caseEvent ?? 0)} />
                    </Card>
                  </Col>
                  <Col xs={12} md={6}>
                    <Card size="small">
                      <Statistic
                        title="今日 inbox repaymentEvent"
                        value={Number(today?.roll?.inbox?.repaymentEvent ?? 0)}
                      />
                    </Card>
                  </Col>
                  <Col xs={12} md={6}>
                    <Card size="small">
                      <Statistic title="今日新建 plan" value={Number(today?.roll?.newPlans ?? 0)} />
                    </Card>
                  </Col>
                  <Col xs={12} md={6}>
                    <Card size="small">
                      <Statistic title="STAGE_UPGRADE" value={Number(today?.roll?.stageUpgrade?.count ?? 0)} />
                    </Card>
                  </Col>
                </Row>
                <Alert
                  type={today?.roll?.deliveredAfterRoute?.pass === false ? "error" : "success"}
                  showIcon
                  message={`ROUTED_TO_LEGACY ${today?.roll?.routedToLegacy?.count ?? 0} 件；迁出后再 DELIVERED ${today?.roll?.deliveredAfterRoute?.count ?? 0}`}
                />
                <Typography.Text type="secondary">迁出 case_id</Typography.Text>
                <CaseIds ids={today?.roll?.routedToLegacy?.caseIds} />
                {today?.roll?.deliveredAfterRoute?.pass === false && (
                  <>
                    <Typography.Text type="danger">迁出后仍送达（必须处理）</Typography.Text>
                    <CaseIds ids={today?.roll?.deliveredAfterRoute?.caseIds} />
                  </>
                )}
                <Card size="small" title="取消原因">
                  <Flex wrap="wrap" gap={8}>
                    {Object.entries(today?.roll?.cancels || {}).length === 0 ? (
                      <Typography.Text type="secondary">无</Typography.Text>
                    ) : (
                      Object.entries(today?.roll?.cancels || {}).map(([k, v]) => (
                        <Tag key={k}>
                          {k}: {String(v)}
                        </Tag>
                      ))
                    )}
                  </Flex>
                </Card>
              </Flex>
            </Card>

            <Card>
              <Flex vertical gap={12}>
                <div>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    风险信号与异常
                  </Typography.Title>
                  <Typography.Text type="secondary">悬挂 &gt;0 去 Ops Queue / Case Monitor</Typography.Text>
                </div>
                <Row gutter={[16, 16]}>
                  <Col xs={12} md={6}>
                    <Statistic
                      title="AI 悬挂"
                      value={(today?.risk?.hanging || []).length}
                      valueStyle={{
                        color: (today?.risk?.hanging || []).length > 0 ? "#cf1322" : undefined
                      }}
                    />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic
                      title="Guard 拦截（今日）"
                      value={Number(today?.risk?.guardBlocked?.guardBlocked ?? 0)}
                    />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic title="AI EXECUTING 时点" value={Number(today?.risk?.executingAi ?? 0)} />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic title="AI PENDING 已到期" value={Number(today?.risk?.pendingDueAi ?? 0)} />
                  </Col>
                </Row>
                <Flex wrap="wrap" gap={8}>
                  {(today?.risk?.guardByChannel || []).map((g) => (
                    <Tag key={g.channel}>
                      Guard {g.channel}: {g.count}
                    </Tag>
                  ))}
                  <Tag>OPEN 异常 {Number(today?.risk?.exceptions?.open ?? 0)}</Tag>
                  <Tag color="red">INVALID_NUMBER {Number(today?.risk?.disconnect?.invalidNumber ?? 0)}</Tag>
                </Flex>
                <Table
                  rowKey="stepId"
                  size="small"
                  pagination={false}
                  locale={{ emptyText: "无悬挂" }}
                  dataSource={today?.risk?.hanging || []}
                  columns={[
                    { title: "step", dataIndex: "stepId", width: 90 },
                    { title: "plan", dataIndex: "planId", width: 90 },
                    { title: "case", dataIndex: "caseId", width: 90 },
                    {
                      title: "dispatchedAt",
                      dataIndex: "dispatchedAt",
                      render: (v: string) => fmtTs(v)
                    }
                  ]}
                />
              </Flex>
            </Card>
          </>
        ) : (
          <>
            <Card>
              <Flex vertical gap={12}>
                <Flex justify="space-between" wrap="wrap" gap={12}>
                  <div>
                    <Typography.Title level={4} style={{ margin: 0 }}>
                      昨日复盘
                    </Typography.Title>
                    <Typography.Text type="secondary">
                      T+1 看昨天（PHT）· dpd&gt;0 · 不含 SKIPPED / S0 · 催收名单不是账本在催全量
                    </Typography.Text>
                  </div>
                  <Typography.Text type="secondary">
                    业务日 {portfolio?.businessDate || "—"} · 查询 {portfolio?.asOf || "—"}
                  </Typography.Text>
                </Flex>
                <Row gutter={[16, 16]}>
                  <Col xs={12} md={6}>
                    <Statistic
                      title={
                        <Tooltip title="昨天 SMS/PUSH/EMAIL 尝试发出或 AI completed，且动作时 dpd>0 的去重案件。">
                          昨日催收案件
                        </Tooltip>
                      }
                      value={Number(portfolio?.workset?.cases ?? 0)}
                    />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic
                      title={
                        <Tooltip title="催收名单 ∩ 昨日 caseEvent 日切快照的 overdueAmount。投影表只有现值，没有按日余额历史。">
                          昨日日切余额
                        </Tooltip>
                      }
                      value={money(portfolio?.workset?.openingOutstanding)}
                    />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic
                      title={
                        <Tooltip title="催收名单中，昨日 inbox 入站的 repaymentEvent 去重案件（与触达同一自然日）。">
                          有还款案件
                        </Tooltip>
                      }
                      value={Number(portfolio?.workset?.repaidCases ?? 0)}
                    />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic
                      title={
                        <Tooltip title="上述昨日 repaymentEvent 的 paidAmount 合计。">
                          昨日还款金额
                        </Tooltip>
                      }
                      value={money(portfolio?.workset?.repaidAmount)}
                    />
                  </Col>
                </Row>
                <Row gutter={[16, 16]}>
                  <Col xs={24}>
                    <Card size="small" title="触达时点 Stage 分布">
                      <Table
                        rowKey="stage"
                        size="small"
                        pagination={false}
                        dataSource={portfolio?.byStage || []}
                        columns={[
                          { title: "Stage", dataIndex: "stage", width: 80 },
                          { title: "触达案件", dataIndex: "cases", align: "right", width: 96 },
                          {
                            title: "昨日日切余额",
                            dataIndex: "openingOutstanding",
                            align: "right",
                            render: (v: number) => money(v)
                          },
                          {
                            title: "有还款案件",
                            dataIndex: "repaidCases",
                            align: "right",
                            width: 110
                          },
                          {
                            title: "还款金额",
                            dataIndex: "repaidAmount",
                            align: "right",
                            render: (v: number) => money(v)
                          }
                        ]}
                      />
                    </Card>
                  </Col>
                </Row>
              </Flex>
            </Card>

            <Card>
              <Flex vertical gap={12}>
                <Flex justify="space-between" wrap="wrap" gap={12}>
                  <div>
                    <Typography.Title level={4} style={{ margin: 0 }}>
                      渠道质量（分渠道，禁止合并）
                    </Typography.Title>
                    <Typography.Text type="secondary">近 {days} 日 · 矩阵格内率，不跨渠道加总</Typography.Text>
                  </div>
                  <Select
                    value={days}
                    style={{ width: 132 }}
                    options={[
                      { value: 7, label: "近 7 日" },
                      { value: 14, label: "近 14 日" },
                      { value: 30, label: "近 30 日" }
                    ]}
                    onChange={setDays}
                  />
                </Flex>
                <Card size="small" title="按渠道（SMS / PUSH / EMAIL）">
                  <Table
                    rowKey="channel"
                    size="small"
                    pagination={false}
                    dataSource={data?.byChannel || []}
                    columns={[{ title: "渠道", dataIndex: "channel", width: 90 }, ...metricCols]}
                  />
                </Card>
                <Row gutter={[16, 16]}>
                  {["SMS", "PUSH", "EMAIL"].map((ch) => {
                    const series = (dailyByChannel?.series || []).filter((s) => s.channel === ch);
                    return (
                      <Col xs={24} md={8} key={ch}>
                        <Card size="small" title={`${ch} 趋势`}>
                          {series.length === 0 ? (
                            <Typography.Text type="secondary">无数据</Typography.Text>
                          ) : (
                            <TrendSpark series={series} />
                          )}
                        </Card>
                      </Col>
                    );
                  })}
                </Row>
                <Card size="small" title="渠道 × Stage 矩阵（格=Attempted / 率；AI Call 分线路接通与真人接通两行）">
                  {(matrix?.rows || []).length === 0 ? (
                    <Typography.Text type="secondary">暂无矩阵数据</Typography.Text>
                  ) : (
                    <ChannelStageMatrix rows={matrix?.rows || []} />
                  )}
                </Card>
                <Card size="small" title="计划状态分布">
                  <Flex wrap="wrap" gap={8}>
                    {data && Object.keys(data.plans || {}).length > 0 ? (
                      Object.entries(data.plans)
                        .filter(([k]) => k !== "scope")
                        .map(([status, count]) => (
                          <Tag key={status} style={{ margin: 0 }}>
                            {status}: {String(count)}
                          </Tag>
                        ))
                    ) : (
                      <Typography.Text type="secondary">暂无计划数据</Typography.Text>
                    )}
                  </Flex>
                </Card>
              </Flex>
            </Card>

            <Card>
              <Flex vertical gap={12}>
                <Flex justify="space-between" wrap="wrap" gap={12}>
                  <div>
                    <Typography.Title level={4} style={{ margin: 0 }}>
                      AI Call 复盘
                    </Typography.Title>
                    <Typography.Text type="secondary">独立漏斗；FAILED 不含 BUSY / NO_ANSWER</Typography.Text>
                  </div>
                  <Select
                    value={aicallDays}
                    style={{ width: 132 }}
                    options={[
                      { value: 1, label: "今天" },
                      { value: 7, label: "近 7 天" },
                      { value: 30, label: "近 30 天" }
                    ]}
                    onChange={(v) => {
                      setAicallDays(v);
                      setAicallDetailPage(1);
                    }}
                  />
                </Flex>
                <Row gutter={[16, 16]}>
                  <Col xs={12} md={4}>
                    <Statistic title="拨出" value={Number(aicall?.funnel?.dispatched ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="线路接通" value={Number(aicall?.funnel?.answered ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic
                      title="真人接通"
                      value={Number(aicall?.funnel?.human ?? aicall?.funnel?.aiConnected ?? 0)}
                    />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="有效沟通" value={Number(aicall?.funnel?.effective ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="RPC" value={Number(aicall?.funnel?.rpc ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="PTP" value={Number(aicall?.funnel?.ptp ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="信箱/筛选" value={Number(aicall?.funnel?.invalid ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic
                      title="线路接通率"
                      value={
                        aicall?.funnel?.dispatched
                          ? pctRate((aicall?.funnel?.answered ?? 0) / aicall.funnel.dispatched)
                          : "—"
                      }
                    />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic
                      title="真人接通率"
                      value={
                        aicall?.funnel?.dispatched
                          ? pctRate(
                              (aicall?.funnel?.human ?? aicall?.funnel?.aiConnected ?? 0) /
                                aicall.funnel.dispatched
                            )
                          : "—"
                      }
                    />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic
                      title="有效沟通率"
                      value={
                        (aicall?.funnel?.human ?? aicall?.funnel?.aiConnected)
                          ? pctRate(
                              (aicall?.funnel?.effective ?? 0) /
                                (aicall?.funnel?.human ?? aicall?.funnel?.aiConnected ?? 1)
                            )
                          : "—"
                      }
                    />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic
                      title="RPC率（有效沟通分母）"
                      value={
                        aicall?.funnel?.effective
                          ? pctRate((aicall?.funnel?.rpc ?? 0) / aicall.funnel.effective)
                          : "—"
                      }
                    />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic
                      title="PTP率（RPC分母）"
                      value={
                        aicall?.funnel?.rpc
                          ? pctRate((aicall?.funnel?.ptp ?? 0) / aicall.funnel.rpc)
                          : "—"
                      }
                    />
                  </Col>
                </Row>
                <Card size="small" title="按波次">
                  <Table
                    rowKey="waveKey"
                    size="small"
                    pagination={false}
                    dataSource={aicall?.waves || []}
                    scroll={{ x: 1100 }}
                    columns={[
                      {
                        title: "波次",
                        dataIndex: "waveKey",
                        width: 168,
                        render: (v: string) => formatWave(v)
                      },
                      { title: "实拨", dataIndex: "completed", align: "right" },
                      { title: "线路接通", dataIndex: "lineAnswered", align: "right" },
                      {
                        title: "真人",
                        align: "right",
                        render: (_: unknown, r) => dash(r.human ?? r.answered)
                      },
                      { title: "有效沟通", dataIndex: "effective", align: "right" },
                      { title: "忙线", dataIndex: "busy", align: "right" },
                      { title: "未接", dataIndex: "noAnswer", align: "right" },
                      { title: "对方侧其他", dataIndex: "calleeOther", align: "right" },
                      { title: "失败", dataIndex: "failed", align: "right" },
                      {
                        title: "信箱/筛选",
                        align: "right",
                        render: (_: unknown, r) => dash(r.mailbox ?? r.snr)
                      },
                      {
                        title: "失败率",
                        dataIndex: "failedRate",
                        align: "right",
                        render: (v: number | null) => pctRate(v)
                      }
                    ]}
                  />
                </Card>
                <Row gutter={[16, 16]}>
                  <Col xs={24}>
                    <Card size="small" title="RPC 后 disposition（right_party=yes）">
                      <Flex wrap="wrap" gap={8}>
                        {(aicall?.labelDistribution || []).length === 0 ? (
                          <Typography.Text type="secondary">暂无本人收口样本</Typography.Text>
                        ) : (
                          (aicall?.labelDistribution || []).map((l) => (
                            <Tag key={l.label} color={labelColor(l.bucket)} style={{ margin: 0 }}>
                              {l.bucket} · {l.label}: {l.count}
                            </Tag>
                          ))
                        )}
                      </Flex>
                    </Card>
                  </Col>
                  <Col xs={24}>
                    <Card size="small" title="未接通结构（先 failure_class，再细码）">
                      {(aicall?.failureStructure || []).length === 0 ? (
                        <Typography.Text type="secondary">无</Typography.Text>
                      ) : (
                        <BarList
                          data={aicall?.failureStructure || []}
                          labelKey="label"
                          valueKey="count"
                        />
                      )}
                    </Card>
                  </Col>
                </Row>
                <Card size="small" title="线路接通明细">
                  <Table
                    rowKey="sessionId"
                    size="small"
                    locale={{ emptyText: "该窗口无线路接通" }}
                    dataSource={aicallDetail?.items || []}
                    columns={answeredColumns({
                      facets: aicallDetail?.facets,
                      filteredValue: {
                        resultLabel:
                          aicallDetailFilters.resultLabel != null
                            ? [aicallDetailFilters.resultLabel]
                            : null,
                        stageSnapshot:
                          aicallDetailFilters.stage != null ? [aicallDetailFilters.stage] : null,
                        waveKey: aicallDetailFilters.waveKey ? [aicallDetailFilters.waveKey] : null
                      }
                    })}
                    pagination={{
                      current: aicallDetailPage,
                      pageSize: 25,
                      total: aicallDetail?.total ?? 0,
                      showSizeChanger: false
                    }}
                    onChange={(pagination, filters) => {
                      const next = {
                        resultLabel: firstFilter(filters.resultLabel as (string | number)[] | null),
                        stage: firstFilter(filters.stageSnapshot as (string | number)[] | null),
                        waveKey: firstFilter(filters.waveKey as (string | number)[] | null)
                      };
                      const page = pagination.current || 1;
                      setAicallDetailFilters(next);
                      setAicallDetailPage(
                        JSON.stringify(next) === JSON.stringify(aicallDetailFilters) ? page : 1
                      );
                    }}
                  />
                </Card>
              </Flex>
            </Card>

            <Card>
              <Flex vertical gap={12}>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  风险清单（复盘窗口）
                </Typography.Title>
                <Flex gap={24}>
                  <Statistic
                    title="断联 INVALID_NUMBER"
                    value={Number(risk?.disconnect?.invalidNumber ?? 0)}
                    valueStyle={{ color: "#cf1322" }}
                  />
                  <Statistic title="Guard 拦截（全量时点）" value={Number(risk?.guardBlocked?.guardBlocked ?? 0)} />
                </Flex>
                <Table
                  rowKey={(r) => String(r.sessionId || r.session_id || r.caseId || "")}
                  size="small"
                  pagination={false}
                  locale={{ emptyText: "暂无高敏标签" }}
                  dataSource={risk?.highSensitivity || []}
                  tableLayout="fixed"
                  columns={[
                    { title: "案件", dataIndex: "caseId", width: 96 },
                    {
                      title: "标签",
                      width: 100,
                      render: (_: unknown, r: RiskData["highSensitivity"][number]) => (
                        <Tag color="red">{r.resultLabel || r.result_label}</Tag>
                      )
                    },
                    {
                      title: "摘要",
                      dataIndex: "summary",
                      render: (v: string) => <SummaryCell text={v} />
                    },
                    {
                      title: "会话",
                      dataIndex: "sessionId",
                      width: 160,
                      ellipsis: true,
                      render: (v: string, r: RiskData["highSensitivity"][number]) =>
                        v || r.session_id || "—"
                    }
                  ]}
                />
              </Flex>
            </Card>
          </>
        )}
      </Space>
    </div>
  );
}

function labelBucketOf(label?: string): string {
  if (!label) return "未分类";
  if (
    label === "promise_to_pay" ||
    label === "payment_arrangement" ||
    label === "refused" ||
    label === "hardship" ||
    label === "callback" ||
    label === "unresolved"
  ) {
    return "业务结果";
  }
  if (label === "disputed") return "合规风险";
  return "未分类";
}
