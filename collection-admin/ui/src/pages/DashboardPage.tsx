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

  const empty = !loading && data && (data.summary.totalRecords ?? 0) === 0;
  const attempted = data?.summary.totalAttempted ?? data?.summary.totalSent ?? 0;

  const templateCols: ColumnsType<OutreachRow> = [
    { title: "scriptSlot", dataIndex: "scriptSlot", ellipsis: true, width: 140 },
    { title: "Tpl", dataIndex: "templateId", width: 56, align: "right" },
    { title: "Ch", dataIndex: "templateChannel", width: 56 },
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

        <Row gutter={[16, 16]}>
          <Col xs={24}>
            <Card title="按模板 / scriptSlot" loading={loading}>
              <Table
                rowKey={(r) => `${r.templateId}-${r.scriptSlot}`}
                size="small"
                pagination={false}
                scroll={{ x: 820 }}
                locale={{ emptyText: "无数据" }}
                dataSource={data?.byTemplate || []}
                columns={templateCols}
              />
            </Card>
          </Col>
        </Row>
      </Space>
    </div>
  );
}
