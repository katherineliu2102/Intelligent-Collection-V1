# Pub/Sub 运行服务账号交付基线

> 生产凭证和真实地址不入仓库。本页只保留已交付的运行边界与后续权限收口动作。

## 已交付

| 项 | 当前约定 |
|---|---|
| 服务账号 | `intelligent-collection-v1@fintech-all.iam.gserviceaccount.com` |
| 项目 | `fintech-all` |
| 生产机密钥路径 | `/opt/app/secrets/credentials.json` |
| 仓库规则 | 本地密钥仅可放 `deploy/secrets/`，该目录已被 Git 忽略 |
| 应用职责 | 仅消费案件和调度两个 Pub/Sub 订阅 |

运行账号至少需要两个订阅的 `roles/pubsub.subscriber`；应用不需要 Pub/Sub 发布、管理、项目列举或 Scheduler 管理权限。应用启动后应验证两个 Pub/Sub 健康指示器为 `UP`。

## 权限收口

当前账号额外拥有 `roles/pubsub.publisher`。这是超授：若生产机凭证泄露，攻击者可向案件主题注入伪造事件，进而触发真实触达。

最终应将运行账号收敛为仅 `roles/pubsub.subscriber`（优先按订阅资源授权）。需要发布测试消息、查看积压指标或 Scheduler 状态时，使用不部署到生产机的独立运维/测试身份。

## 交付与轮换

- 密钥通过受控渠道交付，生产文件权限为 `600`，仅容器挂载读取。
- 不得使用自然人 ADC 作为运行凭证。
- 按运维轮换策略更新密钥；轮换后重启应用并复验 Pub/Sub 健康状态。
