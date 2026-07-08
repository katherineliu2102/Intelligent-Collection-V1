import { Button, Card, Form, Input, Modal, Space, Table, Tabs, Tag, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { PlanTemplatesTab } from "./PlanTemplatesTab";

type CatalogRow = {
  slot: string;
  stage?: string;
  phase1?: string;
  configured?: boolean;
  contentSource?: string;
  subject?: string;
  title?: string;
  body?: string;
  bodyRendered?: string;
  titleRendered?: string;
  templateIdFull?: string;
  preview?: string;
};

type DbScript = {
  id: number;
  scriptSlot: string;
  channel: string;
  locale: string;
  body?: string;
  title?: string;
  version: number;
  configVersion?: number;
  updatedBy?: string;
};

type MergedRow = CatalogRow & {
  channel: string;
  inDb: boolean;
  dbBody?: string;
  dbTitle?: string;
  dbVersion: number;
  effectiveSource: "DB" | "YAML" | "NONE";
};

type EditState = {
  open: boolean;
  channel: string;
  slot: string;
  version: number;
  body: string;
  title: string;
  reason: string;
  hasTitle: boolean;
  saving: boolean;
};

const emptyEdit: EditState = {
  open: false,
  channel: "SMS",
  slot: "",
  version: 0,
  body: "",
  title: "",
  reason: "Edit from admin UI",
  hasTitle: false,
  saving: false
};

function sourceColor(src: string): string {
  switch (src) {
    case "DB":
      return "green";
    case "YAML":
      return "blue";
    default:
      return "default";
  }
}

export function TemplatesPage() {
  const [loading, setLoading] = useState(false);
  const [sms, setSms] = useState<MergedRow[]>([]);
  const [push, setPush] = useState<MergedRow[]>([]);
  const [email, setEmail] = useState<CatalogRow[]>([]);
  const [edit, setEdit] = useState<EditState>(emptyEdit);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [catalog, dbResp] = await Promise.all([
        api.catalogOverview() as Promise<{ templates?: any }>,
        api.listScriptTemplates() as Promise<{ data: DbScript[] }>
      ]);
      const templates = catalog.templates || {};
      const dbList: DbScript[] = dbResp.data || [];
      const dbMap = new Map<string, DbScript>();
      dbList.forEach((d) => dbMap.set(`${d.channel}/${d.scriptSlot}`, d));

      const merge = (rows: CatalogRow[], channel: string): MergedRow[] =>
        (rows || []).map((r) => {
          const db = dbMap.get(`${channel}/${r.slot}`);
          const inDb = !!db;
          const effectiveSource: MergedRow["effectiveSource"] = inDb
            ? "DB"
            : r.body || r.title
              ? "YAML"
              : "NONE";
          return {
            ...r,
            channel,
            inDb,
            dbBody: db?.body,
            dbTitle: db?.title,
            dbVersion: db?.version ?? 0,
            effectiveSource
          };
        });

      setSms(merge(templates.sms || [], "SMS"));
      setPush(merge(templates.push || [], "PUSH"));
      setEmail(templates.email || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openEdit = (row: MergedRow) => {
    setEdit({
      open: true,
      channel: row.channel,
      slot: row.slot,
      version: row.dbVersion,
      body: row.inDb ? row.dbBody || "" : row.body || "",
      title: row.inDb ? row.dbTitle || "" : row.title || "",
      reason: "Edit from admin UI",
      hasTitle: row.channel === "PUSH",
      saving: false
    });
  };

  const submitEdit = async () => {
    setEdit((s) => ({ ...s, saving: true }));
    try {
      await api.updateScriptTemplate({
        scriptSlot: edit.slot,
        channel: edit.channel,
        body: edit.body,
        title: edit.hasTitle ? edit.title : undefined,
        version: edit.version,
        reason: edit.reason
      });
      message.success(`Saved ${edit.channel}/${edit.slot}. Engine picks it up within ~10s.`);
      setEdit(emptyEdit);
      await load();
    } catch (e: any) {
      message.error(e.message);
      setEdit((s) => ({ ...s, saving: false }));
    }
  };

  const resetToYaml = async (row: MergedRow) => {
    try {
      await api.deactivateScriptTemplate(row.slot, row.channel);
      message.success(`Reset ${row.channel}/${row.slot} to YAML. Engine reloads within ~10s.`);
      await load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const editableExpanded = (row: MergedRow) => (
    <Space direction="vertical" style={{ width: "100%" }}>
      {row.inDb ? (
        <>
          <Typography.Text>
            <b>DB body:</b> {row.dbBody || "—"}
          </Typography.Text>
          {row.channel === "PUSH" && (
            <Typography.Text>
              <b>DB title:</b> {row.dbTitle || "—"}
            </Typography.Text>
          )}
        </>
      ) : (
        <Typography.Text type="secondary">
          Not overridden in DB yet — engine uses YAML. Click Edit to create a DB override.
        </Typography.Text>
      )}
      <Typography.Text type="secondary">
        <b>YAML body:</b> {row.body || "—"}
      </Typography.Text>
      {row.bodyRendered && (
        <Typography.Text type="secondary">
          <b>Rendered:</b> {row.bodyRendered}
        </Typography.Text>
      )}
    </Space>
  );

  const cols = (channel: string) => [
    { title: "Slot", dataIndex: "slot", width: 210 },
    { title: "Stage", dataIndex: "stage", width: 70 },
    {
      title: "Effective",
      dataIndex: "effectiveSource",
      width: 110,
      render: (v: string) => <Tag color={sourceColor(v)}>{v}</Tag>
    },
    ...(channel === "PUSH"
      ? [{ title: "Title", dataIndex: "title", ellipsis: true }]
      : []),
    {
      title: "Body",
      width: 360,
      ellipsis: true,
      render: (_: any, r: MergedRow) => (r.inDb ? r.dbBody : r.body) || "—"
    },
    {
      title: "Action",
      width: 180,
      render: (_: any, r: MergedRow) => (
        <Space>
          <Button size="small" onClick={() => openEdit(r)}>
            Edit
          </Button>
          {r.inDb && (
            <Button size="small" danger onClick={() => resetToYaml(r)}>
              Reset
            </Button>
          )}
        </Space>
      )
    }
  ];

  return (
    <Card
      title="Message Templates"
      loading={loading}
      extra={<Button onClick={load}>Refresh</Button>}
    >
      <Typography.Paragraph type="secondary">
        SMS / Push content is editable and persisted to DB (<code>t_script_template</code>). Effective
        source: <Tag color="green">DB</Tag> override &gt; <Tag color="blue">YAML</Tag> fallback. After
        saving, the engine reloads within ~10s (no restart). Email is managed in SendGrid (read-only
        here).
      </Typography.Paragraph>
      <Tabs
        items={[
          {
            key: "sms",
            label: `SMS (${sms.length})`,
            children: (
              <Table
                rowKey="slot"
                size="small"
                pagination={false}
                dataSource={sms}
                expandable={{ expandedRowRender: editableExpanded }}
                columns={cols("SMS")}
              />
            )
          },
          {
            key: "push",
            label: `Push (${push.length})`,
            children: (
              <Table
                rowKey="slot"
                size="small"
                pagination={false}
                dataSource={push}
                expandable={{ expandedRowRender: editableExpanded }}
                columns={cols("PUSH")}
              />
            )
          },
          {
            key: "email",
            label: `Email (${email.length})`,
            children: (
              <Table
                rowKey="slot"
                size="small"
                pagination={false}
                dataSource={email}
                columns={[
                  { title: "Slot", dataIndex: "slot", width: 220 },
                  { title: "Stage", dataIndex: "stage", width: 70 },
                  { title: "Subject", dataIndex: "subject", ellipsis: true },
                  { title: "SendGrid ID", dataIndex: "templateIdFull", ellipsis: true }
                ]}
              />
            )
          },
          {
            key: "plans",
            label: "Plans",
            children: <PlanTemplatesTab />
          }
        ]}
      />

      <Modal
        title={`Edit ${edit.channel} · ${edit.slot}`}
        open={edit.open}
        onOk={submitEdit}
        confirmLoading={edit.saving}
        onCancel={() => setEdit(emptyEdit)}
        okText="Save"
        width={640}
      >
        <Form layout="vertical">
          {edit.hasTitle && (
            <Form.Item label="Title">
              <Input
                value={edit.title}
                onChange={(e) => setEdit((s) => ({ ...s, title: e.target.value }))}
              />
            </Form.Item>
          )}
          <Form.Item label="Body" help="Placeholders: {name} {amount} {dpd} {repaymentUrl}">
            <Input.TextArea
              rows={4}
              value={edit.body}
              onChange={(e) => setEdit((s) => ({ ...s, body: e.target.value }))}
            />
          </Form.Item>
          <Form.Item label="Change Reason">
            <Input
              value={edit.reason}
              onChange={(e) => setEdit((s) => ({ ...s, reason: e.target.value }))}
            />
          </Form.Item>
          <Typography.Text type="secondary">
            Optimistic lock version: {edit.version} {edit.version === 0 ? "(new DB row)" : ""}
          </Typography.Text>
        </Form>
      </Modal>
    </Card>
  );
}
