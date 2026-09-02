# collection-admin/ui

管理后台前端壳工程（React + Vite + Ant Design）。

## 目录说明

- `src/App.tsx`：主布局与路由
- `src/api.ts`：后端 API 封装（`/auth`、`/cases`、`/ops`、`/compliance`、`/admin`）
- `src/pages/`：页面组件（按业务模块分类）

## 本地运行

启动、登录、排障的完整说明以 **[管理后台操作手册 §2](../../docs/MOCASA催收系统升级_Phase1_管理后台操作手册.md#2-启动与登录)** 为准。本文件只给最短命令。

Windows（一键）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/start-admin.ps1
```

macOS / Linux：

```bash
./scripts/dev/start-local.sh
# 另开终端
cd collection-admin/ui && npm install && npm run dev -- --host 127.0.0.1 --port 5173
```

浏览器：**http://127.0.0.1:5173**。不要用 `file://` 打开 `index.html`。

Nacos / `.env` / 进程见 [操作说明_Nacos本地启动](../../docs/操作说明_Nacos本地启动.md)。
