import {
  Alert,
  Button,
  Card,
  Col,
  Flex,
  Row,
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
    totalOutstanding: number;
    todayRecovered: number;
  };
  byStage: { stage: string; cases: number; outstanding: number }[];
  byStatus: { status: string; cases: number; outstanding: number }[];
  touchConversion: { touched: number; converted48h: number };
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

function pct(v: number) {
  return `${(v * 100).toFixed(1)}%`;
}

function money(v?: number | string) {
  const n = Number(v);
  if (v == null || v === "" || Number.isNaN(n)) return "—";
  return "₱" + n.toLocaleString("en-PH", { maximumFractionDigits: 0 });
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
  { title: "Other", dataIndex: "other", width: 68, align: "right" },
  {
    title: <Tooltip title="Delivered / Attempted（SKIPPED 不计入分母）">Rate</Tooltip>,
    dataIndex: "deliveryRate",
    width: 72,
    align: "right",
    render: (v: number) => pct(v)
  }
];

export function DashboardPage() {
  const [loading, setLoading] = useState(false);
  const [days, setDays] = useState(30);
  const [data, setData] = useState<OutreachData | null>(null);
  const [portfolio, setPortfolio] = useState<PortfolioData | null>(null);
  const [aicall, setAicall] = useState<AiCallData | null>(null);
  const [aicallDays, setAicallDays] = useState(7);
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
      // 组合概况加载失败不阻断触达看板
      console.warn("portfolio load failed", e);
    }
  }, []);

  const loadAicall = useCallback(async () => {
    try {
      const resp = await api.dashboardAicallRealtime(aicallDays);
      setAicall(resp.data as AiCallData);
    } catch (e: any) {
      console.warn("aicall load failed", e);
    }
  }, [aicallDays]);

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
    loadAicall();
    loadRisk();
  }, [load, loadPortfolio, loadAicall, loadRisk]);

  const empty = !loading && data && (data.summary.totalRecords ?? 0) === 0;
  const attempted = data?.summary.totalAttempted ?? data?.summary.totalSent ?? 0;

  return (
    <div style={{ width: "100%", minWidth: 0 }}>
      <Space direction="vertical" size="large" style={{ width: "100%" }}>
        {/* 催收组合概况（§5.1.1 回收 / §5.1.2 迁徙，催收管理视角置顶） */}
        <Card>
          <Flex vertical gap={12}>
            <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
              <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  催收组合概况 · 回收与迁徙
                </Typography.Title>
                <Typography.Text type="secondary">
                  热层 · t_ai_collection 投影实时（催收管理视角 · 第一屏）
                </Typography.Text>
              </div>
              {portfolio && (
                <Typography.Text type="secondary">快照：{portfolio.asOf}</Typography.Text>
              )}
            </Flex>

            <Row gutter={[16, 16]}>
              <Col xs={12} md={6}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic title="在催案件" value={Number(portfolio?.portfolio.inCollection ?? 0)} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic
                    title="近 7 天触达"
                    value={Number(portfolio?.touchConversion.touched ?? 0)}
                    valueStyle={{ color: "#1677ff" }}
                  />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic title="OS 在催余额" value={money(portfolio?.portfolio.totalOutstanding)} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic
                    title="今日回收"
                    value={money(portfolio?.portfolio.todayRecovered)}
                    valueStyle={{ color: "#3f8600" }}
                  />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic title="已结清" value={Number(portfolio?.portfolio.settled ?? 0)} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic
                    title="触达→48h 还款"
                    value={
                      portfolio?.touchConversion.touched
                        ? pct(
                            (portfolio?.touchConversion.converted48h ?? 0) /
                              portfolio?.touchConversion.touched
                          )
                        : "—"
                    }
                    valueStyle={{ color: "#1677ff" }}
                  />
                </Card>
              </Col>
            </Row>

            <Row gutter={[16, 16]}>
              <Col xs={24}>
                <Card size="small" title="按 Stage（案件在往哪走）">
                  <Table
                    rowKey="stage"
                    size="small"
                    pagination={false}
                    locale={{ emptyText: "无数据" }}
                    dataSource={portfolio?.byStage || []}
                    columns={[
                      { title: "Stage", dataIndex: "stage", width: 72 },
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

            <Flex wrap="wrap" gap={[8, 8]} align="center">
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
          </Flex>
        </Card>

        {/* AI Call 分区（§5.1.4：第一屏业务结果 / 第二屏渠道卫生层） */}
        <Card>
          <Flex vertical gap={12}>
            <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
              <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  AI Call 分区 · 业务结果
                </Typography.Title>
                <Typography.Text type="secondary">
                  热层 · t_ai_call_session 会话底座
                </Typography.Text>
              </div>
              <Select
                value={aicallDays}
                style={{ width: 132 }}
                options={[
                  { value: 1, label: "今天" },
                  { value: 2, label: "今天 / 昨天" },
                  { value: 7, label: "近 7 天" },
                  { value: 30, label: "近 30 天" }
                ]}
                onChange={setAicallDays}
              />
            </Flex>

            <Row gutter={[16, 16]}>
              <Col xs={12} md={4}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic title="拨出" value={Number(aicall?.funnel.dispatched ?? 0)} />
                </Card>
              </Col>
              <Col xs={12} md={4}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic title="接通" value={Number(aicall?.funnel.answered ?? 0)} />
                </Card>
              </Col>
              <Col xs={12} md={4}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic title="真人接续" value={Number(aicall?.funnel.aiConnected ?? 0)} />
                </Card>
              </Col>
              <Col xs={12} md={4}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic title="接通但无效" value={Number(aicall?.funnel.invalid ?? 0)} />
                </Card>
              </Col>
              <Col xs={12} md={4}>
                <Card size="small" styles={{ body: { padding: 16 } }}>
                  <Statistic
                    title="真人接通率"
                    value={
                      aicall?.funnel.dispatched
                        ? pct((aicall?.funnel.aiConnected ?? 0) / aicall?.funnel.dispatched)
                        : "—"
                    }
                  />
                </Card>
              </Col>
            </Row>

            <Row gutter={[16, 16]}>
              <Col xs={24} lg={12}>
                <Card size="small" title="接通后结果标签（result_label 三桶）">
                  <Flex wrap="wrap" gap={[8, 8]}>
                    {(aicall?.labelDistribution || []).length === 0 ? (
                      <Typography.Text type="secondary">暂无接通样本</Typography.Text>
                    ) : (
                      (aicall?.labelDistribution || []).map((l) => (
                        <Tag
                          key={l.label}
                          color={
                            l.bucket === "合规风险"
                              ? "red"
                              : l.bucket === "业务结果"
                                ? "green"
                                : "default"
                          }
                          style={{ margin: 0 }}
                        >
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
          </Flex>
        </Card>

        {/* 风险信号（§5.1.5：高敏标签清单 + 断联，D4 仅展示+提醒，不自动暂停） */}
        <Card>
          <Flex vertical gap={12}>
            <Flex justify="space-between" align="center" wrap="wrap" gap={12}>
              <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  风险信号
                </Typography.Title>
                <Typography.Text type="secondary">
                  高敏标签（dispute 等）仅展示 + 提醒，不自动暂停（§5.1.7）
                </Typography.Text>
              </div>
              <Flex gap={24} align="center">
                <Statistic
                  title="断联（INVALID_NUMBER）"
                  value={Number(risk?.disconnect.invalidNumber ?? 0)}
                  valueStyle={{ color: "#cf1322" }}
                />
                <Statistic
                  title="Guard 拦截"
                  value={Number(risk?.guardBlocked.guardBlocked ?? 0)}
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
                    render: (v: string) =>
                      v ? String(v).slice(0, 19).replace("T", " ") : "—"
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
                    render: (v: string) =>
                      v ? String(v).slice(0, 19).replace("T", " ") : "—"
                  },
                  {
                    title: "受理时间",
                    dataIndex: "dispatchedAt",
                    width: 170,
                    render: (v: string) =>
                      v ? String(v).slice(0, 19).replace("T", " ") : "—"
                  }
                ]}
              />
            </Card>
          </Flex>
        </Card>

        <Card>
          <Flex vertical gap={12}>
            <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
              <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  触达与结果链（Outreach）
                </Typography.Title>
                <Typography.Text type="secondary">
                  热层 · 测试库 t_contact_timeline 实时聚合（当前 Phase 1 连测试库）
                </Typography.Text>
              </div>
              <Flex wrap="wrap" gap={8} align="center">
                <Tag color="green">{data?.layer || "HOT"}</Tag>
                <Tag color="blue">{data?.freshness || "realtime"}</Tag>
                <Select
                  value={days}
                  style={{ width: 132 }}
                  options={[
                    { value: 7, label: "近 7 天" },
                    { value: 14, label: "近 14 天" },
                    { value: 30, label: "近 30 天" },
                    { value: 90, label: "近 90 天" }
                  ]}
                  onChange={setDays}
                />
                <Button type="primary" onClick={load} loading={loading}>
                  刷新
                </Button>
              </Flex>
            </Flex>
            {data && (
              <Typography.Text type="secondary" style={{ wordBreak: "break-all" }}>
                窗口：{data.from} → {data.to}
              </Typography.Text>
            )}
          </Flex>
        </Card>

        {empty && (
          <Alert
            type="info"
            showIcon
            message="当前时间窗口内无触达记录"
            description="测试数据最近写入约在数天前。请切换到「近 30 天」或「近 90 天」；或在 Case Monitor 查看具体案件 timeline。"
          />
        )}

        <Row gutter={[16, 16]}>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic title="Timeline 记录" value={data?.summary.totalRecords ?? 0} />
            </Card>
          </Col>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic
                title={<Tooltip title="不含 SKIPPED（未实际发送）">实际发送</Tooltip>}
                value={attempted}
              />
            </Card>
          </Col>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic
                title="送达率"
                value={((data?.summary.deliveryRate ?? 0) * 100).toFixed(1)}
                suffix="%"
                valueStyle={{ color: "#3f8600" }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic
                title={<Tooltip title="全量 OPEN 异常（非窗口内）">待处理异常</Tooltip>}
                value={Number(data?.exceptions.open ?? 0)}
              />
            </Card>
          </Col>
        </Row>

        <Row gutter={[16, 16]} align="stretch">
          <Col xs={24}>
            <Card title="结果分布" loading={loading} style={{ height: "100%" }}>
              <Flex vertical gap={16}>
                <Flex wrap="wrap" gap={[8, 8]} align="center">
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
                <Row gutter={[16, 8]}>
                  <Col xs={24} sm={12} md={8}>
                    <Typography.Text type="secondary">
                      送达率 = Delivered ÷ Attempted
                    </Typography.Text>
                  </Col>
                  <Col xs={12} sm={6} md={5}>
                    <Typography.Text type="secondary">
                      Skipped: {data?.summary.skipped ?? 0}
                    </Typography.Text>
                  </Col>
                  <Col xs={12} sm={6} md={5}>
                    <Typography.Text type="secondary">
                      Other: {data?.summary.other ?? 0}
                    </Typography.Text>
                  </Col>
                  <Col xs={24} md={6}>
                    <Typography.Text type="secondary">
                      计划（全量）: {data?.plans.total ?? 0}
                    </Typography.Text>
                  </Col>
                </Row>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  Skipped = 未发送（Guard / 非里程碑 Email 等），Other = 其他未归类结果，均不计入送达率分母。
                  回收漏斗 / Aging 趋势需冷层 BigQuery，尚未接入。
                </Typography.Paragraph>
              </Flex>
            </Card>
          </Col>
        </Row>

        <Row gutter={[16, 16]}>
          <Col xs={24}>
            <Card title="按渠道" loading={loading}>
              <Table
                rowKey="channel"
                size="small"
                pagination={false}
                scroll={{ x: 720 }}
                locale={{ emptyText: "无数据" }}
                dataSource={data?.byChannel || []}
                columns={[
                  { title: "渠道", dataIndex: "channel", width: 80, fixed: "left" },
                  ...metricCols
                ]}
              />
            </Card>
          </Col>
          <Col xs={24}>
            <Card title="按 Stage" loading={loading}>
              <Table
                rowKey="stage"
                size="small"
                pagination={false}
                scroll={{ x: 720 }}
                locale={{ emptyText: "无数据" }}
                dataSource={data?.byStage || []}
                columns={[
                  { title: "Stage", dataIndex: "stage", width: 72, fixed: "left" },
                  ...metricCols
                ]}
              />
            </Card>
          </Col>
        </Row>
      </Space>
    </div>
  );
}
