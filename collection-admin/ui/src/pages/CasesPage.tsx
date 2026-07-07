import { Button, Card, Form, Input, Space, Table, message } from "antd";
import { useState } from "react";
import { api } from "../api";

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
        <Table
          rowKey="caseId"
          loading={loading}
          dataSource={items}
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
