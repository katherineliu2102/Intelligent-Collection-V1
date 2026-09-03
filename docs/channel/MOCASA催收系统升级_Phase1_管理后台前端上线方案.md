# MOCASA 催收系统升级 Phase 1 — 管理后台前端上线方案

> 适用对象：管理后台（`collection-admin`）前端 UI 的上线部署
> 部署机：Pilot 机 `bdp01`；对外域名 `https://collection-admin.mocasa.com`
> 关联文档：`MOCASA催收系统升级_Phase1_发版手册.md`（后端 jar 发版）、`MOCASA催收系统升级_Phase1_管理后台设计文档.md`

## 目录

- [1. 摘要与结论](#1-摘要与结论)
- [2. 现状与目标](#2-现状与目标)
- [3. 方案决策](#3-方案决策)
- [4. 上线前置条件（开发期必须完成）](#4-上线前置条件开发期必须完成)
- [5. 上线操作步骤](#5-上线操作步骤)
- [6. 目标态 nginx 完整配置](#6-目标态-nginx-完整配置)
- [7. 验证清单](#7-验证清单)
- [8. 回滚方案](#8-回滚方案)
- [9. 风险与注意事项](#9-风险与注意事项)
- [附录 A：管理面 API 端点清单](#附录-a管理面-api-端点清单)

---

## 1. 摘要与结论

**方案：前端独立部署（nginx 托管静态产物），后端容器零改动；管理面 API 走统一 `/api` 前缀，由 nginx 剥离前缀后转发到后端 `127.0.0.1:8080`，并用 IP 白名单保护。**

核心结论：

| # | 结论 |
|---|---|
| 1 | 前端**永不进 jar**。前端构建产物（`dist/`）是独立交付物，由 nginx 托管；后端 jar 与容器不动 |
| 2 | 前端与 API **同源部署**（同一域名 `collection-admin.mocasa.com`），保住 `JSESSIONID` cookie 登录会话 |
| 3 | 管理面 API 统一加 **`/api` 前缀**，与页面路由彻底分离，nginx 只代理 `/api`，其余走 SPA |
| 4 | 上线分两步：**先上静态页（零风险）→ 再开放管理面（认证 + IP 白名单）**，各自可独立回滚 |
| 5 | 上线全程**不重启容器、不碰 `/webhook/` 回调路径**，催收链路零影响 |

**为什么后端 API 不受影响**：本方案只新增 nginx 静态托管与 `/api` 代理配置，后端 `collection-admin` 容器全程不停止、不重启、不改 env。`/webhook/` 回调路径保持原样，进件、触达、AI Call 回调链路无感知。

---

## 2. 现状与目标

### 2.1 现状（已核实）

| 项 | 现状 |
|---|---|
| 后端 API | 已在 `bdp01` 运行，容器 `collection-admin`（镜像 `intelligent-collection-admin:pilot`），绑定 `127.0.0.1:8080` |
| 域名与证书 | `collection-admin.mocasa.com` 已就绪，nginx + SSL（`/etc/nginx/ssl/collection-admin.mocasa.com.{pem,key}`）已配置 |
| 前端 UI | **未部署**。Dockerfile 仅 COPY 后端 jar，pom 无前端构建步骤 |
| 公网访问 | nginx 仅放通 `/webhook/`（第三方回调），其余一律 `403`（`deploy/nginx/collection-admin.mocasa.com.conf`） |
| 后端认证 | **已就绪**：`AdminAuthenticator`（BCrypt）校验用户名口令，账号经环境变量 `COLLECTION_ADMIN_USER` / `COLLECTION_ADMIN_PASSWORD_HASH` 注入 |
| 前端登录页 | **开发态**：`LoginPage.tsx` 仅「用户名 + 角色」两栏，无密码框，且角色由前端自选（后端会忽略前端传入的 role、按账号配置返回） |

### 2.2 目标

让管理后台 UI 在 `https://collection-admin.mocasa.com` 可访问、可登录，供团队内部运营使用，同时：

- 不影响后端 API 与催收主链路（进件、触达、AI Call 回调）；
- 管理面仅白名单 IP 可访问，公网只保留 `/webhook/` 回调入口。

---

## 3. 方案决策

### 3.1 前端独立部署，不进 jar

| 维度 | 前端进 jar（发版一次） | 前端独立部署（本方案） |
|---|---|---|
| 前端小改动 | 走全量发版门禁（CI/Spotless/测试/构建）+ 重启引擎容器 | `npm run build` → 传静态文件 → `nginx -s reload`，分钟级 |
| 对催收链路 | 每次重启有中断窗口，`/webhook/` 回调短暂中断 | 零影响 |
| 前后端耦合 | 耦合在一个 jar，前端迭代绑架引擎发版 | 彻底分离，各自演进 |

结论：前端独立部署是唯一符合「高效、成熟、便于运营」目标的选择。

### 3.2 同源部署的必要性

前端请求后端使用 `credentials: "include"`，登录会话依赖 `JSESSIONID` cookie。若前端与 API 跨域（不同域名/端口），cookie 无法自动携带，登录态失效。因此前端静态页与 API 必须部署在同一域名下，由 nginx 同源代理。

### 3.3 API 加 `/api` 前缀（消除页面路由与 API 路径的暧昧）

前端页面路由（BrowserRouter）与后端 API 路径存在同名冲突：

| 路径 | 类型 |
|---|---|
| `/dashboard`、`/cases`、`/ops`、`/compliance`、`/strategy`、`/templates`、`/system` | **页面路由**（应返回 index.html） |
| `/cases/search`、`/ops/exceptions`、`/compliance/freeze`、`/dashboard/outreach/realtime` | **API**（应转发后端） |

若让 nginx 按精确子路径逐一匹配，每新增一个 API 端点都要补一条 location，维护成本高且易漏。统一给前端 API 加 `/api` 前缀后，nginx 只需一条 `location ^~ /api/` 代理规则，页面与 API 零歧义、零维护。

---

## 4. 上线前置条件（开发期必须完成）

以下两项必须在**上线前**于前端代码中完成并本地验证通过，属于「开发好、测试好」的范围。

### 4.1 登录页补密码框（P0，安全必改）

后端认证已就绪，但前端登录页缺密码框，当前用页面登录必返回 `401`。同时前端存在「角色自选」下拉框，与后端「角色由账号配置决定」的口径冲突，应删除。

**改动点：**

1. `collection-admin/ui/src/pages/LoginPage.tsx`：删除 role 下拉框，新增 `password` 输入框（`Input.Password`）。
2. `collection-admin/ui/src/api.ts`：`login` 签名由 `login(username, role)` 改为 `login(username, password)`，请求体为 `{ username, password }`。

> 后端 `AuthController.login` 已校验 `{ username, password }`，账号的角色由 `collection.admin.auth.accounts`（本地）或环境变量（pilot）配置决定，前端不应、也无法自选角色。

### 4.2 API 加 `/api` 前缀（P1，架构必改）

1. `collection-admin/ui/src/api.ts`：保持 `API_BASE = import.meta.env.VITE_API_BASE || ""` 不变，通过环境变量注入前缀。
2. 新增 `collection-admin/ui/.env.production`：
   ```
   VITE_API_BASE=/api
   ```
3. `collection-admin/ui/vite.config.ts`：将分散的 proxy 简化为 `/api` 单条，开发与生产一致：
   ```ts
   proxy: {
     "/api": {
       target: "http://localhost:8888",   // 本机 8888 被占时改 8899
       changeOrigin: true,
       rewrite: (p) => p.replace(/^\/api/, "")
     }
   }
   ```
4. 本地开发 `.env.development`（或 `.env`）同步加 `VITE_API_BASE=/api`，确保开发、生产路径一致。

### 4.3 本地验证通过

```
cd collection-admin/ui
npm run build          # 产物在 dist/，检查 dist/index.html 存在
```

本地跑通页面登录（输入 admin 账号密码）并确认各模块 API 经 `/api` 前缀正常返回。

---

## 5. 上线操作步骤

### 5.1 第一步：静态页上线（零风险，不开放管理面 API）

此步只把前端页面挂上去，**不开放 `/api` 代理**，管理面仍不可登录，用于验证静态托管与 SPA 路由，无任何安全暴露。

**1. 本机构建**

```bash
cd D:/AI/Intelligent-Collection-V1/collection-admin/ui
npm run build
ls -lh dist/index.html    # 应存在
```

**2. 上传到服务器**

```bash
ssh ubuntu@$PILOT_HOST "mkdir -p /opt/app/admin-ui"
scp -r dist/* ubuntu@$PILOT_HOST:/opt/app/admin-ui/
```

**3. 备份并更新 nginx（仅加静态托管，暂不加 /api）**

```bash
ssh ubuntu@$PILOT_HOST
sudo cp /etc/nginx/conf.d/collection-admin.mocasa.com.conf{,.bak.$(date +%Y%m%d%H%M)}
# 将 root 指向 /opt/app/admin-ui，并新增 SPA fallback（见 §6，先注释掉 /api 段）
sudo nginx -t && sudo nginx -s reload
```

**4. 验证静态页**（见 §7 第一步校验项）。

### 5.2 第二步：开放管理面（认证 + IP 白名单）

**前置：§4 两项前端改动已 build 并重新上传；确认办公出口 IP。**

**1. 获取办公出口 IP**

```bash
curl -s ifconfig.me    # 在本机执行，得到办公出口公网 IP
```

**2. 更新 nginx：启用 `/api` 代理 + IP 白名单**

按 §6 完整配置，取消 `/api` 段注释，填入白名单 IP。

```bash
sudo nginx -t && sudo nginx -s reload
```

**3. 验证登录与 API**（见 §7 第二步校验项）。

---

## 6. 目标态 nginx 完整配置

在现有 `deploy/nginx/collection-admin.mocasa.com.conf` 基础上，改动仅三处：`root` 指向前端产物目录、新增 `/api` 代理段、`location /` 改为 SPA fallback 并加白名单。**`/webhook/` 段一字不动。**

```nginx
upstream ups_collection {
    server 127.0.0.1:8080 max_fails=3 fail_timeout=10s;
}

server {
    listen      80;
    listen      443 ssl;
    server_name collection-admin.mocasa.com;
    root /opt/app/admin-ui;                 # 前端构建产物目录（改动 1）

    access_log /var/log/nginx/collection-admin.mocasa.com_access.log main;
    error_log  /var/log/nginx/collection-admin.mocasa.com_error.log;
    server_tokens off;

    ssl_certificate      /etc/nginx/ssl/collection-admin.mocasa.com.pem;
    ssl_certificate_key  /etc/nginx/ssl/collection-admin.mocasa.com.key;
    ssl_session_cache    shared:SSL:1m;
    ssl_session_timeout  5m;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers  on;

    proxy_headers_hash_max_size 51200;
    proxy_headers_hash_bucket_size 6400;

    location ^~ /.well-known/acme-challenge/ {
        default_type "text/plain";
    }

    # ── 第三方回调：唯一公网入口（保持现状，一字不动）──────────
    location ^~ /webhook/ {
        client_max_body_size 1m;
        proxy_pass http://ups_collection;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # ── 管理面 API：/api 前缀，剥离后转发，仅白名单 IP（改动 2）──
    location ^~ /api/ {
        allow <办公出口IP1>;     # 多个 IP 逐个 allow
        allow <办公出口IP2>;
        deny all;

        rewrite ^/api/(.*)$ /$1 break;
        proxy_pass http://ups_collection;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # ── 前端 SPA（改动 3）：白名单内可访问，未命中文件回 index.html ──
    location / {
        allow <办公出口IP1>;
        allow <办公出口IP2>;
        deny all;
        try_files $uri $uri/ /index.html;
    }
}
```

> 说明：`location ^~ /api/` 优先级高于 `location /`，故 `/api/*` 走代理、其余走 SPA，无歧义。登录会话 `JSESSIONID` cookie 在同源下由 nginx 默认透传，无需额外配置。

---

## 7. 验证清单

### 第一步（静态页）

| # | 检查项 | 命令 / 预期 |
|---|---|---|
| 1 | 页面可访问 | `curl -I https://collection-admin.mocasa.com/` 返回 `200`，`Content-Type: text/html` |
| 2 | SPA 路由回退 | 直接访问 `https://collection-admin.mocasa.com/cases` 返回 `200`（index.html，非 404） |
| 3 | 管理面 API 仍关闭 | `curl -I https://collection-admin.mocasa.com/api/auth/login` 返回 `403`（白名单/未开放） |
| 4 | 回调不受影响 | `curl -I https://collection-admin.mocasa.com/webhook/facade-callback` 返回后端响应（非 403） |
| 5 | 容器未重启 | `ssh ubuntu@$PILOT_HOST "docker ps --filter name=collection-admin --format '{{.Status}}'"` 时间连续 |

### 第二步（开放管理面）

| # | 检查项 | 预期 |
|---|---|---|
| 1 | 白名单外 403 | 用白名单外网络访问 `/` 与 `/api/`，均 `403` |
| 2 | 白名单内可登录 | 浏览器登录，输 admin 账号密码，进入后台（角色为后端配置的 `SYSTEM_ADMIN`） |
| 3 | 登录态保持 | 登录后刷新、切换菜单，session 不丢失 |
| 4 | 各模块 API | 案件检索、配置、看板、异常队列等页面数据正常返回 |
| 5 | 回调仍通 | 重复第一步第 4 项 |

---

## 8. 回滚方案

| 场景 | 回滚动作 |
|---|---|
| 前端页面异常 | 回退静态文件：重新上传上一版 `dist/*` 到 `/opt/app/admin-ui/`，`nginx -s reload` |
| nginx 配置错误 | `sudo cp /etc/nginx/conf.d/collection-admin.mocasa.com.conf.bak.YYYYMMDDHHMM` 覆盖回旧配置 → `nginx -t && nginx -s reload` |
| 管理面需紧急关闭 | 注释 `/api` 段与 `location /` 的白名单（或恢复 `location / { return 403; }`）→ reload |

> 全程不涉及容器与 jar，回滚即时、零副作用。

---

## 9. 风险与注意事项

| # | 事项 | 说明 |
|---|---|---|
| 1 | **绝不改 `/webhook/`** | 回调是生产链路的活入口，改动即事故；本方案对它的配置零修改 |
| 2 | 认证必须先补密码框 | §4.1 未完成前，开放 `/api` 等于把后台敞开给白名单内所有网络，不可上线 |
| 3 | 白名单 IP 需固定 | 办公出口 IP 若为动态，需配合 VPN 或定期更新白名单；家庭/移动访问需临时加 IP |
| 4 | 本地与 pilot 共库 | 本地开发连的是 `ai_collection_db`，避免在本地后台做共享配置的写操作 |
| 5 | `mock` 端点不放通 | `/mock/*`（MockTriggerController、AdminMockDataController）可驱动真实触达，生产 `/api` 代理**不覆盖 `/mock`**（本方案 `/api` 代理未放通 `/mock`，天然隔离） |
| 6 | 前端更新走独立流程 | 前端每次更新 = `build → scp → reload`，不触发引擎发版门禁，与后端解耦 |

---

## 附录 A：管理面 API 端点清单

以下为后端实际暴露的管理面端点（供 `/api` 前缀改造与白名单核对），生产经 `/api` 统一前缀访问。

| 前缀 | 端点 | 说明 |
|---|---|---|
| `/auth` | `/login`、`/logout` | 登录 / 登出 |
| `/admin` | `/me`、`/audit-logs` | 当前用户、审计日志 |
| `/cases` | `/search` | 案件检索 |
| `/ops/exceptions` | `GET`、`/{id}/ack`、`/{id}/resolve` | 异常队列 |
| `/ops/evidence` | `/event/{id}`、`/case/{id}`、`/plan/{id}`、`/redis` | 事件证据 |
| `/ops/dlq` | `/redrive` | DLQ 重放 |
| `/compliance` | `/freeze`、`/unfreeze`、`/escalate` | 合规操作 |
| `/config` | `/evaluation-settings`、`/versions`、`/rollback`、`/script-templates`、`/plan-templates` 等 | 配置管理与回滚 |
| `/catalog` | `/overview`、`/template/{slot}`、`/template/{slot}/preview` | 策略目录 |
| `/plans` | `/overview/by-case/{caseId}`、`/by-case/{caseId}/history`、`/{planId}/steps`、`/timeline/{userId}` 等 | 计划与时间线 |
| `/dashboard` | `/outreach/realtime`、`/portfolio` | 看板数据 |

**不放通的端点**：`/mock/*`（测试触发，可驱动真实触达）、`/webhook/*`（回调，独立路径）。

---

> **修订历史**
> - v1.0（2026-09-01）：初版。确立「前端独立部署 + `/api` 统一前缀 + 同源 + 分两步上线」方案。
