import { Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

type Step = { channel: string; delayMin: number; observeMin: number; templateId: number };

type PlanTemplate = {
  id: number;
  templateCode: string;
  stage: string;
  tone?: string;
  productCode?: string;
  planJson: string;
  version: number;
  configVersion?: number;
  updatedBy?: string;
};

const CHANNELS = ["SMS", "PUSH", "EMAIL", "AI_CALL", "TTS"];

function parseSteps(planJson?: string): Step[] {
  if (!planJson) return [];
  try {
    const obj = JSON.parse(planJson);
    return Array.isArray(obj.steps) ? obj.steps : [];
  } catch {
    return [];
  }
}

function stepsSummary(planJson?: string): string {
  return parseSteps(planJson)
    .map((s) => s.channel)
    .join(" → ");
}

export function PlanTemplatesTab() {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<PlanTemplate[]>([]);
  const [editing, setEditing] = useState<PlanTemplate | null>(null);
  const [steps, setSteps] = useState<Step[]>([]);
  const [reason, setReason] = useState("Edit from admin UI");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const resp = (await api.listPlanTemplates()) as { data: PlanTemplate[] };
      setRows(resp.data || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openEdit = (row: PlanTemplate) => {
    setEditing(row);
    setSteps(parseSteps(row.planJson));
    setReason("Edit from admin UI");
  };

  const updateStep = (idx: number, patch: Partial<Step>) => {
    setSteps((prev) => prev.map((s, i) => (i === idx ? { ...s, ...patch } : s)));
  };

  const addStep = () =>
    setSteps((prev) => [...prev, { channel: "SMS", delayMin: 0, observeMin: 0, templateId: 101 }]);

  const removeStep = (idx: number) => setSteps((prev) => prev.filter((_, i) => i !== idx));

  const deactivate = async (row: PlanTemplate) => {
    try {
      await api.deactivatePlanTemplate(row.templateCode);
      message.success(`Deactivated ${row.templateCode}. Engine reloads within ~10s.`);
      await load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const save = async () => {
    if (!editing) return;
    if (steps.length === 0) {
      message.warning("At least one step is required");
      return;
    }
    setSaving(true);
    try {
      await api.updatePlanTemplate({
        templateCode: editing.templateCode,
        stage: editing.stage,
        tone: editing.tone,
        productCode: editing.productCode,
        steps,
        version: editing.version,
        reason
      });
      message.success(`Saved ${editing.templateCode}. Engine reloads within ~10s.`);
      setEditing(null);
      await load();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      <Space>
        <Typography.Text type="secondary">
          计划模板按 Stage 定义触达步骤序列（<code>t_contact_plan_template</code>）。按需覆盖：已编辑
          &gt; 系统默认；保存后约 10 秒引擎热更新。
        </Typography.Text>
        <Button onClick={load} loading={loading}>
          Refresh
        </Button>
      </Space>
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        pagination={false}
        dataSource={rows}
        columns={[
          { title: "Code", dataIndex: "templateCode", width: 180 },
          { title: "Stage", dataIndex: "stage", width: 80 },
          { title: "Tone", dataIndex: "tone", width: 100 },
          {
            title: "Steps",
            render: (_: any, r: PlanTemplate) => <Tag>{stepsSummary(r.planJson) || "—"}</Tag>
          },
          { title: "Ver", dataIndex: "version", width: 60 },
          {
            title: "Action",
            width: 170,
            render: (_: any, r: PlanTemplate) => (
              <Space>
                <Button size="small" onClick={() => openEdit(r)}>
                  Edit
                </Button>
                <Button size="small" danger onClick={() => deactivate(r)}>
                  Deactivate
                </Button>
              </Space>
            )
          }
        ]}
      />

      <Modal
        title={editing ? `Edit Plan · ${editing.templateCode} (${editing.stage})` : ""}
        open={!!editing}
        onOk={save}
        confirmLoading={saving}
        onCancel={() => setEditing(null)}
        okText="Save"
        width={760}
      >
        <Table
          rowKey={(_, i) => String(i)}
          size="small"
          pagination={false}
          dataSource={steps}
          columns={[
            {
              title: "#",
              width: 50,
              render: (_: any, __: Step, i: number) => i + 1
            },
            {
              title: "Channel",
              width: 130,
              render: (_: any, s: Step, i: number) => (
                <Select
                  size="small"
                  style={{ width: 120 }}
                  value={s.channel}
                  options={CHANNELS.map((c) => ({ value: c, label: c }))}
                  onChange={(v) => updateStep(i, { channel: v })}
                />
              )
            },
            {
              title: "Delay(min)",
              width: 110,
              render: (_: any, s: Step, i: number) => (
                <InputNumber
                  size="small"
                  min={0}
                  value={s.delayMin}
                  onChange={(v) => updateStep(i, { delayMin: Number(v) || 0 })}
                />
              )
            },
            {
              title: "Observe(min)",
              width: 120,
              render: (_: any, s: Step, i: number) => (
                <InputNumber
                  size="small"
                  min={0}
                  value={s.observeMin}
                  onChange={(v) => updateStep(i, { observeMin: Number(v) || 0 })}
                />
              )
            },
            {
              title: "Template ID",
              width: 120,
              render: (_: any, s: Step, i: number) => (
                <InputNumber
                  size="small"
                  min={0}
                  value={s.templateId}
                  onChange={(v) => updateStep(i, { templateId: Number(v) || 0 })}
                />
              )
            },
            {
              title: "",
              width: 70,
              render: (_: any, __: Step, i: number) => (
                <Button size="small" danger onClick={() => removeStep(i)}>
                  Del
                </Button>
              )
            }
          ]}
        />
        <Space style={{ marginTop: 12, width: "100%" }} direction="vertical">
          <Button onClick={addStep}>+ Add Step</Button>
          <Input
            addonBefore="Reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <Typography.Text type="secondary">
            Optimistic lock version: {editing?.version ?? 0}
          </Typography.Text>
        </Space>
      </Modal>
    </Space>
  );
}
