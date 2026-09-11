import { Button, Card, Form, Input, Typography, message } from "antd";
import { api } from "../api";

type Props = {
  onSuccess: () => void;
};

export function LoginPage({ onSuccess }: Props) {
  const [form] = Form.useForm();

  const onSubmit = async () => {
    const values = await form.validateFields();
    await api.login(values.username, values.password);
    message.success("登录成功");
    onSuccess();
  };

  return (
    <Card style={{ maxWidth: 480, margin: "48px auto" }}>
      <Typography.Title level={4}>Collections Admin</Typography.Title>
      <Form form={form} layout="vertical">
        <Form.Item name="username" label="Username" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="password" label="Password" rules={[{ required: true }]}>
          <Input.Password />
        </Form.Item>
        <Button type="primary" onClick={onSubmit}>
          Login
        </Button>
      </Form>
    </Card>
  );
}
