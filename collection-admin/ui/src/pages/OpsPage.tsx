import { Button, Card, Select, Space, Table, Tooltip, message } from "antd";
import { useState } from "react";
import { api } from "../api";

export function OpsPage() {
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState("OPEN");
  const [items, setItems] = useState<any[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await api.listOps({ status, page: 1, pageSize: 20 });
      setItems(resp.data.items || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card title="Ops Queue">
      <Space direction="vertical" style={{ width: "100%" }}>
        <Space>
          <Select
            value={status}
            style={{ width: 180 }}
            options={[
              { value: "OPEN", label: "OPEN" },
              { value: "ACK", label: "ACK" },
              { value: "RESOLVED", label: "RESOLVED" },
              { value: "IGNORED", label: "IGNORED" }
            ]}
            onChange={(v) => setStatus(v)}
          />
          <Button type="primary" onClick={load} loading={loading}>
            Refresh
          </Button>
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={items}
          columns={[
            { title: "ID", dataIndex: "id" },
            { title: "Type", dataIndex: "exceptionType" },
            { title: "Channel", dataIndex: "channel" },
            { title: "Error", dataIndex: "errorCode" },
            { title: "Case", dataIndex: "caseId" },
            { title: "Status", dataIndex: "status" },
            {
              title: "Action",
              render: (_, row) =>
                row.status === "OPEN" ? (
                  <Tooltip title="尚未接入引擎，暂不可处置">
                    <Space>
                      <Button size="small" disabled>
                        ACK
                      </Button>
                      <Button size="small" type="primary" disabled>
                        Resolve
                      </Button>
                    </Space>
                  </Tooltip>
                ) : (
                  "—"
                )
            }
          ]}
        />
      </Space>
    </Card>
  );
}
