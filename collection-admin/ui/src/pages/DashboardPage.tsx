import {
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
  sent: number;
  delivered: number;
  failed: number;
  skipped: number;
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
    totalSent: number;
    delivered: number;
    failed: number;
    skipped: number;
    deliveryRate: number;
  };
  byChannel: OutreachRow[];
  byStage: OutreachRow[];
  byTemplate: OutreachRow[];
  byResult: { result: string; count: number }[];
  exceptions: Record<string, number | string>;
  plans: Record<string, number | string>;
};

function pct(v: number) {
  return `${(v * 100).toFixed(1)}%`;
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
    title: (
      <Tooltip title="All OUT timeline rows, including guard SKIPPED">
        Records
      </Tooltip>
    ),
    dataIndex: "records",
    width: 88,
    align: "right"
  },
  {
    title: (
      <Tooltip title="Actual channel attempts (excludes SKIPPED)">
        Attempted
      </Tooltip>
    ),
    dataIndex: "sent",
    width: 96,
    align: "right"
  },
  { title: "Delivered", dataIndex: "delivered", width: 88, align: "right" },
  { title: "Failed", dataIndex: "failed", width: 72, align: "right" },
  { title: "Skipped", dataIndex: "skipped", width: 80, align: "right" },
  {
    title: (
      <Tooltip title="Delivered / Attempted">
        Rate
      </Tooltip>
    ),
    dataIndex: "deliveryRate",
    width: 80,
    align: "right",
    render: (v: number) => pct(v)
  }
];

export function DashboardPage() {
  const [loading, setLoading] = useState(false);
  const [days, setDays] = useState(7);
  const [data, setData] = useState<OutreachData | null>(null);

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

  useEffect(() => {
    load();
  }, [load]);

  const templateCols: ColumnsType<OutreachRow> = [
    {
      title: "scriptSlot",
      dataIndex: "scriptSlot",
      ellipsis: true,
      width: 150
    },
    { title: "Tpl ID", dataIndex: "templateId", width: 72, align: "right" },
    { title: "Ch", dataIndex: "templateChannel", width: 64 },
    ...metricCols
  ];

  return (
    <div style={{ width: "100%", minWidth: 0 }}>
      <Space direction="vertical" size="large" style={{ width: "100%" }}>
        <Card>
          <Flex vertical gap={12}>
            <Flex justify="space-between" align="flex-start" wrap="wrap" gap={12}>
              <div style={{ minWidth: 0, flex: "1 1 240px" }}>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  Outreach Dashboard
                </Typography.Title>
                <Typography.Text type="secondary">
                  Hot layer · realtime from t_contact_timeline
                </Typography.Text>
              </div>
              <Flex wrap="wrap" gap={8} align="center" style={{ flex: "0 1 auto" }}>
                <Tag color="green">{data?.layer || "HOT"}</Tag>
                <Tag color="blue">{data?.freshness || "realtime"}</Tag>
                <Select
                  value={days}
                  style={{ width: 132 }}
                  options={[
                    { value: 7, label: "Last 7 days" },
                    { value: 14, label: "Last 14 days" },
                    { value: 30, label: "Last 30 days" }
                  ]}
                  onChange={setDays}
                />
                <Button type="primary" onClick={load} loading={loading}>
                  Refresh
                </Button>
              </Flex>
            </Flex>
            {data && (
              <Typography.Text type="secondary" style={{ wordBreak: "break-all" }}>
                Window: {data.from} → {data.to}
              </Typography.Text>
            )}
          </Flex>
        </Card>

        <Row gutter={[16, 16]}>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic title="Timeline Records" value={data?.summary.totalRecords ?? 0} />
            </Card>
          </Col>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic
                title={
                  <Tooltip title="Excludes guard/compliance SKIPPED">
                    Attempted Sends
                  </Tooltip>
                }
                value={data?.summary.totalSent ?? 0}
              />
            </Card>
          </Col>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic
                title="Delivery Rate"
                value={((data?.summary.deliveryRate ?? 0) * 100).toFixed(1)}
                suffix="%"
                valueStyle={{ color: "#3f8600" }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={12} md={6}>
            <Card loading={loading} styles={{ body: { padding: 16 } }}>
              <Statistic
                title={
                  <Tooltip title="All-time open exceptions">
                    Open Exceptions
                  </Tooltip>
                }
                value={Number(data?.exceptions.open ?? 0)}
              />
            </Card>
          </Col>
        </Row>

        <Row gutter={[16, 16]}>
          <Col xs={24} xl={12}>
            <Card title="By Channel" loading={loading}>
              <Table
                rowKey="channel"
                size="small"
                pagination={false}
                scroll={{ x: 640 }}
                dataSource={data?.byChannel || []}
                columns={[
                  { title: "Channel", dataIndex: "channel", width: 88, fixed: "left" },
                  ...metricCols
                ]}
              />
            </Card>
          </Col>
          <Col xs={24} xl={12}>
            <Card title="By Stage" loading={loading}>
              <Table
                rowKey="stage"
                size="small"
                pagination={false}
                scroll={{ x: 640 }}
                dataSource={data?.byStage || []}
                columns={[
                  { title: "Stage", dataIndex: "stage", width: 72, fixed: "left" },
                  ...metricCols
                ]}
              />
            </Card>
          </Col>
        </Row>

        <Row gutter={[16, 16]}>
          <Col xs={24} xl={14}>
            <Card title="By Template / scriptSlot" loading={loading}>
              <Table
                rowKey={(r) => `${r.templateId}-${r.scriptSlot}`}
                size="small"
                pagination={false}
                scroll={{ x: 760 }}
                dataSource={data?.byTemplate || []}
                columns={templateCols}
              />
            </Card>
          </Col>
          <Col xs={24} xl={10}>
            <Card title="Result Distribution" loading={loading}>
              <Flex wrap="wrap" gap={8}>
                {(data?.byResult || []).map((r) => (
                  <Tag key={r.result} color={resultColor(r.result)}>
                    {r.result}: {r.count}
                  </Tag>
                ))}
              </Flex>
              <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 8 }}>
                Skipped ({data?.summary.skipped ?? 0}) = guard/compliance blocks, not counted in
                delivery rate denominator.
              </Typography.Paragraph>
              <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
                Plan status (all time): completed {data?.plans.PLAN_COMPLETED ?? 0}, cancelled{" "}
                {data?.plans.PLAN_CANCELLED ?? 0}, total {data?.plans.total ?? 0}
              </Typography.Paragraph>
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                Recovery funnel / Aging trends require cold layer (BigQuery) — not yet wired.
              </Typography.Paragraph>
            </Card>
          </Col>
        </Row>
      </Space>
    </div>
  );
}
