# MOCASA 催收系统升级 Phase 1 — 管理后台前端上线方案

> **版本**: v1.4（2026-09-09）
> **状态**: 方案 B 已拍板：三个独立测试账号、首次上线统一 `SYSTEM_ADMIN`，RBAC 后置。前端 `/api`、登录拦截、多账号和仓库 Nginx 目标配置已开发，**现网 nginx 尚未改、管理后台尚未对外开放**。
> **适用对象**: 管理后台（`collection-admin`）前端 UI 上线  
> **部署机**: Pilot 机 `bdp01`；域名 `https://collection-admin.mocasa.com`  
> **使用者**: 团队内部少部分人做管理（看板 / 案件 / 模板 / 异常 / 合规），日常用浏览器打开域名，不靠每人配 SSH  
> **关联文档**: [发版手册](./MOCASA催收系统升级_Phase1_发版手册.md)（后端 jar）、[管理后台设计文档](../MOCASA催收系统升级_Phase1_管理后台设计文档.md)、[管理后台操作手册](../MOCASA催收系统升级_Phase1_管理后台操作手册.md)（本机开发）  
> **登录账号**: 见 `docs/ops/生产访问凭据.local.md`（gitignore，不入库）

## 目录

- [1. 摘要与结论](#1-摘要与结论)
- [2. 现状与目标](#2-现状与目标)
- [3. 方案决策](#3-方案决策)
- [4. 上线前置条件（必须先改代码）](#4-上线前置条件必须先改代码)
- [5. 上线操作步骤](#5-上线操作步骤)
- [6. 目标态 nginx 完整配置](#6-目标态-nginx-完整配置)
- [7. 验证清单](#7-验证清单)
- [8. 回滚方案](#8-回滚方案)
- [9. 风险与注意事项](#9-风险与注意事项)
- [10. 已知问题与未闭合项（2026-09-08）](#10-已知问题与未闭合项2026-09-08)
- [附录 A：管理面 API 端点清单](#附录-a管理面-api-端点清单)
- [附录 B：出口 IP 怎么复查](#附录-b出口-ip-怎么复查)

---

## 1. 摘要与结论

**方案：公网 HTTPS 域名 + 固定出口白名单 + 登录；前端独立静态部署；管理面 API 走 `/api`；开 `/api` 之前先发一版补登录拦截的 jar；静态文件先上传、nginx 一次切完整配置。`/webhook/` 行为保持现状。**

| # | 定稿 |
|---|---|
| 1 | 前端**永不进 jar**。产物 `dist/` 由 nginx 托管；以后改 UI 只传静态文件，不重启催收容器 |
| 2 | 前端与 API **同源**（`collection-admin.mocasa.com`），保住 `JSESSIONID` |
| 3 | 管理面统一 **`/api` 前缀**，nginx 一条规则代理，页面路由走 SPA |
| 4 | **访问模型**：域名继续公网解析；`/` 与 `/api` 仅放行 §1 四条出口 IP；其余 403。公网只保留 `/webhook/` 给供应商。SSH 隧道只作应急，不当日常入口 |
| 5 | **这一次允许重启容器**：先发 jar，把 `/plans/**`、`/catalog/**` 补进登录拦截，再开 `/api`。之后前端迭代仍不重启 |
| 6 | **节奏**：代码就绪 → 发 jar → 上传到新 release 目录（此时 nginx 仍 403）→ 原子切换 `current` 软链 → **一次** reload 完整 conf。不要直接覆盖在线静态目录 |
| 7 | **绝不改 `/webhook/` 的转发语义**（80/443 都可回调、应用侧验签）。管理面强制 HTTPS |
| 8 | **首批账号**：每位使用者独立账号，暂时统一 `SYSTEM_ADMIN`；禁止共用 `admin`。三角色 RBAC 在上线稳定后立即建设，不阻断首次开放 |
| 9 | **三角色目标态**：`VIEWER` / `OPERATOR` / `SYSTEM_ADMIN`；DLQ 重放、配置版本回滚、故障注入、紧急停催仅 `SYSTEM_ADMIN` |

**白名单（2026-09-08 已核实）**

| 场景 | 出口 IP | nginx |
|---|---|---|
| 办公室（不连 VPN） | `213.155.143.66` | `allow` |
| 公司 VPN 已连接 | `120.76.219.216` | `allow` |
| 补充出口 | `218.94.54.138` | `allow` |
| 补充出口 | `112.2.0.198` | `allow` |
| 手机 4G / 未在上表的外网 | 其他 | `deny` → **403（预期）** |

`location /` 与 `/api/` 必须用同一份 ACL（见 §6 `include`）。

**仓库里的 `deploy/nginx/collection-admin.mocasa.com.conf` 暂保持现网「仅 webhook、其余 403」。** 现在就改成目标态，有人按仓库同步到机器会在代码未就绪时打开管理面。上线当天覆盖机上 conf 后，再把目标态回写仓库。

---

## 2. 现状与目标

### 2.1 现状（2026-09-09 已核对代码与仓库 conf）

| 项 | 现状 |
|---|---|
| 后端 API | `bdp01` 容器 `collection-admin`（镜像 `intelligent-collection-admin:pilot`），绑 `127.0.0.1:8080` |
| 域名与证书 | `collection-admin.mocasa.com` 已就绪；证书 `/etc/nginx/ssl/collection-admin.mocasa.com.{pem,key}` |
| 公网 nginx | 仅 `/webhook/` 放通，其余 `return 403`。ACME 依赖 server `root /opt/tmp` |
| 前端 UI | **未部署**。Dockerfile 只 COPY jar |
| 后端认证 | `AdminAuthenticator`（BCrypt）；支持 indexed 环境变量配置多个账号，旧 `COLLECTION_ADMIN_USER` / `COLLECTION_ADMIN_PASSWORD_HASH` 仅兼容 |
| 前端登录页 | **已有密码框**，`api.login(username, password)` 已对齐后端。旧文档 §4.1 已过时 |
| 前端 API 前缀 | ✅ 已开发并完成 production build：`.env.development` / `.env.production` 均为 `/api`，dev / preview 代理统一去前缀 |
| 登录拦截 | ✅ 已补 `/plans/**`、`/catalog/**`；配置、匿名 401 与三账号认证共 13 条定向测试通过；全 reactor 测试通过 |
| 三个独立账号 | 🟡 已生成用户名、明文和 BCrypt 哈希；本地凭据文件已 gitignore，尚未写入 Pilot `pilot.env` |
| 本机开发入口 | `http://127.0.0.1:5173`（操作手册）；与 Pilot 域名是两套入口 |

### 2.2 目标（「上线成功」的定义）

同时成立才算成功：

1. 办公室不连 VPN，浏览器打开 `https://collection-admin.mocasa.com` 能登录，看板 / 案件 / 模板 / 异常 / 合规可用。
2. 连公司 VPN 后同样能登录（在家 / 出差）。
3. 未连 VPN 的外网（如手机热点）访问 `/` 与 `/api` 均为 **403**。
4. `/webhook/` 与 reload 前行为一致（应用仍收到回调并验签）。
5. 白名单网段内，未登录不能读案件时间线 / 计划 / 话术。
6. 之后改前端不重启催收容器；不能三天两头因 IP 漂移进不去（靠已登记的多条固定出口）。

---

## 3. 方案决策

### 3.1 为什么挂公网域名，而不是继续纯隧道

域名已经在公网。要讨论的不是「要不要开 443」，而是「要不要在此域名上打开 `/` 和 `/api`」。

| | 公网 HTTPS + 白名单 + 登录（本方案） | 管理面继续对公网 403，人走 SSH 隧道 |
|---|---|---|
| 体验 | 浏览器打开域名即可，符合「内部少数人做管理」 | 每人要 SSH 权限和隧道，运营/策略用不了 |
| 暴露面 | 扫描器打到 403；办公室/VPN 内能碰到登录页 | 管理面路径不出现在公网 |
| 进不去 | 出口 IP 变了会 403；用 VPN 作备份出口 | 隧道断了即不可用 |
| 配错代价 | 漏 `deny all` 等于管理面对全网打开 | 几乎不会「配错白名单」 |

选定前者：使用者要打开域名干活；已有固定办公室 IP 和会换出口的公司 VPN。隧道保留为应急（nginx 回滚成全 403、或 IP 全变时）。

白名单**不是登录**。它只认「从哪栋网出来」。办公室 NAT 上的访客 Wi-Fi 也能碰到登录页，必须叠强口令 + 拦截器罩住全部管理接口。

### 3.2 前端独立部署，不进 jar

| 维度 | 前端进 jar | 前端独立部署（本方案） |
|---|---|---|
| 前端小改 | 全量发版门禁 + 重启引擎 | `npm run build` → 传文件（一般不必 reload） |
| 催收链路 | 每次重启有回调窗口 | 日常零影响 |
| 耦合 | UI 绑架引擎发版 | 分开演进 |

「独立部署」≠「永远不发 jar」。见下一节。

### 3.3 这一次必须发 jar：补登录拦截

打开 `/api` 后，nginx 会把白名单内的 `/api/plans/**`、`/api/catalog/**` 转到容器。拦截器现在不管这两条，**未登录也能读时间线、计划、话术全文**（案件搜索等路径已有拦截）。

| 选择 | 后果 |
|---|---|
| 坚持容器零改动就开 `/api` | 办公室/VPN 网段内不登录可读 PII，不满足数据安全 |
| 先发 jar 再开 `/api`（本方案） | 一次发版窗口；回调可能短暂 502。当前 15:00 后基本静默，但供应商重试策略尚未确认，仍保留前后探测 |
| 先不开发管理面 | 前端传上去也登不进去，达不到目标 |

Cookie 的 `Secure` / `SameSite` 用 nginx `proxy_cookie_flags` 加，不必为这件事改 Spring。

### 3.4 同源 + `/api` 前缀

`api.ts` 使用 `credentials: "include"`。跨域会丢会话。

页面 `/dashboard` 与接口 `/dashboard/today` 等已在 Vite 代理里打架。统一 `/api` 后 nginx 只需一条代理，不必每加一个端点改 conf。

### 3.5 一次切 nginx，而不是两次 reload

| 节奏 | 实际改了什么 | 验收到什么 |
|---|---|---|
| 只 scp 文件，nginx 仍 403 | 磁盘上有 `dist/`，公网无变化 | **这一步才接近零风险** |
| 两次 reload（旧 v1.0） | 先把 `return 403` 改成出页面，再开 `/api` | 中间态「能看不能登」；第一步才是改入口规则；旧校验表会把 `/api` 的 SPA 200 误当成 403 |
| **一次 reload（本方案）** | 文件已就位后，一次装上白名单 + SPA + `/api` | 当场按最终成功标准验收；reload 次数少 |

危险动作是改 `location /`，不是「把文件拷上去」。webhook 段即使不改，`nginx -s reload` 也会热加载整份 conf，所以 reload 后必须立刻对照回调。

### 3.6 同源部署下 session 怎么保住

nginx 反代默认转发 `Cookie`。再加：

- `proxy_cookie_flags JSESSIONID secure samesite=lax httponly;`（需 nginx ≥ 1.19.3，上线前 `nginx -v` 确认）
- 管理面只走 HTTPS（80 上非 webhook / 非 ACME 的请求 301 到 HTTPS），避免明文带出会话

---

## 4. 上线前置条件（必须先改代码）

以下在**发 Pilot 之前**于仓库完成并本地验证。本文只规定要改什么，不在本轮改代码。

### 4.1 前端：`VITE_API_BASE=/api`（P0，未做则生产登录必挂）

现状：`api.ts` 已是 `login(username, password)`；缺生产前缀。不改就 `npm run build` 上线，请求打 `/auth/login`，被 SPA `try_files` 吃成 HTML。

1. 新增 `collection-admin/ui/.env.production`：

   ```
   VITE_API_BASE=/api
   ```

2. 本地开发 `collection-admin/ui/.env.development`（或现有 `.env`）同步：

   ```
   VITE_API_BASE=/api
   ```

3. `collection-admin/ui/vite.config.ts` 把散代理收成一条（本机后端默认 8888）：

   ```ts
   proxy: {
     "/api": {
       target: "http://localhost:8888",
       changeOrigin: true,
       rewrite: (p) => p.replace(/^\/api/, "")
     }
   }
   ```

   不要把 `/mock` 单独代理进生产构建；开发若仍要 mock，只放在这条 `/api` 下本地用，生产 nginx 显式拒绝 `/api/mock`。

4. `api.ts` 保持 `API_BASE = import.meta.env.VITE_API_BASE || ""`，路径仍写 `/auth/login` 这种后端原路径。

### 4.2 后端：拦截器补 `/plans/**`、`/catalog/**`（P0，未做则开 `/api` 会漏 PII）

`AdminWebConfig.addPathPatterns` 增加：

- `/plans/**`
- `/catalog/**`

`/auth/**` 继续排除。补单测：未登录访问 `/plans/timeline/{id}`、`/catalog/overview` 返回 401。

长期应把规则收敛为“管理 API 默认要求登录，只显式放行 `/auth/login` 与独立 `/webhook` 入口”，避免以后新增 Controller 时忘记登记。首次上线至少先完成上述漏口修复和全部管理 Controller 清单复核。

### 4.3 独立账号（P0，首批统一权限）

独立账号先解决“你是谁”和“审计能否追到个人”，不等同于完整 RBAC。禁止多人共享 `admin`：共享账号会导致操作日志无法追责、离职时所有人被迫一起换密码、泄露后无法单独禁用。

首批通过 `/opt/app/pilot.env` 配置多个账号，每人一个用户名和 BCrypt 哈希；明文口令不入仓、不进 Nacos：

```bash
COLLECTION_ADMIN_AUTH_ACCOUNTS_0_USERNAME=user_a
COLLECTION_ADMIN_AUTH_ACCOUNTS_0_PASSWORD_HASH='$2a$10$...'
COLLECTION_ADMIN_AUTH_ACCOUNTS_0_ROLE=SYSTEM_ADMIN

COLLECTION_ADMIN_AUTH_ACCOUNTS_1_USERNAME=user_b
COLLECTION_ADMIN_AUTH_ACCOUNTS_1_PASSWORD_HASH='$2a$10$...'
COLLECTION_ADMIN_AUTH_ACCOUNTS_1_ROLE=SYSTEM_ADMIN
```

BCrypt 哈希必须用单引号包裹。增删账号需改 `pilot.env` 并重启容器；当前使用者少，可接受。首批不开发账号管理页面。

本轮已生成 `mocasa-admin-01` / `02` / `03` 三个测试账号，均为 `SYSTEM_ADMIN`。明文和可复制的 `pilot.env` 片段只保存在被 gitignore 的 `docs/ops/管理后台测试账号_20260909.local.md`，不得写入普通文档或提交 Git；测试阶段结束后全部轮换。

上线稳定后再实现后端真正授权（前端隐藏菜单不算权限）：

| 角色 | 权限 |
|---|---|
| `VIEWER` | 看板、案件、计划、目录、异常与审计日志只读 |
| `OPERATOR` | 包含 VIEWER；可改话术/计划模板、处理普通异常、冻结/解冻/升级、改普通评估参数 |
| `SYSTEM_ADMIN` | 全部权限；独占 DLQ 重放、配置版本回滚、故障注入、全局/分渠道停催与恢复 |

高危操作目标态统一要求：二次确认、必填原因、写操作人审计；Phase 1 不做双人审批流。

### 4.4 原子静态发布（P0，避免半新半旧白屏）

禁止向 nginx 正在读取的目录直接 `rsync --delete`。使用版本目录：

```text
/opt/app/admin-ui/
  releases/20260909-153000/
  releases/20260910-110000/
  current -> releases/20260910-110000/
```

先完整上传并校验新 release，再用一条 `ln -sfn` 原子切换 `current`。nginx `root` 永远指向 `/opt/app/admin-ui/current`。回滚只需把软链指回上一版。

### 4.5 本地验证

```powershell
cd D:\AI\Intelligent-Collection-V1\collection-admin\ui
npm run build
Test-Path dist\index.html    # 应为 True
npx vite preview --host 127.0.0.1 --port 4173
```

用 preview（走 production `VITE_API_BASE`）登录本机后端，确认各模块请求路径带 `/api` 且数据正常。不要只跑 `npm run dev` 就当生产验证。

后端拦截器用本机 8888 直接 `curl` 未登录的 `/plans/...` 应 401（改代码后）。

---

## 5. 上线操作步骤

已批准发布窗口：**15:00–00:00（UTC+8 / 菲律宾时间），可接受 30 分钟维护**。虽然 15:00 后系统基本静默，仍推荐优先选 22:30–23:00（触达窗 21:00 后），并保留 Webhook 前后探测。顺序固定，不要对调。

### 5.0 发版前在本机确认出口（当天）

```powershell
# 不连 VPN
Invoke-RestMethod -Uri "https://ifconfig.me/ip"
# 连上公司 VPN 再查一次
Invoke-RestMethod -Uri "https://ifconfig.me/ip"
```

本机查到的地址应落在 §1 四条 `allow` 之一（`213.155.143.66` / `120.76.219.216` / `218.94.54.138` / `112.2.0.198`）。不在名单里则先改 §6 ACL，再 reload。

### 5.1 发补拦截器的 jar（会重启容器）

按 [发版手册](./MOCASA催收系统升级_Phase1_发版手册.md) 编包、scp、`docker build`、`pilot-run.sh`。

重启后：

```bash
curl -s -o /dev/null -w "health:%{http_code}\n" http://127.0.0.1:8080/actuator/health
# 期望 200。公网 https://.../actuator/health 现网仍应 403，不要拿公网 health 当容器就绪标准
```

对照一条 webhook：应用侧仍应能收到并验签（见 §7）。容器 `Up` 时间会从 0 重新计，这是预期。

### 5.2 本机构建并上传新 release（nginx 仍 403）

**Windows（本机）：**

```powershell
cd D:\AI\Intelligent-Collection-V1\collection-admin\ui
npm run build
Test-Path dist\index.html
```

不要用 PowerShell 的 `scp dist/*`（glob 易踩坑）。生成本次 release ID，整目录上传：

```powershell
$release = Get-Date -Format "yyyyMMdd-HHmmss"
ssh ubuntu@$env:PILOT_HOST "mkdir -p /tmp/admin-ui-$release /opt/app/admin-ui/releases"
scp -r dist ubuntu@${env:PILOT_HOST}:/tmp/admin-ui-$release/
Write-Host "RELEASE_ID=$release"
```

记住输出的 `RELEASE_ID`。**服务器：**

```bash
# 替换为上一步输出
RELEASE_ID=20260909-153000
sudo mkdir -p "/opt/app/admin-ui/releases/$RELEASE_ID"
sudo rsync -a "/tmp/admin-ui-$RELEASE_ID/dist/" \
  "/opt/app/admin-ui/releases/$RELEASE_ID/"

# 完整性检查：未通过不要切 current
test -s "/opt/app/admin-ui/releases/$RELEASE_ID/index.html"
find "/opt/app/admin-ui/releases/$RELEASE_ID/assets" -type f | grep -q .

# 首次上线此时只创建软链；nginx 仍是现网 403 配置
sudo ln -sfn "/opt/app/admin-ui/releases/$RELEASE_ID" /opt/app/admin-ui/current
readlink -f /opt/app/admin-ui/current
ls -lh /opt/app/admin-ui/current/index.html
```

此时 **不要** `nginx -s reload`。公网仍应全部 `/` → 403（webhook 除外）。可用手机热点抽查 `/` 仍 403。

以后发布新前端：上传新 release → 完整性检查 → 原子切 `current`；一般不必 reload。至少保留最近两版，确认稳定后再删更旧 release。

### 5.3 一次装上完整 nginx（唯一一次为管理面 reload）

```bash
ssh ubuntu@$PILOT_HOST
sudo cp /etc/nginx/conf.d/collection-admin.mocasa.com.conf{,.bak.$(date +%Y%m%d%H%M)}

# 写入 §6 的 snippet + 两个 server
sudo nginx -t && sudo nginx -s reload
```

reload 后立刻做 §7。失败则按 §8 把 bak 拷回去再 `nginx -t && nginx -s reload`。

上线成功后，把机上 conf **回写**仓库 `deploy/nginx/`（含 snippet），避免下次发版把 SPA 打回 403。

---

## 6. 目标态 nginx 完整配置

相对现网只增加管理面；**webhook 转发语义与现网一致**（`proxy_pass` / 头 / `client_max_body_size 1m` / 80 与 443 都可进）。为避免改 `root` 弄坏证书续期，HTTP 的 ACME 显式 `root /opt/tmp`。管理面只在 443。

先放 ACL，两处 `include`，改 IP 只改这一处。

**`/etc/nginx/snippets/collection-admin-acl.conf`**

```nginx
# 管理面 ACL（2026-09-08）：办公室 / VPN / 补充出口
allow 213.155.143.66;
allow 120.76.219.216;
allow 218.94.54.138;
allow 112.2.0.198;
deny all;
```

**`/etc/nginx/conf.d/collection-admin.mocasa.com.conf`**

```nginx
upstream ups_collection {
    server 127.0.0.1:8080 max_fails=3 fail_timeout=10s;
}

# ── HTTP：ACME + 回调保持可进；管理面跳到 HTTPS ──────────
server {
    listen      80;
    server_name collection-admin.mocasa.com;
    server_tokens off;

    access_log /var/log/nginx/collection-admin.mocasa.com_access.log main;
    error_log  /var/log/nginx/collection-admin.mocasa.com_error.log;

    location ^~ /.well-known/acme-challenge/ {
        root /opt/tmp;
        default_type "text/plain";
    }

    location ^~ /webhook/ {
        client_max_body_size 1m;
        proxy_pass http://ups_collection;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

# ── HTTPS：回调 + 白名单管理面 ──────────
server {
    listen      443 ssl;
    server_name collection-admin.mocasa.com;
    root /opt/app/admin-ui/current;

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
        root /opt/tmp;
        default_type "text/plain";
    }

    # 第三方回调：唯一不对管理面做 IP 白名单的业务入口（应用侧验签）
    location ^~ /webhook/ {
        client_max_body_size 1m;
        proxy_pass http://ups_collection;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # mock 可驱动真实触达；即使有人打进 pilot 包也拒绝（长前缀优先于 /api/）
    location ^~ /api/mock {
        deny all;
    }

    location ^~ /api/ {
        include snippets/collection-admin-acl.conf;
        proxy_pass http://ups_collection/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cookie_flags JSESSIONID secure samesite=lax httponly;
    }

    location = /index.html {
        include snippets/collection-admin-acl.conf;
        add_header Cache-Control "no-store";
    }

    location / {
        include snippets/collection-admin-acl.conf;
        try_files $uri $uri/ /index.html;
    }
}
```

说明：

- `location ^~ /api/` + `proxy_pass http://ups_collection/;`（upstream 带尾斜杠）负责去掉 `/api` 前缀，不要再用 `rewrite ... break`。
- `^~ /api/mock` 比 `^~ /api/` 更长，扫描 `/api/mock/send-sms` 会 403，不会进容器。
- 静态资源（带 hash 的 JS/CSS）可走默认缓存；`index.html` 禁止缓存，避免发新前端后白屏。
- `location ^~ /api/` 优先于 `location /`，页面与 API 无歧义。

---

## 7. 验证清单

在 **reload 前**先记下一条 webhook 探测的 HTTP 状态（见下），reload 后对照。不要用 `curl -I`（HEAD）打登录或 webhook，后端多半不按 HEAD 处理。

**A. 回调（办公室或服务器本机均可；期望与 reload 前同类：到达应用后的 4xx 验签失败，而不是 nginx 403/502）**

```bash
curl -sS -o /dev/null -w "webhook:%{http_code}\n" \
  -X POST "https://collection-admin.mocasa.com/webhook/facade-callback" \
  -H "Content-Type: application/json" \
  -d '{}'
```

空 JSON 应被应用拒绝（常见 401），**证明路径仍进容器**。若变成 403/502/无响应，立即回滚 nginx。

**B. 白名单内（办公室不连 VPN，或连 VPN）**

| # | 检查项 | 预期 |
|---|---|---|
| 1 | 浏览器打开 `https://collection-admin.mocasa.com/` | 登录页，不是 403 |
| 2 | `http://collection-admin.mocasa.com/` | 301 到 HTTPS |
| 3 | 直接打开 `/cases` | 200 的 HTML（SPA），不是 404 |
| 4 | 未登录 `GET https://.../api/plans/timeline/1` | **401** JSON（拦截器生效）。若 200 则立刻关 `/api` |
| 5 | 未登录 `GET /api/catalog/overview` | **401** |
| 6 | 登录（账号见凭据文件） | 进入后台，角色为后端配置的 `SYSTEM_ADMIN` |
| 7 | 刷新、切菜单 | session 不丢 |
| 8 | Data Analysis / Strategy / Templates / Case Monitor / Ops / Compliance / System | 页面有数据或空态，不是 HTML 登录页、不是 401 刷屏 |
| 9 | `GET /api/mock/send-sms` | **403**（nginx deny） |

**C. 白名单外（手机热点，不连 VPN）**

| # | 检查项 | 预期 |
|---|---|---|
| 1 | `https://collection-admin.mocasa.com/` | **403** |
| 2 | `https://collection-admin.mocasa.com/api/auth/login` | **403** |
| 3 | webhook POST（同上） | 与 A 相同，**不是** 403 |

**D. 容器**

发 jar 后 `docker ps` 时间会重置。此后 **只 reload nginx、只换静态文件时**，容器 Up 时间应连续。

---

## 8. 回滚方案

| 场景 | 动作 | 是否动容器 |
|---|---|---|
| 前端页面坏了 | `ln -sfn` 把 `/opt/app/admin-ui/current` 指回上一版 release。只换软链不必 reload | 否 |
| nginx 配错 / 回调异常 | `sudo cp` 对应 `.bak.YYYYMMDDHHMM` 覆盖 conf → `nginx -t && nginx -s reload` | 否 |
| 管理面需紧急关闭 | 恢复现网 `location / { return 403; }` 那份 bak（只留 webhook） | 否 |
| 新 jar 启动失败 | 按发版手册回滚 jar 镜像 | 是 |
| 日常进不去（403） | 先连公司 VPN 再试；仍 403 则查 `access.log` 里 `$remote_addr` 是否已不在 ACL，再改 snippet 后 reload | 否 |

应急看 API（管理面已关时）：

```bash
ssh -L 8080:127.0.0.1:8080 ubuntu@$PILOT_HOST
# 浏览器或 curl 走本机 8080；这不是给运营的日常入口
```

---

## 9. 风险与注意事项

| #   | 事项                             | 说明                                                                                                             |
| --- | ------------------------------ | -------------------------------------------------------------------------------------------------------------- |
| 1   | **绝不改 webhook 转发语义**           | 回调是活入口。本方案只把 HTTP 拆成独立 server，webhook location 内容与现网一致                                                         |
| 2   | 先发拦截器再开 `/api`                 | 否则白名单网段内未登录可读 `/plans`、`/catalog`                                                                              |
| 3 | 白名单写固定出口，不要当天 `ifconfig.me` 替班 | 已写入四条出口。4G / 未登记地址进不去是成功。IP 变了改 snippet 即可，不必重启容器 |
| 4   | 办公室网是共享位置                      | 访客 Wi-Fi 能碰到登录页；靠口令 + 拦截器。不要把口令写进本文                                                                            |
| 5   | mock / 故障注入                    | nginx 拒绝 `/api/mock`；`/ops/fault-injection` 仍需登录。Pilot 包里 mock 仅 `local/test` profile，不把「进程没装 mock」当成 nginx 隔离 |
| 6   | 不要给本域名套 CDN                    | `$remote_addr` 会变成 CDN，白名单要么全 403，要么变成「凡走 CDN 都算白名单」                                                           |
| 7   | 本机与 Pilot 共测试库                 | 本地后台不要改共享配置当真                                                                                                  |
| 8   | 发新前端后白屏                        | 查 `current` 是否指向完整 release、`index.html` 是否 `no-store`；不要直接覆盖在线目录                                     |
| 9   | IPv6                           | 若办公室走 IPv6，IPv4 白名单会误伤。403 时看 access.log 的地址，再 `allow` 对应 IPv6 或让该域名只出 A 记录                                    |
| 10  | `proxy_cookie_flags`           | nginx 过旧会 `nginx -t` 失败。先确认版本；过旧则暂时不加该指令，但仍须管理面只走 HTTPS                                                        |
| 11  | 现网 conf 先不改仓库                  | 见 §1。上线成功后再回写 `deploy/nginx/`                                                                                  |

---

## 10. 上线门槛与后续加固（2026-09-09 定稿）

### 10.1 首次开放的阻断项（P0）

下表全部完成前，不能打开公网 `/` 与 `/api`：

| 项 | 当前状态 | 验收 |
|---|---|---|
| 前端生产 `/api` 前缀 | ✅ 本地完成 | production build 通过；Pilot 上线后再验真实登录与全部模块 |
| `/plans/**`、`/catalog/**` 登录拦截 | ✅ 本地完成 | 13 条认证定向测试 + 全 reactor 测试通过 |
| 每人独立账号 | 🟡 已生成待落 Pilot | 三个独立测试账号均为 SYSTEM_ADMIN；明文仅在 gitignore 本地文件 |
| 原子静态发布 | ⬜ 未落位 | `releases/<id>` 完整，`current` 可前后切换且无需覆盖在线目录 |
| nginx 目标配置 | 🟡 已入仓待部署 | 四条 ACL、HTTPS、mock deny、ACME 已按 §6 落代码；本机无 Nginx/Docker，待 Pilot `nginx -t` |
| Webhook 保护 | ⬜ 待窗口验证 | jar 重启、nginx reload 前后 POST 对照，不出现 403/502/超时 |
| 三层回滚 | ⬜ 待演练 | jar / nginx / 静态软链分别能独立回退 |

### 10.2 首次上线采用的账号与权限

- 每位实际使用者一个账号，账号通过 `pilot.env` 管理；首批统一 `SYSTEM_ADMIN`。
- 这是为个人审计、单人停用和口令轮换，不代表 RBAC 已实现。
- 当前代码只把 `role` 写入 session，尚未按角色拒绝接口；因此不要在文档中声称已有 RBAC。
- 高危操作在 RBAC 落地前，只交给明确指定的少数管理员使用，并按团队流程二次确认、填写原因。

### 10.3 上线稳定后立即建设（不阻断首次开放）

| 优先级 | 能力 | 约定 |
|---|---|---|
| P1 | 三角色 RBAC | `VIEWER` / `OPERATOR` / `SYSTEM_ADMIN`；后端鉴权为准，前端菜单仅同步体验 |
| P1 | 高危操作保护 | DLQ 重放、配置版本回滚、故障注入、紧急停催仅 `SYSTEM_ADMIN`；二次确认 + 必填原因 + 审计 |
| P1 | 紧急停催 | 第一版先有可执行且演练过的 runbook；后台全局/分渠道按钮后做 |
| P1 | 主链路对账 | 应入案 → 实际入案 → 应触达 → 实际触达/回调 |
| P1 | 配置安全 | 变更 diff、静态校验、乐观锁、版本与回滚；不做双人审批流 |
| P1 | 告警处理闭环 | 告警认领、备注、解决、关闭；复用催收系统已有告警，不新建管理后台专属钉钉机器人 |
| P2 | 渠道运维台 | 渠道状态、失败率、暂停/恢复和备用渠道 |
| P2 | 案件 360 增强 | 还款、录音、转写和完整事件时间轴 |

### 10.4 本轮监控边界

催收系统已经有钉钉告警，**本轮不新增管理后台专属钉钉告警，也不因此阻断前端上线**。首次 Pilot 按人工可恢复 30–60 分钟设计，不建设双机高可用。

上线操作仍必须人工检查：

- 容器运行与本机 `/actuator/health`
- Webhook POST 状态与 nginx 5xx
- TLS 证书有效期
- nginx access/error log

独立的管理后台技术监控（容器重启、API 延迟、连接池、慢 SQL、登录失败）列入上线后加固；未来若接入统一监控平台，优先复用现有基础设施，不再建设一套孤立告警系统。

### 10.5 操作与仓库边界

- PowerShell 与 bash 命令必须在文档标明的终端执行。
- `proxy_cookie_flags` 需要 nginx ≥ 1.19.3；上线当天先 `nginx -v`。
- reload 会热加载整份 conf，即使没有修改 webhook location；对照失败优先回滚 nginx。
- 白名单不是登录。同一出口上的访客 Wi‑Fi 仍能碰到登录页，安全依赖 ACL + 独立账号 + 登录拦截。
- 仓库 `deploy/nginx/` 目前故意保持“仅 webhook”。上线成功后必须把实机目标态（含 ACL snippet）回写仓库。
- 健康检查只打机上 `127.0.0.1:8080`；公网 `/actuator` 不作为就绪入口。

---

## 附录 A：管理面 API 端点清单

生产经 `/api` 前缀访问。后端路径不变。

| 前缀 | 端点 | 说明 |
|---|---|---|
| `/auth` | `/login`、`/logout` | 登录 / 登出（不拦截） |
| `/admin` | `/me`、`/audit-logs` | 当前用户、审计日志 |
| `/cases` | `/search`、`/{caseId}` | 案件检索 / 单案 |
| `/ops/exceptions` | `GET`、`/{id}/ack`、`/{id}/resolve` | 异常队列 |
| `/ops/evidence` | `/event/{id}` 等 | 事件证据 |
| `/ops/dlq` | `/redrive` | DLQ 重放（可驱动真实触达，必须登录） |
| `/ops/fault-injection` | 开关 | 仅取证期，必须登录 |
| `/compliance` | `/freeze`、`/unfreeze`、`/escalate` | 合规 |
| `/config` | evaluation / versions / rollback / script-templates / plan-templates 等 | 配置热更新 |
| `/catalog` | `/overview`、`/template/{slot}`、`/preview` | 策略目录（**须补拦截**） |
| `/plans` | overview / history / steps / timeline 等 | 计划与时间线（**须补拦截**） |
| `/dashboard` | `/today`、`/outreach/realtime`、`/portfolio`、`/aging`、`/matrix`、`/daily-by-channel`、`/aicall/*`、`/risk` | 看板 |

**不放通**：`/mock/*`（nginx deny + 非 pilot profile）、`/actuator/*`（不走 `/api`，落 `location /` 白名单外 403；白名单内会落到 SPA，不应当成 health 入口）。健康检查只打 `127.0.0.1:8080`。

---

## 附录 B：出口 IP 怎么复查

查的是 NAT 后的公网地址，不是 `192.168.x.x`。

```powershell
Invoke-RestMethod -Uri "https://ifconfig.me/ip"
Invoke-RestMethod -Uri "https://api.ipify.org"
```

浏览器可打开 https://ifconfig.me 。两个结果应相同。

| 场景 | 怎么查 | 定稿值（2026-09-08） |
|---|---|---|
| 办公室、不连 VPN | 公司 Wi-Fi/有线 | `213.155.143.66` |
| 连上公司 VPN | 再查一次；应变 | `120.76.219.216` |
| 补充出口 | 同事 / 其他出口核实 | `218.94.54.138` |
| 补充出口 | 同事 / 其他出口核实 | `112.2.0.198` |

以后有人 403：先让对方查 `ifconfig.me`，把新地址补进 snippet 后 `nginx -t && nginx -s reload`，不必重启容器。

---

> **修订历史**  
> - v1.4（2026-09-09）：方案 B 落地：三个独立测试账号均为 SYSTEM_ADMIN，RBAC 后置；完成前端 `/api`、dev/preview 代理、`/plans`/`/catalog` 登录拦截、多账号 Pilot 配置与部署脚本兼容；批准 15:00–00:00、30 分钟维护窗口，仍保留 Webhook 前后探测。
> - v1.3（2026-09-09）：补首次上线 P0、原子静态发布、每人独立账号；定稿“首批统一 SYSTEM_ADMIN、稳定后落三角色 RBAC”；高危操作仅 SYSTEM_ADMIN；紧急停催先 runbook；复用催收系统已有告警，本轮不建管理后台专属钉钉告警。
> - v1.2（2026-09-08）：写入 §10 已知问题；钉钉 jar 与前端上线拆窗口；删 §3.1 笔误「3323」。  
> - v1.1（2026-09-08）：按评审改访问模型（办公室 + VPN 白名单、隧道仅应急）、允许一次发 jar 补拦截器、一次切 nginx、修正登录页已就绪 / 校验表 / ACME root / mock 隔离 / Cookie Secure；写入已核实出口 IP。同日补充 `218.94.54.138`、`112.2.0.198`。  
> - v1.0（2026-09-01）：初版。前端独立部署 + `/api` + 同源 + 两步上线 + 容器零改动（其中两步上线与零改动已废止）。
