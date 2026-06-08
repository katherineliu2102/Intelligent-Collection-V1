# 项目本地启动说明

## 1. 本地配置

本地开发测试时，如果需要调整系统参数，优先修改：

```text
collection-admin/src/main/resources/application-local.yml
```

`application-local.yml` 会在 `local` profile 下生效，可用于覆盖 Nacos 下发的默认配置。

## 2. Nacos 配置

项目启动时会从 Nacos 读取配置，测试环境的 Nacos 连接信息请向项目负责人获取。

需要准备以下信息：

```text
NACOS_SERVER_ADDR=<Nacos 地址，例如 127.0.0.1:8848>
NACOS_NAMESPACE=<Nacos 命名空间>
NACOS_GROUP=<Nacos 分组>
NACOS_USERNAME=<Nacos 用户名>
NACOS_PASSWORD=<Nacos 密码>
```

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
APP_PORT=8080
```

`.env` 仅用于本地运行，不要提交到 Git。

## 4. Java 命令启动

先进入项目根目录，编译代码：

```bash
mvn -pl collection-admin -am clean package -DskipTests
```

再通过环境变量启动应用。Git Bash 示例：

```bash
export SPRING_PROFILES_ACTIVE=local
export NACOS_SERVER_ADDR=<Nacos 地址>
export NACOS_NAMESPACE=<Nacos 命名空间>
export NACOS_GROUP=<Nacos 分组>
export NACOS_USERNAME=<Nacos 用户名>
export NACOS_PASSWORD=<Nacos 密码>

java -jar collection-admin/target/collection-admin.jar
```

## 5. Docker 启动（推荐）

启动前请确认本机已安装并启动 Docker。

首次运行时，先准备 `.env`：

```bash
cp .env.example .env
```

填写 `.env` 后，在项目根目录执行：

```bash
./start-docker.sh
```

Windows CMD 可执行：

```cmd
start-docker.cmd
```

脚本会先编译 `collection-admin` 及其依赖模块，然后重新构建并启动 Docker 容器。

## 6. 查看日志

查看容器日志：

```bash
docker compose logs -f collection-admin
```

查看本地挂载日志目录：

```text
logs/
```
