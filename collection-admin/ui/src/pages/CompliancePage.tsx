import { Button, Card, Form, Input, Select, Space, message } from "antd";
import { api } from "../api";

export function CompliancePage() {
  const [form] = Form.useForm();

  const submit = async () => {
    const values = await form.validateFields();
    await api.compliance(values.action, {
      caseId: Number(values.caseId),
      userId: values.userId ? Number(values.userId) : undefined,
      reason: values.reason
    });
    message.success("操作成功");
  };

  return (
    <Card title="Compliance Ops">
      <Space direction="vertical" style={{ width: 520 }}>
        <Form
          form={form}
          layout="vertical"
          initialValues={{ action: "freeze", caseId: 92002, reason: "Customer complaint" }}
        >
          <Form.Item name="action" label="Action" rules={[{ required: true }]}>
            <Select
              options={[
                { label: "Freeze", value: "freeze" },
                { label: "Unfreeze", value: "unfreeze" },
                { label: "Escalate", value: "escalate" }
              ]}
            />
          </Form.Item>
          <Form.Item name="caseId" label="Case ID" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="userId" label="User ID (optional)">
            <Input />
          </Form.Item>
          <Form.Item name="reason" label="Reason" rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Button type="primary" onClick={submit}>
            Submit
          </Button>
        </Form>
      </Space>
    </Card>
  );
}
