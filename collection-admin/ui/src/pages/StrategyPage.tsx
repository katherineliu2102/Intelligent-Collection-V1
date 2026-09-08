import {
  Button,
  Card,
  Descriptions,
  Input,
  Space,
  Table,
  Tag,
  Typography,
  message
} from "antd";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

type VersionItem = {
  id: number;
  configType: string;
  configKey: string;
  fromVersion?: number;
  toVersion: number;
  rollbackRef?: number;
  reason?: string;
  operator: string;
  createdAt: string;
};

type CatalogOverview = {
  paradigm?: string;
  touchWindow?: string;
  ceaseRule?: string;
  summary?: Record<string, number>;
  runtime?: {
    connectivity?: Record<string, boolean>;
    compliance?: Record<string, string | number>;
  };
  stages?: any[];
  channels?: any[];
};

function phaseColor(phase: string): string {
  switch (phase) {
    case "LIVE":
      return "green";
    case "MOCK":
      return "orange";
    case "PHASE2":
    case "RESERVED":
      return "default";
    case "SEPARATE":
      return "purple";
    default:
      return "blue";
  }
}

export function StrategyPage() {
  const [versions, setVersions] = useState<VersionItem[]>([]);
  const [catalog, setCatalog] = useState<CatalogOverview | null>(null);
  const [loadingCatalog, setLoadingCatalog] = useState(false);
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [rollingBack, setRollingBack] = useState(false);
  const [rollbackReason, setRollbackReason] = useState("Rollback from admin UI");
  const [selectedRowId, setSelectedRowId] = useState<number | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);

  const loadCatalog = useCallback(async () => {
    setLoadingCatalog(true);
    try {
      const data = (await api.catalogOverview()) as CatalogOverview;
      setCatalog(data);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoadingCatalog(false);
    }
  }, []);

  const loadVersions = useCallback(async () => {
    setLoadingVersions(true);
    try {
      const resp = await api.listConfigVersions(1, 20);
      setVersions(resp.data.items || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoadingVersions(false);
    }
  }, []);

  useEffect(() => {
    loadCatalog();
    loadVersions();
  }, [loadCatalog, loadVersions]);

  const rollback = async () => {
    if (selectedVersion == null) {
      message.warning("Select a target config version first");
      return;
    }
    setRollingBack(true);
    try {
      await api.rollbackConfig(selectedVersion, rollbackReason);
      message.success(`Rolled back to config version ${selectedVersion}`);
      await loadVersions();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setRollingBack(false);
    }
  };
  const summary = catalog?.summary || {};
  const connectivity = catalog?.runtime?.connectivity || {};
  const compliance = catalog?.runtime?.compliance || {};

  return (
    <Space direction="vertical" size={16} style={{ width: "100%" }}>
      <Card title="Strategy Overview" loading={loadingCatalog} extra={<Button onClick={loadCatalog}>Refresh</Button>}>
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="Paradigm" span={2}>
            {catalog?.paradigm || "—"}
          </Descriptions.Item>
          <Descriptions.Item label="Touch Window">{catalog?.touchWindow || "—"}</Descriptions.Item>
          <Descriptions.Item label="Cease Rule">{catalog?.ceaseRule || "—"}</Descriptions.Item>
          <Descriptions.Item label="Quiet Hours">
            {String(compliance.quietHours ?? "—")}
          </Descriptions.Item>
          <Descriptions.Item label="Daily Limit">
            {compliance.dailyLimit && typeof compliance.dailyLimit === "object" ? (
              <Space size={4} wrap>
                {Object.entries(compliance.dailyLimit).map(([ch, n]) => (
                  <Tag key={ch} color="blue" style={{ margin: 0 }}>
                    {ch}: {String(n)}
                  </Tag>
                ))}
              </Space>
            ) : (
              "—"
            )}
          </Descriptions.Item>
        </Descriptions>
        <Space style={{ marginTop: 16 }} wrap>
          <Tag color="blue">Stages: {summary.stagesCount ?? "—"}</Tag>
          <Tag color="green">Channels Live: {summary.channelsLive ?? "—"}</Tag>
          <Tag>SMS: {summary.smsConfiguredCount ?? "—"}</Tag>
          <Tag>Push: {summary.pushConfiguredCount ?? "—"}</Tag>
          <Tag>Email: {summary.emailConfiguredCount ?? "—"}</Tag>
        </Space>
        <Space style={{ marginTop: 12 }} wrap>
          {Object.entries(connectivity).map(([k, v]) => (
            <Tag key={k} color={v ? "green" : "red"}>
              {k}: {v ? "OK" : "OFF"}
            </Tag>
          ))}
        </Space>
      </Card>

      <Card title="Stage Plan" loading={loadingCatalog}>
        <Typography.Text type="secondary" style={{ display: "block", marginBottom: 8 }}>
          引擎 Stage 为 S0–S4；S4 桶内 D+61~90 降为每日 1 通 AI。D+91 停催见上方 Cease Rule。
        </Typography.Text>
        <Table
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={catalog?.stages || []}
          columns={[
            { title: "Stage", dataIndex: "id", width: 70 },
            { title: "DPD", dataIndex: "dpdRange", width: 110 },
            { title: "SMS", dataIndex: "sms", width: 160, render: (v: string) => v || "—" },
            { title: "Push", dataIndex: "push", width: 140, render: (v: string) => v || "—" },
            { title: "Email (14:00)", dataIndex: "email", width: 150, render: (v: string) => v || "—" },
            { title: "AI 外呼", dataIndex: "aiCall", width: 200, render: (v: string) => v || "—" },
            { title: "Tone", dataIndex: "tone", width: 130, render: (v: string) => v || "—" },
            { title: "话术重点", dataIndex: "messaging", render: (v: string, r: { positioning?: string }) => v || r.positioning || "—" }
          ]}
          scroll={{ x: 1100 }}
        />
      </Card>

      <Card title="Channels" loading={loadingCatalog}>
        <Table
          rowKey="type"
          size="small"
          pagination={false}
          dataSource={catalog?.channels || []}
          columns={[
            { title: "Type", dataIndex: "type", width: 110 },
            { title: "Provider", dataIndex: "provider", width: 220 },
            { title: "Adapter", dataIndex: "adapter", width: 200 },
            {
              title: "Live In Phase 1",
              dataIndex: "phase1",
              width: 100,
              render: (v: string) => <Tag color={phaseColor(v)}>{v}</Tag>
            },
            { title: "Description", dataIndex: "description" }
          ]}
        />
      </Card>

      <Card title="Config Versions">
        <Table
          rowKey="id"
          loading={loadingVersions}
          dataSource={versions}
          pagination={false}
          rowSelection={{
            type: "radio",
            selectedRowKeys: selectedRowId == null ? [] : [selectedRowId],
            onChange: (_keys, rows) => {
              const row = rows[0] as VersionItem | undefined;
              setSelectedRowId(row ? row.id : null);
              setSelectedVersion(row ? row.toVersion : null);
            }
          }}
          columns={[
            { title: "ID", dataIndex: "id", width: 80 },
            { title: "Type", dataIndex: "configType", width: 140 },
            { title: "Key", dataIndex: "configKey", width: 160 },
            { title: "From", dataIndex: "fromVersion", width: 80 },
            { title: "To", dataIndex: "toVersion", width: 80 },
            { title: "Rollback Ref", dataIndex: "rollbackRef", width: 110 },
            { title: "Operator", dataIndex: "operator", width: 120 },
            { title: "Reason", dataIndex: "reason" },
            { title: "Created At", dataIndex: "createdAt", width: 180 }
          ]}
        />
        <Space style={{ marginTop: 16 }} align="start">
          <Input.TextArea
            rows={2}
            style={{ width: 360 }}
            value={rollbackReason}
            onChange={(e) => setRollbackReason(e.target.value)}
            placeholder="Rollback reason"
          />
          <Button
            danger
            onClick={rollback}
            loading={rollingBack}
            disabled={selectedVersion == null}
          >
            Rollback To Selected Version
          </Button>
          <Button onClick={loadVersions} loading={loadingVersions}>
            Refresh Versions
          </Button>
        </Space>
      </Card>
    </Space>
  );
}
