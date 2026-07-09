## 这个 PR 做了什么 / 为什么


## 影响模块
<!-- common / engine / ingestion / admin / channel / service / deploy / docs -->

## 提交前确认（涉及才勾）
- [ ] 改了 `common` 契约(SPI/DTO/枚举) → 已全量 `mvn -q test` 且已 @ 通知 channel/service 同事
- [ ] 改了表结构 → 追加了**增量** SQL（未改历史脚本）
- [ ] 本 PR **不含**任何真实密钥 / 连接串 / `.env` / `*-local`
- [ ] 已按 `.cursor/rules/ic-v1-validation.mdc` 跑验证，结果贴在下方

验证结果：
```

```
