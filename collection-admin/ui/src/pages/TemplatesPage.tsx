import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message
} from "antd";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../api";
import {
  ALLOWED_VARS,
  LIMITS,
  validateScriptTemplateLocal
} from "../scriptTemplateValidation";
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

type EffectiveSource = "EDITED" | "DEFAULT" | "UNCONFIGURED";

const EFFECTIVE_LABEL: Record<EffectiveSource, string> = {
  EDITED: "已编辑",
  DEFAULT: "系统默认",
  UNCONFIGURED: "未配置"
};

type MergedRow = CatalogRow & {
  channel: string;
  inDb: boolean;
  dbBody?: string;
  dbTitle?: string;
  dbVersion: number;
  effectiveSource: EffectiveSource;
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

function sourceColor(src: EffectiveSource): string {
  switch (src) {
    case "EDITED":
      return "green";
    case "DEFAULT":
      return "blue";
    default:
      return "default";
  }
}

function effectiveLabel(src: EffectiveSource): string {
  return EFFECTIVE_LABEL[src];
}

function lengthHint(current: number, max: number): string {
  return `${current}/${max}`;
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
          const effectiveSource: EffectiveSource = inDb
            ? "EDITED"
            : r.body || r.title
              ? "DEFAULT"
              : "UNCONFIGURED";
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

  const localValidation = useMemo(
    () => validateScriptTemplateLocal(edit.channel, edit.title, edit.body),
    [edit.channel, edit.title, edit.body]
  );

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

  const insertVar = (target: "title" | "body", varName: string) => {
    const token = `{${varName}}`;
    setEdit((s) => {
      if (target === "title") {
        return { ...s, title: `${s.title}${token}` };
      }
      return { ...s, body: `${s.body}${token}` };
    });
  };

  const submitEdit = async () => {
    if (!localValidation.valid) {
      message.error(localValidation.errors[0]?.message || "Validation failed");
      return;
    }
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
      const details =
        Array.isArray(e.errors) && e.errors.length
          ? e.errors.map((x: any) => x.message).join("; ")
          : e.message;
      message.error(details);
      setEdit((s) => ({ ...s, saving: false }));
    }
  };

  const resetToYaml = async (row: MergedRow) => {
    try {
      await api.deactivateScriptTemplate(row.slot, row.channel);
      message.success(`已恢复 ${row.channel}/${row.slot} 为系统默认，约 10 秒内生效。`);
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
            <b>已编辑正文：</b> {row.dbBody || "—"}
          </Typography.Text>
          {row.channel === "PUSH" && (
            <Typography.Text>
              <b>已编辑标题：</b> {row.dbTitle || "—"}
            </Typography.Text>
          )}
        </>
      ) : (
        <Typography.Text type="secondary">
          尚未在后台编辑 — 引擎使用系统默认配置。点击 Edit 保存后将变为「已编辑」。
        </Typography.Text>
      )}
      <Typography.Text type="secondary">
        <b>系统默认正文：</b> {row.body || "—"}
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
      title: "生效状态",
      dataIndex: "effectiveSource",
      width: 110,
      render: (v: EffectiveSource) => <Tag color={sourceColor(v)}>{effectiveLabel(v)}</Tag>
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
              恢复默认
            </Button>
          )}
        </Space>
      )
    }
  ];

  const bodyMax = edit.channel === "SMS" ? LIMITS.smsBodyMax : LIMITS.pushBodyMax;
  const titleMax = LIMITS.pushTitleMax;

  return (
    <Card
      title="Message Templates"
      loading={loading}
      extra={<Button onClick={load}>Refresh</Button>}
    >
      <Typography.Paragraph type="secondary">
        SMS / Push 支持按需编辑并保存到后台（<code>t_script_template</code>）。生效优先级：
        <Tag color="green">已编辑</Tag> &gt; <Tag color="blue">系统默认</Tag>；未配置 slot 显示
        <Tag>未配置</Tag>。保存后约 10 秒引擎热更新，无需重启。Email 由 SendGrid 托管（本页只读）。
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
        okButtonProps={{ disabled: !localValidation.valid }}
        onCancel={() => setEdit(emptyEdit)}
        okText="Save"
        width={720}
      >
        <Form layout="vertical">
          <Form.Item label="允许变量（点击插入到正文）">
            <Space wrap>
              {ALLOWED_VARS.map((v) => (
                <Tag
                  key={v}
                  color="blue"
                  style={{ cursor: "pointer" }}
                  onClick={() => insertVar("body", v)}
                >
                  {`{${v}}`}
                </Tag>
              ))}
              {edit.hasTitle && (
                <Typography.Text type="secondary">
                  标题插入：
                  {ALLOWED_VARS.map((v) => (
                    <Tag
                      key={`t-${v}`}
                      style={{ cursor: "pointer", marginInlineStart: 4 }}
                      onClick={() => insertVar("title", v)}
                    >
                      {`{${v}}`}
                    </Tag>
                  ))}
                </Typography.Text>
              )}
            </Space>
          </Form.Item>
          {edit.hasTitle && (
            <Form.Item
              label={`Title（${lengthHint(edit.title.length, titleMax)}）`}
              validateStatus={
                localValidation.errors.some((e) => e.field === "title") ? "error" : undefined
              }
              help={localValidation.errors.find((e) => e.field === "title")?.message}
            >
              <Input
                value={edit.title}
                maxLength={titleMax}
                showCount
                onChange={(e) => setEdit((s) => ({ ...s, title: e.target.value }))}
              />
            </Form.Item>
          )}
          <Form.Item
            label={`Body（${lengthHint(edit.body.length, bodyMax)}）`}
            validateStatus={
              localValidation.errors.some((e) => e.field === "body") ? "error" : undefined
            }
            help={
              localValidation.errors.find((e) => e.field === "body")?.message ||
              (edit.channel === "SMS"
                ? "SMS 必须包含 {amount} 与 {repaymentUrl}；仅允许白名单变量。"
                : undefined)
            }
          >
            <Input.TextArea
              rows={5}
              value={edit.body}
              maxLength={bodyMax}
              showCount
              onChange={(e) => setEdit((s) => ({ ...s, body: e.target.value }))}
            />
          </Form.Item>
          {localValidation.warnings.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={localValidation.warnings.map((w) => w.message).join("；")}
            />
          )}
          {(localValidation.preview.bodyRendered || localValidation.preview.titleRendered) && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="样例渲染预览"
              description={
                <Space direction="vertical" size={2}>
                  {localValidation.preview.titleRendered && (
                    <Typography.Text>
                      <b>Title:</b> {localValidation.preview.titleRendered}（
                      {localValidation.preview.titleRenderedLength} 字）
                    </Typography.Text>
                  )}
                  {localValidation.preview.bodyRendered && (
                    <Typography.Text>
                      <b>Body:</b> {localValidation.preview.bodyRendered}（
                      {localValidation.preview.bodyRenderedLength} 字）
                    </Typography.Text>
                  )}
                </Space>
              }
            />
          )}
          <Form.Item label="Change Reason">
            <Input
              value={edit.reason}
              onChange={(e) => setEdit((s) => ({ ...s, reason: e.target.value }))}
            />
          </Form.Item>
          <Typography.Text type="secondary">
            版本号（乐观锁）：{edit.version} {edit.version === 0 ? "（首次编辑，将新建记录）" : ""}
          </Typography.Text>
        </Form>
      </Modal>
    </Card>
  );
}
