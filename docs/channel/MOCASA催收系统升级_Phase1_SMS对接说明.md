# 文档已迁移

催收 SMS 改为经 **内部通知中心** 发送，collection-channel **不再直连** QH / Hiway / BORI。

请维护：

**[MOCASA催收系统升级_Phase1_Notification对接说明.md](./MOCASA催收系统升级_Phase1_Notification对接说明.md)** — §1 SMS（同步 `/v1/sms/send`，`contentType=collection`）

通知中心后台账号路由（QH/Hiway/BORI weight）见该文档 **附录 A**（运维配置，非引擎实现）。

底层供应商 API 仅供通知中心排障：

- [QH SMS 接口.md](../../../AI%20collection/相关资料/QH%20SMS%20接口.md)
- [HiwayIO-API 1.5.2.docx](../../../AI%20collection/相关资料/HiwayIO-API%201.5.2.docx)
- [【BORI】HTTP 对接开发文档1.0.docx](../../../AI%20collection/相关资料/【BORI】HTTP%20对接开发文档1.0.docx)
