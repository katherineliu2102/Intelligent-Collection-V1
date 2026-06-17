# 测试文档（testing/）

> 给**新进场测试同学**的测试计划 / 用例 / 数据落点。

## 放什么
- 测试计划、用例集、回归清单
- 测试数据准备说明（配合 [`../../db/`](../../db/) 的 seed 脚本）
- 联调/验收步骤

## 已有测试资产（先读）
- 测试地图总览：[`../测试总览_Phase1.md`](../测试总览_Phase1.md)
- 引擎单测矩阵：[`../测试矩阵_engine阶段1.md`](../测试矩阵_engine阶段1.md)
- 渠道功能测试指南：[`../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md`](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)

## 约定
- 新增/变更行为 → 补单测或在本目录记录验证方式（见根 `PULL_REQUEST_TEMPLATE.md` 卡点）。
- 验证命令统一遵循 `.cursor/rules/ic-v1-validation.mdc`。
