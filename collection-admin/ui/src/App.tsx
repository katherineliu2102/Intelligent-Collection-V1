import {
  AlertOutlined,
  DashboardOutlined,
  FileSearchOutlined,
  MailOutlined,
  SafetyOutlined,
  SettingOutlined,
  SlidersOutlined
} from "@ant-design/icons";
import { Layout, Menu, Typography } from "antd";
import type { MenuProps } from "antd";
import { useEffect, useMemo, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { api } from "./api";
import { CasesPage } from "./pages/CasesPage";
import { CompliancePage } from "./pages/CompliancePage";
import { LoginPage } from "./pages/LoginPage";
import { OpsPage } from "./pages/OpsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { StrategyPage } from "./pages/StrategyPage";
import { SystemPage } from "./pages/SystemPage";
import { TemplatesPage } from "./pages/TemplatesPage";

const { Header, Sider, Content } = Layout;

type Item = Required<MenuProps>["items"][number];

export default function App() {
  const location = useLocation();
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);
  const [authed, setAuthed] = useState(false);

  const items = useMemo<Item[]>(
    () => [
      { key: "/dashboard", icon: <DashboardOutlined />, label: "Data Analysis" },
      { key: "/strategy", icon: <SlidersOutlined />, label: "Strategy Config" },
      { key: "/templates", icon: <MailOutlined />, label: "Templates" },
      { key: "/cases", icon: <FileSearchOutlined />, label: "Case Monitor" },
      { key: "/ops", icon: <AlertOutlined />, label: "Ops Queue" },
      { key: "/compliance", icon: <SafetyOutlined />, label: "Compliance" },
      { key: "/system", icon: <SettingOutlined />, label: "System Admin" }
    ],
    []
  );

  useEffect(() => {
    const init = async () => {
      try {
        await api.me();
        setAuthed(true);
      } catch {
        setAuthed(false);
      } finally {
        setReady(true);
      }
    };
    init();
  }, []);

  if (!ready) {
    return <div style={{ padding: 24 }}>Loading...</div>;
  }

  if (!authed) {
    return <LoginPage onSuccess={() => setAuthed(true)} />;
  }

  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Sider width={240} theme="light">
        <div style={{ padding: 16 }}>
          <Typography.Title level={5} style={{ margin: 0 }}>
            MOCASA Admin
          </Typography.Title>
          <Typography.Text type="secondary">Phase 1 Shell</Typography.Text>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={items}
          onClick={(e) => navigate(e.key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: "#fff",
            borderBottom: "1px solid #f0f0f0",
            display: "flex",
            alignItems: "center"
          }}
        >
          <Typography.Text strong>Collection Admin UI (React + Ant Design)</Typography.Text>
        </Header>
        <Content style={{ padding: 24, overflow: "auto", minWidth: 0 }}>
          <Routes>
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/strategy" element={<StrategyPage />} />
            <Route path="/templates" element={<TemplatesPage />} />
            <Route path="/cases" element={<CasesPage />} />
            <Route path="/ops" element={<OpsPage />} />
            <Route path="/compliance" element={<CompliancePage />} />
            <Route path="/system" element={<SystemPage />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  );
}
