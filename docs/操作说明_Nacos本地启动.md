# 项目本地启动说明

## 1. 本地配置

本地开发测试时，如果需要调整系统参数，优先修改：

```text
collection-admin/src/main/resources/application-local.yml
```

`application-local.yml` 会在 `local` profile 下生效，可用于覆盖 Nacos 下发的默认配置。

## 2. Nacos 配置

项目启动时会从 Nacos 读取配置。测试环境已提供**公共账号**，向项目负责人获取地址与命名空间后，在 `.env` 中填写即可（全员共享同一套渠道密钥与 DB 配置）。

需要准备以下信息：

```text
NACOS_SERVER_ADDR=<Nacos 地址>
NACOS_NAMESPACE=<Nacos 命名空间>
NACOS_GROUP=<Nacos 分组>
NACOS_USERNAME=<测试环境公共账号>
NACOS_PASSWORD=<测试环境公共密码>
```

启动后自动加载的 Data ID（见 `collection-admin/.../application.yml`）：


| Data ID                             | 说明                                    |
| ----------------------------------- | ------------------------------------- |
| `intelligent-collection-common.yml` | DB、Redis、引擎参数、**渠道供应商配置 `channel.*`** |
| `intelligent-collection-local.yml`  | `local` profile 下的测试环境覆盖              |


渠道开发时 Nacos 中典型配置项：`channel.notification.*`（SMS/Push）、`channel.sendgrid.*`、`channel.lth.voice.*`、`channel.compliance.*`、`channel.debug.single-step`（单渠道冒烟）。详见 [collection-channel 开发执行指南 §6](docs/MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md#6-nacos-配置清单渠道模块使用)。

注意：不要将真实账号、密码提交到 Git 仓库。

## 3. 环境变量

项目根目录提供了 `.env.example`，可复制为本地 `.env`：

```bash
cp .env.example .env
```

Windows CMD 可使用：

```cmd
copy .env.example .env
```

然后在 `.env` 中填写实际配置：

```dotenv
SPRING_PROFILES_ACTIVE=local
NACOS_SERVER_ADDR=
NACOS_NAMESPACE=
NACOS_GROUP=
NACOS_USERNAME=
NACOS_PASSWORD=
```dotenv
SPRING_PROFILES_ACTIVE=local
# 格式必须是 host:port，例如 34.96.213.197:8847（不要带 http:// 或 /nacos）
NACOS_SERVER_ADDR=
NACOS_NAMESPACE=
NACOS_GROUP=
NACOS_USERNAME=
NACOS_PASSWORD=
APP_PORT=8080
```

`.env` 仅用于本地运行，不要提交到 Git。

## 4. 环境确认（开发前必跑）

> 全部通过后再开始渠道开发。详细 TC 见 [功能测试指南 §4 TC-REG-01](docs/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md#4-回归基线)。

### 4.1 前置检查

| # | 项 | 命令 / 操作 | 预期 |
|---|-----|-------------|------|
| 1 | JDK 8 | `java -version` | 1.8.x |
| 2 | Maven | `mvn -version` | 3.6+ |
| 3 | `.env` | 复制 `.env.example` → `.env` 并填写 | 7 个变量非空 |
| 4 | Nacos 地址格式 | `NACOS_SERVER_ADDR=host:port` | **勿**写 `http://` 或 `/nacos` |
| 5 | Nacos 连通 | 浏览器或 curl 访问 `http://<host>:<port>/nacos` | 可打开或 200 |
| 6 | 库表 | DBA 确认 `ai_collection_db` 已执行 `db/schema.sql` | 存在 `t_contact_plan` 等表 |
| 7 | MySQL 连通 | 本机需能访问 Nacos 中 `spring.datasource.url` 的 host:port | 网络/VPN 通 |

### 4.2 编译

```powershell
cd Intelligent-Collection-V1
mvn -pl collection-admin -am clean package -DskipTests
```

预期最后一行：`BUILD SUCCESS`。

### 4.3 启动（Windows 推荐脚本）

```powershell
cd Intelligent-Collection-V1
.\scripts\start-local.ps1
```

脚本会：加载 `.env` → 修正 Nacos 地址格式 → 从 Nacos 拉取 JDBC 并注入 → 启动 **8888** 端口。

**手动启动（PowerShell）**：

```powershell
cd Intelligent-Collection-V1
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
  $p = $_ -split '=', 2
  if ($p.Count -eq 2) { Set-Item -Path "Env:$($p[0].Trim())" -Value $p[1].Trim() }
}
# 若 .env 里是 http://.../nacos，改为 host:port
$env:NACOS_SERVER_ADDR = ($env:NACOS_SERVER_ADDR -replace '^https?://','' -replace '/nacos/?$','')
java -jar collection-admin/target/collection-admin.jar
```

启动成功标志：日志出现 `Tomcat started on port(s): 8888` 且无 `APPLICATION FAILED TO START`。

### 4.4 Mock 基线 TC-REG-01

另开终端（服务保持运行）：

```powershell
# 1. 注入案件
Invoke-RestMethod -Uri "http://localhost:8888/mock/ingest?caseId=91000&userId=91000&stage=S1" -Method POST

# 2. 等待扫描器（Mock 三步步：step1 即时，step2/3 各 delay 1 分钟，建议 90s）
Start-Sleep -Seconds 90

# 3. 查计划（应无活跃 plan 或 status=PLAN_COMPLETED）
Invoke-RestMethod -Uri "http://localhost:8888/plans/active/by-case/91000"

# 4. 查 timeline（应有 3 条 SMS/PUSH/SMS）
Invoke-RestMethod -Uri "http://localhost:8888/plans/timeline/91000?limit=10"
```

| 检查项 | 预期 |
|--------|------|
| ingest 响应 | `"ok": true` |
| 约 2 分钟内 | plan `PLAN_COMPLETED`（或无 active plan） |
| timeline | 3 条记录 |

### 4.5 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| `illegal dataId` | `NACOS_SERVER_ADDR` 含 `http://` 或 `/nacos` | 改为 `host:port` |
| `Failed to configure a DataSource` | Nacos ConfigData 未拉到 `intelligent-collection-local.yml` | 用 `scripts/start-local.ps1`（自动注入 JDBC） |
| plan 一直 PENDING、timeline 0 条 | MyBatis 未开驼峰映射，`planId` 为 null | 确认 `application-local.yml` 含 `mybatis.configuration.map-underscore-to-camel-case: true` |
| 端口不对 | 本地 profile 覆盖为 8888 | 用 `8888` 而非 8080 |

## 5. Java 命令启动（Git Bash / Linux）

先进入项目根目录，编译代码：

```bash
mvn -pl collection-admin -am clean package -DskipTests
```

再通过环境变量启动应用：

```bash
export $(grep -v '^#' .env | xargs)
# 去掉 NACOS 地址中的 http:// 和 /nacos
export NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR#http://}"
export NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR#https://}"
export NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR%/nacos}"

java -jar collection-admin/target/collection-admin.jar
```

## 6. Docker 启动

启动前请确认本机已安装并启动 Docker。

首次运行时，先准备 `.env`：

```bash
cp .env.example .env
```

填写 `.env` 后，在项目根目录执行：

```bash
.\start-docker.cmd
```

Windows CMD 可执行：

```cmd
start-docker.cmd
```

脚本会先编译 `collection-admin` 及其依赖模块，然后重新构建并启动 Docker 容器。

## 7. 查看日志

查看容器日志：

```bash
docker compose logs -f collection-admin
```

查看本地挂载日志目录：

```text
logs/
```

## 8. 渠道模块开发与测试


| 文档                                                                                                           | 用途              |
| ------------------------------------------------------------------------------------------------------------ | --------------- |
| [docs/README_渠道文档索引.md](docs/README_渠道文档索引.md)                                                               | 渠道规格导航          |
| [docs/MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md](docs/MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md) | 分阶段写代码、替换 Mock  |
| [docs/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md](docs/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | 功能测试 TC、curl 命令 |


