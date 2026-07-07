# collection-admin/ui

管理后台前端壳工程（React + Vite + Ant Design）。

## 目录说明

- `src/App.tsx`：主布局与路由
- `src/api.ts`：后端 API 封装（`/auth`、`/cases`、`/ops`、`/compliance`、`/admin`）
- `src/pages/`：页面组件（按业务模块分类）

## 本地运行

**不要直接双击或用浏览器打开 `index.html`**。这是 Vite + React 工程，`<script type="module" src="/src/main.tsx">` 必须通过开发服务器加载，直接打开会空白或报错。

1. 先启动后端（项目根目录）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/start-local.ps1
```

2. 再启动前端：

```bash
cd collection-admin/ui
npm install
npm run dev
```

3. 浏览器访问：**http://localhost:5173**

默认账号：`admin` / 角色 `SYSTEM_ADMIN`（见登录页）。

> 若当前环境没有 `npm`，请先安装 Node.js（包含 npm）。
