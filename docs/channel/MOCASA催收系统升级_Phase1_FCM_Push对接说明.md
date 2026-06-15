# 文档已迁移

催收 App Push 改为经 **内部通知中心** 发送（JPush），collection-channel **不再直连** FCM HTTP v1。

请维护：

**[MOCASA催收系统升级_Phase1_Notification对接说明.md](./MOCASA催收系统升级_Phase1_Notification对接说明.md)** — §2 App Push（异步 `/v1/app_notification/send`）

用户画像使用 **JPush Registration ID**（`UserProfile.device.jpushToken`），见 [领域模型 §3.2 DeviceInfo](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)。

API 细节见 [notification-send-api.md](../../../AI%20collection/相关资料/notification-send-api.md) § App Notification。
