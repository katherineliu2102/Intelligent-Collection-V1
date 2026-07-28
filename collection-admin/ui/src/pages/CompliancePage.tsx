import { Button, Card, Form, Input, Select, Space, Typography, message } from "antd";
import { api } from "../api";

export function CompliancePage() {
  const [form] = Form.useForm();

  const submit = async () => {
    const values = await form.validateFields();
    await api.compliance(values.action, {
      caseId: Number(values.loanId),
      userId: values.userId ? Number(values.userId) : undefined,
      reason: values.reason
    });
    message.success("操作成功");
  };

  return (
    <Card title="Compliance Ops">
      <Space direction="vertical" style={{ width: 520 }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          冻结/解冻/升级均按 <b>loan_id</b>（业务贷款单号，与 Case Monitor 检索一致；API 字段仍为 caseId）。
        </Typography.Paragraph>
        <Form
          form={form}
          layout="vertical"
          initialValues={{ action: "freeze", loanId: 99000002, reason: "Customer complaint" }}
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
          <Form.Item
            name="loanId"
            label="Loan ID (loan_id)"
            rules={[{ required: true, message: "请输入 loan_id" }]}
          >
            <Input placeholder="例如 99000002" />
          </Form.Item>
          <Form.Item name="userId" label="User ID（可选，仅 freeze 时记录）">
            <Input placeholder="一般可不填" />
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
