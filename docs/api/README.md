# 接口文档（api/）

> 给**新进场后端 / 联调同学**的接口契约落点。`collection-admin` 对外的 REST / Webhook 接口在此登记。

## 放什么
- REST 接口的入参 / 返回 / 错误码（如 `/plans/{id}`、`/mock/ingest`、`/webhook/channel-callback`）
- Webhook 回调契约（渠道异步结果回填）
- 对外开放的查询/管理 API

## 约定
- 改了接口入参或返回 → **同一 PR 内**更新本目录对应文档（见根 `PULL_REQUEST_TEMPLATE.md` 卡点）。
- 跨模块 SPI/DTO 契约不在此，见 [`../contracts/`](../contracts/)。
