import { Button, Card, Form, Input, Modal, Select, Space, Switch, Table, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { isSystemAdmin, useAdminRole, type AdminRole } from "../auth";

type AccountRow = {
  id: number;
  username: string;
  role: AdminRole;
  enabled: boolean;
  updatedAt?: string;
  updatedBy?: string;
};

export function SystemPage() {
  const admin = isSystemAdmin(useAdminRole());
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<AccountRow[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [resetId, setResetId] = useState<number | null>(null);
  const [form] = Form.useForm();
  const [resetForm] = Form.useForm();

  const load = useCallback(async () => {
    if (!admin) {
      return;
    }
    setLoading(true);
    try {
      const resp = await api.listAccounts();
      setItems(resp.data || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  }, [admin]);

  useEffect(() => {
    load();
  }, [load]);

  if (!admin) {
    return (
      <Card title="Accounts">
        仅 SYSTEM_ADMIN 可管理账号。配置变更日志在 Strategy → Config Versions。
      </Card>
    );
  }

  const onCreate = async () => {
    const values = await form.validateFields();
    await api.createAccount(values);
    message.success("已创建");
    setCreateOpen(false);
    form.resetFields();
    await load();
  };

  const onToggle = async (row: AccountRow, enabled: boolean) => {
    try {
      await api.patchAccount(row.id, { enabled });
      await load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const onRole = async (row: AccountRow, role: AdminRole) => {
    try {
      await api.patchAccount(row.id, { role });
      await load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const onReset = async () => {
    if (resetId == null) {
      return;
    }
    const values = await resetForm.validateFields();
    await api.patchAccount(resetId, { password: values.password });
    message.success("口令已重置");
    setResetId(null);
    resetForm.resetFields();
    await load();
  };

  return (
    <Card
      title="Accounts"
      extra={
        <Space>
          <Button onClick={load} loading={loading}>
            Refresh
          </Button>
          <Button type="primary" onClick={() => setCreateOpen(true)}>
            新建账号
          </Button>
        </Space>
      }
    >
      <Table
        rowKey="id"
        loading={loading}
        pagination={false}
        dataSource={items}
        columns={[
          { title: "Username", dataIndex: "username" },
          {
            title: "Role",
            dataIndex: "role",
            width: 180,
            render: (role: AdminRole, row: AccountRow) => (
              <Select
                value={role}
                style={{ width: 160 }}
                options={[
                  { value: "VIEWER", label: "VIEWER" },
                  { value: "OPERATOR", label: "OPERATOR" },
                  { value: "SYSTEM_ADMIN", label: "SYSTEM_ADMIN" }
                ]}
                onChange={(v) => onRole(row, v)}
              />
            )
          },
          {
            title: "Enabled",
            dataIndex: "enabled",
            width: 100,
            render: (enabled: boolean, row: AccountRow) => (
              <Switch checked={enabled} onChange={(v) => onToggle(row, v)} />
            )
          },
          { title: "Updated by", dataIndex: "updatedBy", width: 140 },
          {
            title: "Action",
            width: 120,
            render: (_: unknown, row: AccountRow) => (
              <Button size="small" onClick={() => setResetId(row.id)}>
                重置口令
              </Button>
            )
          }
        ]}
      />

      <Modal
        title="新建账号"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" initialValues={{ role: "VIEWER" }}>
          <Form.Item name="username" label="Username" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="password"
            label="Password"
            rules={[{ required: true, min: 8, message: "至少 8 位" }]}
          >
            <Input.Password />
          </Form.Item>
          <Form.Item name="role" label="Role" rules={[{ required: true }]}>
            <Select
              options={[
                { value: "VIEWER", label: "VIEWER" },
                { value: "OPERATOR", label: "OPERATOR" },
                { value: "SYSTEM_ADMIN", label: "SYSTEM_ADMIN" }
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="重置口令"
        open={resetId != null}
        onOk={onReset}
        onCancel={() => setResetId(null)}
        destroyOnClose
      >
        <Form form={resetForm} layout="vertical">
          <Form.Item
            name="password"
            label="New password"
            rules={[{ required: true, min: 8, message: "至少 8 位" }]}
          >
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
