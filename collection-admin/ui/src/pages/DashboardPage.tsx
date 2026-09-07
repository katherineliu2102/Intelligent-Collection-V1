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

type OutreachRow = {
  channel?: string;
  stage?: string;
  scriptSlot?: string;
  templateId?: number;
  templateChannel?: string;
  records: number;
  attempted: number;
  sent?: number;
  delivered: number;
  failed: number;
  skipped: number;
  other: number;
  deliveryRate: number;
};

type OutreachData = {
  layer: string;
  freshness: string;
  windowDays: number;
  from: string;
  to: string;
  summary: {
    totalRecords: number;
    totalAttempted: number;
    totalSent?: number;
    delivered: number;
    failed: number;
    skipped: number;
    other: number;
    deliveryRate: number;
  };
  byChannel: OutreachRow[];
  byStage: OutreachRow[];
  byResult: { result: string; count: number }[];
  exceptions: Record<string, number | string>;
  plans: Record<string, number | string>;
};

type PortfolioData = {
  layer: string;
  freshness: string;
  asOf: string;
  portfolio: {
    totalCases: number;
    inCollection: number;
    settled: number;
    ceased: number;
    inCollectionOutstanding: number;
    totalOutstanding: number;
    todayRecovered: number;
    todaySettled: number;
  };
  byStage: { stage: string; cases: number; outstanding: number }[];
  byStatus: { status: string; cases: number; outstanding: number }[];
  touchConversion: { touched: number; converted48h: number };
  todayInbox: { todayInbox: number };
};

type AiCallData = {
  layer: string;
  freshness: string;
  windowDays: number;
  from: string;
  to: string;
  funnel: {
    dispatched: number;
    answered: number;
    aiConnected: number;
    invalid: number;
  };
  labelDistribution: { label: string; count: number; bucket: string }[];
  sipDistribution: { sipCode: string; count: number }[];
};

type AgingData = {
  layer: string;
  freshness: string;
  asOf: string;
  buckets: { bucket: string; cases: number; outstanding: number }[];
};

type MatrixRow = {
  channel: string;
  stage: string;
  records: number;
  attempted: number;
  delivered: number;
};

type MatrixData = {
  layer: string;
  freshness: string;
  windowDays: number;
  rows: MatrixRow[];
};

type DailyPoint = {
  day: string;
  records: number;
  attempted: number;
  delivered: number;
};

type DailyData = {
  layer: string;
  freshness: string;
  windowDays: number;
  series: DailyPoint[];
};

type AiCallDetailItem = {
  sessionId: string;
  caseId: number;
  answeredAt: string;
  endedAt: string;
  durationSec: number;
  resultLabel: string;
  summary: string;
  stageSnapshot: string;
  dpdSnapshot: number;
};

type AiCallDetailData = {
  layer: string;
  freshness: string;
  windowDays: number;
  page: number;
  pageSize: number;
  total: number;
  items: AiCallDetailItem[];
};

type RiskData = {
  layer: string;
  freshness: string;
  highSensitivity: {
    sessionId: string;
    caseId: number;
    resultLabel: string;
    summary: string;
    receivedAt: string;
  }[];
  disconnect: { invalidNumber: number };
  guardBlocked: { guardBlocked: number };
  hanging: {
    stepId: number;
    planId: number;
    stepOrder: number;
    executedAt: string;
    dispatchedAt: string;
  }[];
};

type ViewKey = "executive" | "collection" | "strategy";

/* ============================= 工具 ============================= */

function pct(v: number) {
  return `${(v * 100).toFixed(1)}%`;
}

function money(v?: number | string) {
  const n = Number(v);
  if (v == null || v === "" || Number.isNaN(n)) return "—";
  return "₱" + n.toLocaleString("en-PH", { maximumFractionDigits: 0 });
}

function fmtTs(v?: string) {
  if (!v) return "—";
  return String(v).slice(0, 19).replace("T", " ");
}

function fmtDuration(sec?: number) {
  if (sec == null || Number.isNaN(sec) || sec < 0) return "—";
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? `${m}m${s}s` : `${s}s`;
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

/** 纯 CSS 横向条形图（零依赖，可视化分布，避免引入重型图表库）。 */
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
    <Flex vertical gap={6}>
      {data.map((d, i) => (
        <Flex key={i} align="center" gap={8}>
          <div style={{ width: 72, textAlign: "right", flexShrink: 0, fontSize: 12 }}>
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

/** TrendSpark：纯 CSS 迷你趋势（每日一根柱，蓝=attempted，绿=delivered），抑制"看个位数就开火"。 */
function TrendSpark({ series }: { series: DailyPoint[] }) {
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
            title={`${String(d.day).slice(0, 10)} · attempted ${attempted} · delivered ${delivered} · records ${d.records}`}
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

/** 渠道 × Stage 矩阵：行=渠道、列=Stage，格=Attempted（底色深浅按行内最大值渐变）。 */
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
            const alpha = attempted === 0 ? 0 : 0.15 + 0.75 * (attempted / (maxByRow.get(channel) || 1));
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
                {attempted || "—"}
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
  { title: "Delivered", dataIndex: "delivered", width: 84, align: "right" },
  { title: "Failed", dataIndex: "failed", width: 68, align: "right" },
  { title: "Skipped", dataIndex: "skipped", width: 76, align: "right" },
  { title: "未归类", dataIndex: "other", width: 68, align: "right" },
  {
    title: <Tooltip title="Delivered / Attempted（SKIPPED 不计入分母）">Rate</Tooltip>,
    dataIndex: "deliveryRate",
    width: 72,
    align: "right",
    render: (v: number) => pct(v)
  }
];

/** 模块可见性（persona 视图折叠）。 */
const VIEW_MODULES: Record<ViewKey, string[]> = {
  executive: ["portfolio", "recovery"],
  collection: ["portfolio", "queue", "outreach", "aicall", "risk"],
  strategy: ["queue", "outreach", "aicall", "risk"]
};

/* ============================= 组件 ============================= */

export function DashboardPage() {
  const [view, setView] = useState<ViewKey>("executive");
  const [loading, setLoading] = useState(false);
  const [days, setDays] = useState(7);

  const [data, setData] = useState<OutreachData | null>(null);
  const [portfolio, setPortfolio] = useState<PortfolioData | null>(null);
  const [aging, setAging] = useState<AgingData | null>(null);
  const [matrix, setMatrix] = useState<MatrixData | null>(null);
  const [daily, setDaily] = useState<DailyData | null>(null);
  const [aicall, setAicall] = useState<AiCallData | null>(null);
  const [aicallDays, setAicallDays] = useState(7);
  const [aicallDetail, setAicallDetail] = useState<AiCallDetailData | null>(null);
  const [aicallDetailPage, setAicallDetailPage] = useState(1);
  const [risk, setRisk] = useState<RiskData | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await api.dashboardOutreachRealtime(days);
      setData(resp.data as OutreachData);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  }, [days]);

  const loadPortfolio = useCallback(async () => {
    try {
      const resp = await api.dashboardPortfolio();
      setPortfolio(resp.data as PortfolioData);
    } catch (e: any) {
      console.warn("portfolio load failed", e);
    }
  }, []);

  const loadAging = useCallback(async () => {
    try {
      const resp = await api.dashboardAging();
      setAging(resp.data as AgingData);
    } catch (e: any) {
      console.warn("aging load failed", e);
    }
  }, []);

  const loadMatrix = useCallback(async () => {
    try {
      const resp = await api.dashboardMatrix(days);
      setMatrix(resp.data as MatrixData);
    } catch (e: any) {
      console.warn("matrix load failed", e);
    }
  }, [days]);

  const loadDaily = useCallback(async () => {
    try {
      const resp = await api.dashboardDaily(days);
      setDaily(resp.data as DailyData);
    } catch (e: any) {
      console.warn("daily load failed", e);
    }
  }, [days]);

  const loadAicall = useCallback(async () => {
    try {
      const resp = await api.dashboardAicallRealtime(aicallDays);
      setAicall(resp.data as AiCallData);
    } catch (e: any) {
      console.warn("aicall load failed", e);
    }
  }, [aicallDays]);

  const loadAicallDetail = useCallback(async () => {
    try {
      const resp = await api.dashboardAicallDetail(aicallDetailPage, 25, aicallDays);
      setAicallDetail(resp.data as AiCallDetailData);
    } catch (e: any) {
      console.warn("aicall detail load failed", e);
    }
  }, [aicallDetailPage, aicallDays]);

  const loadRisk = useCallback(async () => {
    try {
      const resp = await api.dashboardRisk();
      setRisk(resp.data as RiskData);
    } catch (e: any) {
      console.warn("risk load failed", e);
    }
  }, []);

  useEffect(() => {
    load();
    loadPortfolio();
    loadAging();
    loadMatrix();
    loadDaily();
    loadAicall();
    loadAicallDetail();
    loadRisk();
  }, [
    load,
    loadPortfolio,
    loadAging,
    loadMatrix,
    loadDaily,
    loadAicall,
    loadAicallDetail,
    loadRisk
  ]);

  const modules = VIEW_MODULES[view];
  const show = (key: string) => modules.includes(key);

  const empty = !loading && data && (data?.summary?.totalRecords ?? 0) === 0;

  return (
    <div style={{ width: "100%", minWidth: 0 }}>
      <Space direction="vertical" size="large" style={{ width: "100%" }}>
        {/* ── 控制条 ── */}
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
                  { label: "经营", value: "executive" },
                  { label: "催收", value: "collection" },
                  { label: "策略", value: "strategy" }
                ]}
              />
            </Flex>
            <Flex align="center" gap={8} wrap="wrap">
              <Tag color="green">HOT · 热层实时</Tag>
              <Typography.Text type="secondary">PHT 今日口径</Typography.Text>
              <Button onClick={load} loading={loading}>
                刷新
              </Button>
            </Flex>
          </Flex>
        </Card>

        {/* ── §资产组合快照（时点） ── */}
        {show("portfolio") && (
          <Card>
            <Flex vertical gap={12}>
              <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
                <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    资产组合快照
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    时点型（截至现在）· t_ai_collection 投影
                  </Typography.Text>
                </div>
                {portfolio && (
                  <Typography.Text type="secondary">快照：{portfolio.asOf}</Typography.Text>
                )}
              </Flex>

              <Row gutter={[16, 16]}>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="在催案件" value={Number(portfolio?.portfolio?.inCollection ?? 0)} />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="OS 在催余额"
                      value={money(portfolio?.portfolio?.inCollectionOutstanding)}
                    />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Tooltip title="今日 PHT 进入收件箱的 caseEvent 数（入口流量，非当前池子存量）">
                      <Statistic
                        title="今日新增 inbox"
                        value={Number(portfolio?.todayInbox?.todayInbox ?? 0)}
                        valueStyle={{ color: "#1677ff" }}
                      />
                    </Tooltip>
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="今日结清"
                      value={Number(portfolio?.portfolio?.todaySettled ?? 0)}
                      valueStyle={{ color: "#3f8600" }}
                    />
                  </Card>
                </Col>
              </Row>

              <Row gutter={[16, 16]}>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="今日回收"
                      value={money(portfolio?.portfolio?.todayRecovered)}
                      valueStyle={{ color: "#3f8600" }}
                    />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="已结清（存量）" value={Number(portfolio?.portfolio?.settled ?? 0)} />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="停催（存量）" value={Number(portfolio?.portfolio?.ceased ?? 0)} />
                  </Card>
                </Col>
              </Row>

              <Row gutter={[16, 16]}>
                <Col xs={24} lg={12}>
                  <Card size="small" title="按 Stage（案件在往哪走）">
                    <Table
                      rowKey="stage"
                      size="small"
                      pagination={false}
                      locale={{ emptyText: "无数据" }}
                      dataSource={portfolio?.byStage || []}
                      columns={[
                        {
                          title: (
                            <Tooltip title="无 stage：通常为已结清/出窗案件，统计上不归属任何 stage">
                              Stage (含未归类)
                            </Tooltip>
                          ),
                          dataIndex: "stage",
                          width: 90
                        },
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
                <Col xs={24} lg={12}>
                  <Card size="small" title="按 collection_status">
                    <Flex wrap="wrap" gap={8} align="center">
                      {(portfolio?.byStatus || []).map((s) => (
                        <Tag
                          key={s.status}
                          color={s.status === "SETTLED" ? "green" : s.status === "CEASED" ? "default" : "blue"}
                          style={{ margin: 0 }}
                        >
                          {s.status}: {s.cases}
                        </Tag>
                      ))}
                    </Flex>
                    <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                      在催案件（346 量级）是当前池子存量；今日新增 inbox（261 量级）是入口流量，两者是不同事物，不可对账。
                    </Typography.Paragraph>
                  </Card>
                </Col>
              </Row>
              <Row gutter={[16, 16]}>
                <Col xs={24}>
                  <Card size="small" title="计划快照（ContactPlan 状态分布）">
                    <Flex wrap="wrap" gap={8} align="center">
                      {data && Object.keys(data.plans || {}).length > 0 ? (
                        Object.entries(data.plans)
                          .filter(([k]) => k !== "scope")
                          .map(([status, count]) => (
                            <Tag
                              key={status}
                              color={
                                status === "ACTIVE"
                                  ? "blue"
                                  : status === "EXHAUSTED" || status === "CANCELLED"
                                    ? "default"
                                    : "green"
                              }
                              style={{ margin: 0 }}
                            >
                              {status}: {String(count)}
                            </Tag>
                          ))
                      ) : (
                        <Typography.Text type="secondary">暂无计划数据</Typography.Text>
                      )}
                    </Flex>
                    <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                      计划是「在催」的下游指标（全量口径，非窗口内），与渠道触达表分列展示。
                    </Typography.Paragraph>
                  </Card>
                </Col>
              </Row>
            </Flex>
          </Card>
        )}

        {/* ── §回收成效（区间） ── */}
        {show("recovery") && (
          <Card>
            <Flex vertical gap={12}>
              <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
                <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    回收成效
                  </Typography.Title>
                  <Typography.Text type="secondary">区间型 · 触达 → 还款转化</Typography.Text>
                </div>
                <Select
                  value={days}
                  style={{ width: 132 }}
                  options={[
                    { value: 1, label: "今日" },
                    { value: 7, label: "近 7 日" },
                    { value: 30, label: "近 30 日" }
                  ]}
                  onChange={setDays}
                />
              </Flex>

              <Row gutter={[16, 16]}>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="今日回收"
                      value={money(portfolio?.portfolio?.todayRecovered)}
                      valueStyle={{ color: "#3f8600" }}
                    />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="近 7 天触达"
                      value={Number(portfolio?.touchConversion?.touched ?? 0)}
                      valueStyle={{ color: "#1677ff" }}
                    />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="触达→48h 还款"
                      value={
                        portfolio?.touchConversion?.touched
                          ? pct(
                              (portfolio?.touchConversion?.converted48h ?? 0) /
                                portfolio?.touchConversion?.touched
                            )
                          : "—"
                      }
                      valueStyle={{ color: "#1677ff" }}
                    />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="今日结清" value={Number(portfolio?.portfolio?.todaySettled ?? 0)} />
                  </Card>
                </Col>
              </Row>

              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                回收率（CEI）= 近 N 日实收 ÷ (期初 OS + 期间新增 OS) 需冷层期初余额，热层暂以「今日回收 / 今日结清 / 触达→48h 转化」呈现回收画面。
              </Typography.Paragraph>
            </Flex>
          </Card>
        )}

        {/* ── §队列与迁徙（时点分布 + 滚动） ── */}
        {show("queue") && (
          <Card>
            <Flex vertical gap={12}>
              <div style={{ minWidth: 0 }}>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  队列与迁徙
                </Typography.Title>
                <Typography.Text type="secondary">
                  时点型 · Aging 桶分布（Roll Rate 需 stage 历史快照表，推迟 Phase 2）
                </Typography.Text>
              </div>

              <Row gutter={[16, 16]}>
                <Col xs={24}>
                  <Card size="small" title="Aging Bucket 分布（在催案件，0-30 / 31-60 / 61-90 / 91+）">
                    {(aging?.buckets || []).length === 0 ? (
                      <Typography.Text type="secondary">暂无数据</Typography.Text>
                    ) : (
                      <Table
                        rowKey="bucket"
                        size="small"
                        pagination={false}
                        dataSource={aging?.buckets || []}
                        columns={[
                          { title: "DPD 桶", dataIndex: "bucket", width: 120 },
                          {
                            title: "案件",
                            dataIndex: "cases",
                            align: "right",
                            width: 120,
                            sorter: (a, b) => a.cases - b.cases
                          },
                          {
                            title: "OS 余额",
                            dataIndex: "outstanding",
                            align: "right",
                            render: (v: number) => money(v)
                          }
                        ]}
                      />
                    )}
                  </Card>
                </Col>
              </Row>
            </Flex>
          </Card>
        )}

        {/* ── §触达执行（区间） ── */}
        {show("outreach") && (
          <Card>
            <Flex vertical gap={12}>
              <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
                <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    触达执行
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    区间型 · 按渠道独立（SMS / PUSH / EMAIL / AI_CALL 语义不同）
                  </Typography.Text>
                </div>
                <Select
                  value={days}
                  style={{ width: 132 }}
                  options={[
                    { value: 1, label: "今日" },
                    { value: 7, label: "近 7 日" },
                    { value: 14, label: "近 14 日" },
                    { value: 30, label: "近 30 日" },
                    { value: 90, label: "近 90 日" }
                  ]}
                  onChange={setDays}
                />
              </Flex>

              {data && (
                <Typography.Text type="secondary" style={{ wordBreak: "break-all" }}>
                  窗口：{data.from} → {data.to}
                </Typography.Text>
              )}

              {empty && (
                <Alert
                  type="info"
                  showIcon
                  message="当前时间窗口内无触达记录"
                  description="测试数据最近写入约在数天前。请切换到「近 30 天」或「近 90 天」；或在 Case Monitor 查看具体案件 timeline。"
                />
              )}

              <Row gutter={[16, 16]}>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="Timeline 记录" value={data?.summary?.totalRecords ?? 0} />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title={<Tooltip title="不含 SKIPPED（未实际发送）">实际发送</Tooltip>}
                      value={data?.summary?.totalAttempted ?? data?.summary?.totalSent ?? 0}
                    />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="送达率"
                      value={((data?.summary?.deliveryRate ?? 0) * 100).toFixed(1)}
                      suffix="%"
                      valueStyle={{ color: "#3f8600" }}
                    />
                  </Card>
                </Col>
                <Col xs={12} md={6}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title={<Tooltip title="全量 OPEN 异常（非窗口内）">待处理异常</Tooltip>}
                      value={Number(data?.exceptions?.open ?? 0)}
                    />
                  </Card>
                </Col>
              </Row>

              <Row gutter={[16, 16]}>
                <Col xs={24}>
                  <Card size="small" title="结果分布">
                    <Flex wrap="wrap" gap={8} align="center">
                      {(data?.byResult || []).length === 0 && !loading ? (
                        <Typography.Text type="secondary">无数据</Typography.Text>
                      ) : (
                        (data?.byResult || []).map((r) => (
                          <Tag key={r.result} color={resultColor(r.result)} style={{ margin: 0 }}>
                            {r.result}: {r.count}
                          </Tag>
                        ))
                      )}
                    </Flex>
                    <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                      送达率 = Delivered ÷ Attempted；未归类 = 结果不在 7 种已知枚举或 NULL，均不计入送达率分母。
                    </Typography.Paragraph>
                  </Card>
                </Col>
              </Row>

              <Row gutter={[16, 16]}>
                <Col xs={24}>
                  <Card size="small" title={`近 ${days} 日触达趋势（蓝=发起，绿=送达）`}>
                    {(daily?.series || []).length === 0 ? (
                      <Typography.Text type="secondary">暂无趋势数据</Typography.Text>
                    ) : (
                      <TrendSpark series={daily?.series || []} />
                    )}
                  </Card>
                </Col>
                <Col xs={24}>
                  <Card size="small" title="渠道 × Stage 矩阵（Attempted，含 AI_CALL）">
                    {(matrix?.rows || []).length === 0 ? (
                      <Typography.Text type="secondary">暂无矩阵数据</Typography.Text>
                    ) : (
                      <ChannelStageMatrix rows={matrix?.rows || []} />
                    )}
                  </Card>
                </Col>
              </Row>

              <Row gutter={[16, 16]}>
                <Col xs={24}>
                  <Card size="small" title="按渠道">
                    <Table
                      rowKey="channel"
                      size="small"
                      pagination={false}
                      scroll={{ x: 720 }}
                      locale={{ emptyText: "无数据" }}
                      dataSource={data?.byChannel || []}
                      columns={[
                        { title: "渠道", dataIndex: "channel", width: 90, fixed: "left" },
                        ...metricCols
                      ]}
                    />
                  </Card>
                </Col>
                <Col xs={24}>
                  <Card size="small" title="按 Stage">
                    <Table
                      rowKey="stage"
                      size="small"
                      pagination={false}
                      scroll={{ x: 720 }}
                      locale={{ emptyText: "无数据" }}
                      dataSource={data?.byStage || []}
                      columns={[
                        { title: "Stage", dataIndex: "stage", width: 80, fixed: "left" },
                        ...metricCols
                      ]}
                    />
                  </Card>
                </Col>
              </Row>
            </Flex>
          </Card>
        )}

        {/* ── §AI Call 业务结果（区间） ── */}
        {show("aicall") && (
          <Card>
            <Flex vertical gap={12}>
              <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
                <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    AI Call 业务结果
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    区间型 · t_ai_call_session 会话底座
                  </Typography.Text>
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
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="拨出" value={Number(aicall?.funnel?.dispatched ?? 0)} />
                  </Card>
                </Col>
                <Col xs={12} md={4}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="接通" value={Number(aicall?.funnel?.answered ?? 0)} />
                  </Card>
                </Col>
                <Col xs={12} md={4}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="真人接续" value={Number(aicall?.funnel?.aiConnected ?? 0)} />
                  </Card>
                </Col>
                <Col xs={12} md={4}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic title="接通但无效" value={Number(aicall?.funnel?.invalid ?? 0)} />
                  </Card>
                </Col>
                <Col xs={12} md={4}>
                  <Card size="small" styles={{ body: { padding: 16 } }}>
                    <Statistic
                      title="真人接通率"
                      value={
                        aicall?.funnel?.dispatched
                          ? pct((aicall?.funnel?.aiConnected ?? 0) / aicall?.funnel?.dispatched)
                          : "—"
                      }
                    />
                  </Card>
                </Col>
              </Row>

              <Row gutter={[16, 16]}>
                <Col xs={24} lg={12}>
                  <Card size="small" title="接通后结果标签（三桶）">
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
                <Col xs={24} lg={12}>
                  <Card size="small" title="SIP 码分布（渠道卫生层）">
                    {(aicall?.sipDistribution || []).length === 0 ? (
                      <Typography.Text type="secondary">暂无 SIP 数据</Typography.Text>
                    ) : (
                      <BarList
                        data={aicall?.sipDistribution || []}
                        labelKey="sipCode"
                        valueKey="count"
                      />
                    )}
                  </Card>
                </Col>
              </Row>

              <Card size="small" title="接通明细（case 级时序）">
                <Table
                  rowKey="sessionId"
                  size="small"
                  loading={false}
                  locale={{ emptyText: "暂无接通明细" }}
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
                    { title: "Stage", dataIndex: "stageSnapshot", width: 70 },
                    { title: "DPD", dataIndex: "dpdSnapshot", width: 70, align: "right" },
                    {
                      title: "接通时间",
                      dataIndex: "answeredAt",
                      width: 165,
                      render: (v: string) => fmtTs(v)
                    },
                    {
                      title: "时长",
                      dataIndex: "durationSec",
                      width: 90,
                      align: "right",
                      render: (v: number) => fmtDuration(v)
                    },
                    {
                      title: "标签",
                      dataIndex: "resultLabel",
                      width: 160,
                      render: (v: string) =>
                        v ? <Tag color={labelColor(labelBucketOf(v))}>{v}</Tag> : "—"
                    },
                    { title: "摘要", dataIndex: "summary", ellipsis: true }
                  ]}
                />
              </Card>
            </Flex>
          </Card>
        )}

        {/* ── §风险信号与异常（最末） ── */}
        {show("risk") && (
          <Card>
            <Flex vertical gap={12}>
              <Flex justify="space-between" align="center" wrap="wrap" gap={12}>
                <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    风险信号与异常
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    高敏标签（dispute 等）仅展示 + 提醒，不自动暂停（§5.1.7）
                  </Typography.Text>
                </div>
                <Flex gap={24} align="center">
                  <Statistic
                    title="断联（INVALID_NUMBER）"
                    value={Number(risk?.disconnect?.invalidNumber ?? 0)}
                    valueStyle={{ color: "#cf1322" }}
                  />
                  <Statistic
                    title="Guard 拦截"
                    value={Number(risk?.guardBlocked?.guardBlocked ?? 0)}
                    valueStyle={{ color: "#d46b08" }}
                  />
                </Flex>
              </Flex>

              <Card size="small" title="高敏标签清单（最近 20 条）">
                <Table
                  rowKey="sessionId"
                  size="small"
                  pagination={false}
                  locale={{ emptyText: "暂无高敏标签" }}
                  dataSource={risk?.highSensitivity || []}
                  columns={[
                    { title: "会话", dataIndex: "sessionId", ellipsis: true, width: 180 },
                    { title: "案件", dataIndex: "caseId", width: 90 },
                    {
                      title: "标签",
                      dataIndex: "resultLabel",
                      width: 90,
                      render: (v: string) => <Tag color="red">{v}</Tag>
                    },
                    { title: "摘要", dataIndex: "summary", ellipsis: true },
                    {
                      title: "时间",
                      dataIndex: "receivedAt",
                      width: 170,
                      render: (v: string) => fmtTs(v)
                    }
                  ]}
                />
              </Card>

              <Card size="small" title="悬挂会话（AI Call 超 15 分钟未收口）">
                <Table
                  rowKey="stepId"
                  size="small"
                  pagination={false}
                  locale={{ emptyText: "无悬挂会话" }}
                  dataSource={risk?.hanging || []}
                  columns={[
                    { title: "步骤", dataIndex: "stepId", width: 90 },
                    { title: "计划", dataIndex: "planId", width: 90 },
                    {
                      title: "执行时间",
                      dataIndex: "executedAt",
                      width: 170,
                      render: (v: string) => fmtTs(v)
                    },
                    {
                      title: "受理时间",
                      dataIndex: "dispatchedAt",
                      width: 170,
                      render: (v: string) => fmtTs(v)
                    }
                  ]}
                />
              </Card>
            </Flex>
          </Card>
        )}
      </Space>
    </div>
  );
}

/** result_label 三桶分类（与后端 labelBucket 对齐，前端渲染用）。 */
function labelBucketOf(label?: string): string {
  if (!label) return "未分类";
  if (
    label === "promise_to_pay" ||
    label === "follow_up_required" ||
    label === "vague_commitment"
  ) {
    return "业务结果";
  }
  if (label === "dispute") return "合规风险";
  return "未分类";
}
