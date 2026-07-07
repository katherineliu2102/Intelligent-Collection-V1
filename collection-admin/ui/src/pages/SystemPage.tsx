import { Button, Card, Table, message } from "antd";
import { useState } from "react";
import { api } from "../api";

export function SystemPage() {
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<any[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await api.auditLogs();
      setItems(resp.data.items || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card title="System Admin">
      <Button type="primary" onClick={load} loading={loading} style={{ marginBottom: 16 }}>
        Load Audit Logs
      </Button>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={items}
        columns={[
          { title: "ID", dataIndex: "id" },
          { title: "Type", dataIndex: "configType" },
          { title: "Key", dataIndex: "configKey" },
          { title: "Operator", dataIndex: "operator" },
          { title: "CreatedAt", dataIndex: "createdAt" }
        ]}
      />
    </Card>
  );
}
