import { Button, Card, Form, Input, Select, Typography, message } from "antd";
import { api } from "../api";

type Props = {
  onSuccess: () => void;
};

export function LoginPage({ onSuccess }: Props) {
  const [form] = Form.useForm();

  const onSubmit = async () => {
    const values = await form.validateFields();
    await api.login(values.username, values.role);
    message.success("登录成功");
    onSuccess();
  };

  return (
    <Card style={{ maxWidth: 480, margin: "48px auto" }}>
      <Typography.Title level={4}>Admin Login</Typography.Title>
      <Form form={form} layout="vertical" initialValues={{ username: "admin", role: "SYSTEM_ADMIN" }}>
        <Form.Item name="username" label="Username" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="role" label="Role" rules={[{ required: true }]}>
          <Select
            options={[
              { label: "SYSTEM_ADMIN", value: "SYSTEM_ADMIN" },
              { label: "STRATEGY_OPERATOR", value: "STRATEGY_OPERATOR" },
              { label: "COLLECTION_SUPERVISOR", value: "COLLECTION_SUPERVISOR" }
            ]}
          />
        </Form.Item>
        <Button type="primary" onClick={onSubmit}>
          Login
        </Button>
      </Form>
    </Card>
  );
}
