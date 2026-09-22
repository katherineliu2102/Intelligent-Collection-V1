import {
  AlertOutlined,
  DashboardOutlined,
  FileSearchOutlined,
  MailOutlined,
  SafetyOutlined,
  SettingOutlined,
  SlidersOutlined
} from "@ant-design/icons";
import { Button, Layout, Menu, Space, Typography, message } from "antd";
import type { MenuProps } from "antd";
import { useEffect, useMemo, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { api } from "./api";
import { RoleContext, parseAdminRole, type AdminRole } from "./auth";
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
  const [role, setRole] = useState<AdminRole>("VIEWER");
  const [username, setUsername] = useState("");

  const items = useMemo<Item[]>(() => {
    const all: Item[] = [
      { key: "/dashboard", icon: <DashboardOutlined />, label: "Data Analysis" },
      { key: "/strategy", icon: <SlidersOutlined />, label: "Strategy Config" },
      { key: "/templates", icon: <MailOutlined />, label: "Templates" },
      { key: "/cases", icon: <FileSearchOutlined />, label: "Case Monitor" },
      { key: "/ops", icon: <AlertOutlined />, label: "Ops Queue" },
      { key: "/compliance", icon: <SafetyOutlined />, label: "Compliance" }
    ];
    if (role === "SYSTEM_ADMIN") {
      all.push({ key: "/system", icon: <SettingOutlined />, label: "System Admin" });
    }
    return all;
  }, [role]);

  const refreshSession = async () => {
    const resp = await api.me();
    setRole(parseAdminRole(resp?.data?.role));
    setUsername(String(resp?.data?.username || ""));
    setAuthed(true);
  };

  const signOut = async () => {
    try {
      await api.logout();
    } catch {
      // 本地清会话即可；接口失败也不应把人留在已登录壳里
    }
    setAuthed(false);
    setRole("VIEWER");
    setUsername("");
    message.success("已登出");
  };

  useEffect(() => {
    const init = async () => {
      try {
        await refreshSession();
      } catch {
        setAuthed(false);
        setRole("VIEWER");
        setUsername("");
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
    return (
      <LoginPage
        onSuccess={async () => {
          try {
            await refreshSession();
          } catch {
            setAuthed(false);
          }
        }}
      />
    );
  }

  return (
    <RoleContext.Provider value={role}>
      <Layout style={{ minHeight: "100vh" }}>
        <Sider width={240} theme="light">
          <div style={{ padding: 16 }}>
            <Typography.Title level={5} style={{ margin: 0 }}>
              Collections Admin
            </Typography.Title>
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
              alignItems: "center",
              justifyContent: "space-between",
              paddingInline: 24
            }}
          >
            <Typography.Text strong>Collections Admin</Typography.Text>
            <Space size={12}>
              <Typography.Text type="secondary">
                {username || "—"} · {role}
              </Typography.Text>
              <Button size="small" onClick={signOut}>
                登出
              </Button>
            </Space>
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
    </RoleContext.Provider>
  );
}
