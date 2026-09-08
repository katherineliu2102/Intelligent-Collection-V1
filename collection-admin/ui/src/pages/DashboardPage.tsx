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
  batchId?: string;
  waveKey?: string;
  planned?: number;
  completed?: number;
  ringing?: number;
  answered?: number;
  snr?: number;
  aiConnected?: number;
  effective?: number;
  busy?: number;
  noAnswer?: number;
  answerRate?: number | null;
  failedRate?: number | null;
  missing?: boolean;
  failureTop?: { reason: string; count: number }[];
};

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
  answered: {
    sessionId: string;
    caseId: number;
    waveKey?: string;
    answeredAt?: string;
    receivedAt?: string;
    durationSec?: number | null;
    resultLabel?: string;
    summary?: string;
    stageSnapshot?: string;
    dpdSnapshot?: number | null;
  }[];
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
  portfolio: {
    totalCases: number;
    inCollection: number;
    settled: number;
    ceased: number;
    inCollectionOutstanding: number;
    todayRecovered: number;
    todaySettled: number;
  };
  byStage: { stage: string; cases: number; outstanding: number }[];
  byStatus: { status: string; cases: number; outstanding: number }[];
  touchConversion: { touched: number; converted48h: number; settledInWindow?: number };
  todayInbox: { todayInbox: number };
  plans?: Record<string, number | string>;
};

type AiCallData = {
  funnel: {
    dispatched: number;
    ringing?: number;
    answered: number;
    liveAnswered?: number;
    aiConnected: number;
    invalid: number;
  };
  labelDistribution: { label: string; count: number; bucket: string }[];
  sipDistribution: { sipCode: string; count: number }[];
  failureStructure?: { reason: string; count: number }[];
  waves?: {
    waveKey: string;
    slot?: string;
    completed: number;
    answered: number;
    snr: number;
    busy: number;
    noAnswer: number;
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

type AiCallDetailItem = {
  sessionId: string;
  caseId: number;
  answeredAt?: string | null;
  receivedAt?: string | null;
  durationSec?: number | null;
  resultLabel?: string;
  summary?: string;
  stageSnapshot?: string | null;
  dpdSnapshot?: number | null;
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

function fmtDuration(sec?: number | null) {
  if (sec == null || Number.isNaN(Number(sec)) || Number(sec) <= 0) return "—";
  const n = Number(sec);
  const m = Math.floor(n / 60);
  const s = n % 60;
  return m > 0 ? `${m}m${s}s` : `${s}s`;
}

function dash(v?: string | number | null) {
  if (v == null || v === "") return "—";
  return String(v);
}

function SummaryCell({ text }: { text?: string | null }) {
  if (!text) return <>{"—"}</>;
  return (
    <Typography.Paragraph ellipsis={{ rows: 2, tooltip: text }} style={{ marginBottom: 0 }}>
      {text}
    </Typography.Paragraph>
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
              width: 168,
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
  const stages = ["S0", "S1", "S2", "S3", "S4", "UNKNOWN"];
  const channelOrder = ["SMS", "PUSH", "EMAIL", "AI_CALL"];
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
        { title: "渠道 \\ Stage", dataIndex: "", width: 110, render: (v: string) => v },
        ...stages.map((s) => ({
          title: s === "UNKNOWN" ? "未归类" : s,
          align: "right" as const,
          width: 92,
          render: (_: unknown, channel: string) => {
            const c = cell(channel, s);
            const attempted = Number(c?.attempted) || 0;
            const delivered = Number(c?.delivered) || 0;
            const alpha = attempted === 0 ? 0 : 0.15 + 0.75 * (attempted / (maxByRow.get(channel) || 1));
            const rate = attempted > 0 ? delivered / attempted : null;
            return (
              <span
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
  const [aicallDetail, setAicallDetail] = useState<{ total: number; items: AiCallDetailItem[] } | null>(
    null
  );
  const [aicallDetailPage, setAicallDetailPage] = useState(1);
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
      ["aicallDetail", api.dashboardAicallDetail(aicallDetailPage, 25, aicallDays)],
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
        setAicallDetail(payload as { total: number; items: AiCallDetailItem[] });
      }
      if (label === "risk") setRisk(payload as RiskData);
    });
    if (failed.length) {
      message.error(`部分看板接口失败：${failed.join("、")}`);
    }
    setLoading(false);
  }, [days, aicallDays, aicallDetailPage]);

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
                    五槽收口时间线
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    今日 PHT · Email 发送=0 属正常不标红；AI Call 用会话底座，不用 timeline 送达率
                  </Typography.Text>
                </div>
                <Table
                  rowKey={(r) => `${r.slot}-${r.channel}`}
                  size="small"
                  pagination={false}
                  loading={loading && !today}
                  locale={{ emptyText: "无槽位数据" }}
                  dataSource={today?.slots || []}
                  columns={[
                    { title: "PHT 槽", dataIndex: "slot", width: 80 },
                    { title: "渠道", dataIndex: "channel", width: 90 },
                    {
                      title: "收口数字",
                      render: (_: unknown, r: SlotRow) => {
                        if (r.channel === "AI_CALL") {
                          if (r.missing && !r.completed && !r.planned) return "—";
                          const failHot = (r.failedRate ?? 0) > 0.15 && (r.completed ?? 0) >= 20;
                          return (
                            <Flex vertical gap={2}>
                              <span>
                                实拨 {dash(r.completed)} / 计划 {dash(r.planned)} · ANSWERED {dash(r.answered)} ·
                                BUSY {dash(r.busy)} · FAILED{" "}
                                <Typography.Text type={failHot ? "danger" : undefined}>
                                  {dash(r.failed)} ({pctRate(r.failedRate)})
                                </Typography.Text>{" "}
                                · NO_ANSWER {dash(r.noAnswer)} · SNR {dash(r.snr)} · SKIPPED {dash(r.skipped)}
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
                        if (r.failureTop?.length) {
                          return r.failureTop.map((s) => (
                            <Tag key={s.reason} color="red" style={{ marginBottom: 4 }}>
                              {s.reason}×{s.count}
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
                    每渠道独立；禁止跨渠道合并送达率。AI Call 口径见上表与接通明细。
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
                    AI Call 今日接通
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    标签/摘要来自供应商 ai_result，未回传则为空。
                  </Typography.Text>
                </div>
                <Table
                  rowKey="sessionId"
                  size="small"
                  pagination={false}
                  locale={{ emptyText: "今日无接通" }}
                  dataSource={today?.answered || []}
                  columns={[
                    { title: "案件", dataIndex: "caseId", width: 90 },
                    {
                      title: "Stage",
                      dataIndex: "stageSnapshot",
                      width: 70,
                      render: (v: string) => dash(v)
                    },
                    {
                      title: "DPD",
                      dataIndex: "dpdSnapshot",
                      width: 70,
                      align: "right",
                      render: (v: number | null) => dash(v)
                    },
                    { title: "波次", dataIndex: "waveKey", width: 160, render: (v: string) => formatWave(v) },
                    {
                      title: "回调时间",
                      dataIndex: "receivedAt",
                      width: 165,
                      render: (v: string) => fmtTs(v)
                    },
                    {
                      title: "时长",
                      dataIndex: "durationSec",
                      width: 80,
                      align: "right",
                      render: (v: number | null) => fmtDuration(v)
                    },
                    {
                      title: "标签",
                      dataIndex: "resultLabel",
                      width: 160,
                      render: (v: string) =>
                        v ? <Tag color={labelColor(labelBucketOf(v))}>{v}</Tag> : "—"
                    },
                    {
                      title: "摘要",
                      dataIndex: "summary",
                      render: (v: string) => <SummaryCell text={v} />
                    }
                  ]}
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
                      资产组合快照
                    </Typography.Title>
                    <Typography.Text type="secondary">
                      时点存量 · 只看在催 S1–S4（不展示 S0 / stage 为空）
                    </Typography.Text>
                  </div>
                  <Typography.Text type="secondary">快照：{portfolio?.asOf || "—"}</Typography.Text>
                </Flex>
                <Row gutter={[16, 16]}>
                  <Col xs={12} md={6}>
                    <Statistic title="在催案件" value={Number(portfolio?.portfolio?.inCollection ?? 0)} />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic title="OS 在催余额" value={money(portfolio?.portfolio?.inCollectionOutstanding)} />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic title="今日新增 inbox" value={Number(portfolio?.todayInbox?.todayInbox ?? 0)} />
                  </Col>
                  <Col xs={12} md={6}>
                    <Statistic title="今日结清" value={Number(portfolio?.portfolio?.todaySettled ?? 0)} />
                  </Col>
                </Row>
                <Row gutter={[16, 16]}>
                  <Col xs={24}>
                    <Card size="small" title="按 Stage（在催 S1–S4）">
                      <Table
                        rowKey="stage"
                        size="small"
                        pagination={false}
                        dataSource={(portfolio?.byStage || []).filter((r) =>
                          ["S1", "S2", "S3", "S4"].includes(String(r.stage))
                        )}
                        columns={[
                          { title: "Stage", dataIndex: "stage", width: 90 },
                          { title: "案件", dataIndex: "cases", align: "right", width: 96 },
                          {
                            title: "OS 余额",
                            dataIndex: "outstanding",
                            align: "right",
                            render: (v: number) => money(v)
                          }
                        ]}
                      />
                    </Card>
                  </Col>
                </Row>
                <Row gutter={[16, 16]}>
                  <Col xs={12} md={8}>
                    <Statistic title="今日回收" value={money(portfolio?.portfolio?.todayRecovered)} />
                  </Col>
                  <Col xs={12} md={8}>
                    <Statistic
                      title={
                        <Tooltip title="近 7 日去重案件数：SMS/PUSH/EMAIL 成功发出，或 AI 接通。不是触达次数。">
                          近 7 天触达案件
                        </Tooltip>
                      }
                      value={Number(portfolio?.touchConversion?.touched ?? 0)}
                    />
                  </Col>
                  <Col xs={12} md={8}>
                    <Statistic
                      title={
                        <Tooltip title="上述触达案件中，首次触达后 48 小时内 settled_at 有值的比例。热层尚无结清时间时显示 —。">
                          触达→48h 结清
                        </Tooltip>
                      }
                      value={
                        !portfolio?.touchConversion?.touched
                          ? "—"
                          : !portfolio.touchConversion.settledInWindow
                            ? "—"
                            : pctRate(
                                (portfolio.touchConversion.converted48h ?? 0) /
                                  portfolio.touchConversion.touched
                              )
                      }
                    />
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
                <Card size="small" title="渠道 × Stage 矩阵（格=Attempted / 发送或接通率）">
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
                    onChange={setAicallDays}
                  />
                </Flex>
                <Row gutter={[16, 16]}>
                  <Col xs={12} md={4}>
                    <Statistic title="拨出" value={Number(aicall?.funnel?.dispatched ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="接通" value={Number(aicall?.funnel?.liveAnswered ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="真人接续" value={Number(aicall?.funnel?.aiConnected ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic title="接通但无效" value={Number(aicall?.funnel?.invalid ?? 0)} />
                  </Col>
                  <Col xs={12} md={4}>
                    <Statistic
                      title="真人接通率"
                      value={
                        aicall?.funnel?.dispatched
                          ? pctRate((aicall?.funnel?.aiConnected ?? 0) / aicall.funnel.dispatched)
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
                    columns={[
                      {
                        title: "波次",
                        dataIndex: "waveKey",
                        width: 168,
                        render: (v: string) => formatWave(v)
                      },
                      { title: "实拨", dataIndex: "completed", align: "right" },
                      { title: "ANSWERED", dataIndex: "answered", align: "right" },
                      { title: "BUSY", dataIndex: "busy", align: "right" },
                      { title: "FAILED", dataIndex: "failed", align: "right" },
                      { title: "NO_ANSWER", dataIndex: "noAnswer", align: "right" },
                      { title: "SNR", dataIndex: "snr", align: "right" },
                      {
                        title: "FAILED 率",
                        dataIndex: "failedRate",
                        align: "right",
                        render: (v: number | null) => pctRate(v)
                      }
                    ]}
                  />
                </Card>
                <Row gutter={[16, 16]}>
                  <Col xs={24}>
                    <Card size="small" title="接通后结果标签">
                      <Flex wrap="wrap" gap={8}>
                        {(aicall?.labelDistribution || []).length === 0 ? (
                          <Typography.Text type="secondary">暂无接通样本</Typography.Text>
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
                    <Card size="small" title="FAILED 结构（不含 BUSY/NO_ANSWER）">
                      {(aicall?.failureStructure || []).length === 0 ? (
                        <Typography.Text type="secondary">无</Typography.Text>
                      ) : (
                        <BarList
                          data={aicall?.failureStructure || []}
                          labelKey="reason"
                          valueKey="count"
                        />
                      )}
                    </Card>
                  </Col>
                </Row>
                <Card size="small" title="接通明细">
                  <Table
                    rowKey="sessionId"
                    size="small"
                    dataSource={aicallDetail?.items || []}
                    pagination={{
                      current: aicallDetailPage,
                      pageSize: 25,
                      total: aicallDetail?.total ?? 0,
                      showSizeChanger: false,
                      onChange: (p) => setAicallDetailPage(p)
                    }}
                    columns={[
                      { title: "案件", dataIndex: "caseId", width: 90 },
                      {
                        title: "波次",
                        dataIndex: "waveKey",
                        width: 160,
                        render: (v: string) => formatWave(v)
                      },
                      {
                        title: "Stage",
                        dataIndex: "stageSnapshot",
                        width: 70,
                        render: (v: string) => dash(v)
                      },
                      {
                        title: "DPD",
                        dataIndex: "dpdSnapshot",
                        width: 70,
                        align: "right",
                        render: (v: number | null) => dash(v)
                      },
                      {
                        title: "回调时间",
                        dataIndex: "receivedAt",
                        width: 165,
                        render: (v: string) => fmtTs(v)
                      },
                      {
                        title: "时长",
                        dataIndex: "durationSec",
                        width: 90,
                        align: "right",
                        render: (v: number | null) => fmtDuration(v)
                      },
                      {
                        title: "标签",
                        dataIndex: "resultLabel",
                        width: 160,
                        render: (v: string) =>
                          v ? <Tag color={labelColor(labelBucketOf(v))}>{v}</Tag> : "—"
                      },
                      {
                        title: "摘要",
                        dataIndex: "summary",
                        render: (v: string) => <SummaryCell text={v} />
                      }
                    ]}
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
    label === "follow_up_required" ||
    label === "vague_commitment" ||
    label === "refused_to_pay" ||
    label === "refused_to_discuss"
  ) {
    return "业务结果";
  }
  if (label === "dispute") return "合规风险";
  return "未分类";
}
