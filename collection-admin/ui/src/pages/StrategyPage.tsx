import {
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Space,
  Table,
  Tag,
  Typography,
  message
} from "antd";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

type EvaluationSettings = {
  holdoutRatio: number;
  configVersion: number;
  version: number;
  updatedBy?: string;
  updatedAt?: string;
};

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
  const [form] = Form.useForm();
  const [settings, setSettings] = useState<EvaluationSettings | null>(null);
  const [versions, setVersions] = useState<VersionItem[]>([]);
  const [catalog, setCatalog] = useState<CatalogOverview | null>(null);
  const [loadingCatalog, setLoadingCatalog] = useState(false);
  const [loadingSettings, setLoadingSettings] = useState(false);
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [saving, setSaving] = useState(false);
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

  const loadSettings = useCallback(async () => {
    setLoadingSettings(true);
    try {
      const resp = await api.getEvaluationSettings();
      const data = resp.data as EvaluationSettings;
      setSettings(data);
      form.setFieldsValue({
        holdoutRatio: Number(data.holdoutRatio),
        reason: "Update holdout ratio from strategy page"
      });
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoadingSettings(false);
    }
  }, [form]);

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
    loadSettings();
    loadVersions();
  }, [loadCatalog, loadSettings, loadVersions]);

  const saveSettings = async () => {
    const values = await form.validateFields();
    if (!settings) {
      return;
    }
    setSaving(true);
    try {
      const resp = await api.updateEvaluationSettings({
        holdoutRatio: values.holdoutRatio,
        version: settings.version,
        reason: values.reason
      });
      setSettings(resp.data as EvaluationSettings);
      message.success("Holdout ratio updated");
      await loadVersions();
    } catch (e: any) {
      message.error(e.message);
      await loadSettings();
    } finally {
      setSaving(false);
    }
  };

  const rollback = async () => {
    if (selectedVersion == null) {
      message.warning("Select a target config version first");
      return;
    }
    setRollingBack(true);
    try {
      const resp = await api.rollbackConfig(selectedVersion, rollbackReason);
      setSettings(resp.data as EvaluationSettings);
      message.success(`Rolled back to config version ${selectedVersion}`);
      await loadVersions();
      await loadSettings();
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
            {String(compliance.dailyLimit ?? "—")}
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
        <Table
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={catalog?.stages || []}
          columns={[
            { title: "Stage", dataIndex: "id", width: 80 },
            { title: "Name", dataIndex: "name", width: 180 },
            { title: "DPD Range", dataIndex: "dpdRange", width: 140 },
            { title: "Positioning", dataIndex: "positioning" }
          ]}
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
              title: "Phase 1",
              dataIndex: "phase1",
              width: 100,
              render: (v: string) => <Tag color={phaseColor(v)}>{v}</Tag>
            },
            {
              title: "Configured",
              dataIndex: "configured",
              width: 110,
              render: (v: boolean) => <Tag color={v ? "green" : "default"}>{v ? "Y" : "N"}</Tag>
            },
            { title: "Description", dataIndex: "description" }
          ]}
        />
      </Card>

      <Card title="Evaluation Settings" loading={loadingSettings}>
        <Typography.Paragraph type="secondary">
          Holdout ratio controls the benchmark group size for strategy evaluation. Valid range: 1% -
          20%.
        </Typography.Paragraph>
        <Form form={form} layout="vertical" style={{ maxWidth: 520 }}>
          <Form.Item
            name="holdoutRatio"
            label="Holdout Ratio"
            rules={[{ required: true, message: "Holdout ratio is required" }]}
          >
            <InputNumber min={0.01} max={0.2} step={0.01} style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item name="reason" label="Change Reason" rules={[{ required: true }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Space>
            <Button type="primary" onClick={saveSettings} loading={saving}>
              Save Settings
            </Button>
            <Button onClick={loadSettings}>Refresh</Button>
          </Space>
        </Form>
        {settings && (
          <Space style={{ marginTop: 16 }} wrap>
            <Tag color="blue">configVersion: {settings.configVersion}</Tag>
            <Tag>optimistic version: {settings.version}</Tag>
            {settings.updatedBy && <Tag>updatedBy: {settings.updatedBy}</Tag>}
          </Space>
        )}
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
