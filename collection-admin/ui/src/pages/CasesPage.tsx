import { Button, Card, Form, Input, Space, Spin, Table, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { api } from "../api";

function resultColor(v?: string): string {
  switch (v) {
    case "DELIVERED":
      return "green";
    case "SKIPPED":
      return "orange";
    case "FAILED":
      return "red";
    default:
      return "default";
  }
}

function PlanSteps({ planId }: { planId: number }) {
  const [steps, setSteps] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    api
      .planSteps(planId)
      .then((d) => {
        if (alive) setSteps((d as any[]) || []);
      })
      .catch((e: any) => message.error(e.message))
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [planId]);

  return (
    <Table
      rowKey="id"
      size="small"
      loading={loading}
      pagination={false}
      dataSource={steps}
      columns={[
        { title: "#", dataIndex: "stepOrder", width: 60 },
        { title: "Channel", dataIndex: "channelType", width: 100 },
        { title: "Template", dataIndex: "templateId", width: 100 },
        { title: "Status", dataIndex: "status", width: 120 },
        {
          title: "Result",
          dataIndex: "result",
          width: 120,
          render: (v: string) => (v ? <Tag color={resultColor(v)}>{v}</Tag> : "—")
        },
        { title: "Completed At", dataIndex: "completedAt", width: 190 }
      ]}
    />
  );
}

function CaseDetail({ caseId, userId }: { caseId: number; userId?: number }) {
  const [loading, setLoading] = useState(true);
  const [plans, setPlans] = useState<any[]>([]);
  const [timeline, setTimeline] = useState<any[]>([]);

  useEffect(() => {
    let alive = true;
    const tasks: Promise<any>[] = [api.planHistoryByCase(caseId, 10)];
    tasks.push(userId != null ? api.timelineByUser(userId, 50) : Promise.resolve([]));
    Promise.all(tasks)
      .then(([p, t]) => {
        if (!alive) return;
        setPlans((p as any[]) || []);
        setTimeline((t as any[]) || []);
      })
      .catch((e: any) => message.error(e.message))
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [caseId, userId]);

  if (loading) {
    return <Spin />;
  }

  return (
    <Space direction="vertical" style={{ width: "100%" }} size={12}>
      <Typography.Text strong>Plans (incl. completed) — expand for steps</Typography.Text>
      <Table
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={plans}
        expandable={{ expandedRowRender: (p) => <PlanSteps planId={Number(p.id)} /> }}
        columns={[
          { title: "Plan", dataIndex: "id", width: 90 },
          { title: "Stage", dataIndex: "stage", width: 80 },
          { title: "Status", dataIndex: "status", width: 160 },
          {
            title: "Progress",
            width: 100,
            render: (_: any, r: any) => `${r.currentStep ?? "?"}/${r.totalSteps ?? "?"}`
          },
          { title: "Started", dataIndex: "startedAt", width: 180 },
          { title: "Completed", dataIndex: "completedAt", width: 180 }
        ]}
      />

      <Typography.Text strong>Contact Timeline (what was actually sent)</Typography.Text>
      <Table
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={timeline}
        columns={[
          { title: "Channel", dataIndex: "channel", width: 90 },
          { title: "Dir", dataIndex: "direction", width: 70 },
          { title: "Template", dataIndex: "templateId", width: 90 },
          {
            title: "Result",
            dataIndex: "result",
            width: 110,
            render: (v: string) => (v ? <Tag color={resultColor(v)}>{v}</Tag> : "—")
          },
          { title: "Provider Msg Id", dataIndex: "providerMsgId", ellipsis: true },
          { title: "Source", dataIndex: "source", width: 100 },
          { title: "Created At", dataIndex: "createdAt", width: 180 }
        ]}
      />
    </Space>
  );
}

export function CasesPage() {
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<any[]>([]);
  const [form] = Form.useForm();

  const query = async () => {
    setLoading(true);
    try {
      const values = form.getFieldsValue();
      const resp = await api.searchCases({ page: 1, pageSize: 20, ...values });
      setItems(resp.data.items || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card title="Case Monitor">
      <Space direction="vertical" style={{ width: "100%" }}>
        <Form form={form} layout="inline" initialValues={{}}>
          <Form.Item name="caseId" label="Case ID">
            <Input placeholder="92002" />
          </Form.Item>
          <Form.Item name="userId" label="User ID">
            <Input />
          </Form.Item>
          <Button type="primary" onClick={query} loading={loading}>
            Search
          </Button>
        </Form>
        <Typography.Text type="secondary">
          Expand a row to see plan steps and the contact timeline (what was actually sent).
        </Typography.Text>
        <Table
          rowKey="caseId"
          loading={loading}
          dataSource={items}
          expandable={{
            expandedRowRender: (row) => (
              <CaseDetail
                caseId={Number(row.caseId)}
                userId={row.userId != null ? Number(row.userId) : undefined}
              />
            )
          }}
          columns={[
            { title: "Case ID", dataIndex: "caseId" },
            { title: "User ID", dataIndex: "userId" },
            { title: "Stage", dataIndex: "stage" },
            { title: "DPD", dataIndex: "dpd" },
            { title: "Plan Status", dataIndex: "planStatus" },
            { title: "Frozen", dataIndex: "frozen", render: (v) => (v ? "Y" : "N") },
            { title: "Phone", dataIndex: "phone" },
            { title: "Email", dataIndex: "email" }
          ]}
        />
      </Space>
    </Card>
  );
}
