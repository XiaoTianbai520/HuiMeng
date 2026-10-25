# 文档入口

> 当前阶段：**M0、M1、M2 安全基座及当前接通范围的开发/隔离验收完成**，见[M2 最终验收](validation/M2_ACCEPTANCE.md)。M3-01/03 仅补齐最小依赖；完整 M3/M4、1.0 及最新源码的正式部署仍待完成。内部 CA 安装可选。

## 接手与实施

**首次接手先读[交接入口](handoff/README.md)**，其中包含阅读顺序、环境、仓库状态、M2 验收边界及可复制的交接提示。

本次整理的检查范围与接手审阅结果见[审阅记录](handoff/REVIEW.md)。

| 文档 | 职责 |
| --- | --- |
| [PRD](product/PRD.md) | 业务需求与版本边界 |
| [DESIGN](architecture/DESIGN.md) | 架构、安全、数据与部署设计 |
| [CONTRACTS](api/CONTRACTS.md) | 公共契约、已核对的上游协议及兼容接口细化边界 |
| [OpenAPI](api/openapi.yaml) · [冻结记录](api/M1_CONTRACT_REVIEW.md) | 当前 44 操作/37 路径，其中 35 实现；当前仅接通固定 Qwen 文生图和私有媒体，其他创作及安装业务仍待迁移 |
| [TASKS](plan/TASKS.md) | 任务依赖、状态和完成标准 |
| [迁移与验收矩阵](product/TOONFLOW_WEB_MIGRATION.md) | 路由分组与 A01–A18 定义 |
| [插件市场范围](product/PLUGIN_MARKET.md) | 原市场及插件管理的现行范围 |

## 当前基线与问题

- [M0 最终收尾](validation/M0_FINAL.md)
- [M1 验证与后续边界](validation/M1_REPORT.md) · [独立前端](../frontend/README.md)
- [M2 团队管理收尾与浏览器证据](validation/M2_UI_REPORT.md)
- [M2 Java 分层、事务回归与本地调试](validation/M2_JAVA_STRUCTURE.md) · [调试指南](../backend/DEBUGGING.md)
- [M2 服务器 MySQL 开发库连接与迁移](validation/M2_MYSQL_DEV.md)
- [M2 IDEA 直接 MySQL TLS 连接修复](validation/M2_MYSQL_TLS.md)
- [M2 安全基座与部署验证](validation/M2_REPORT.md) · [后端模块](../backend/README.md) · [部署说明](../backend/deploy/README.md)
- [当前供应商与实测边界](validation/LOCAL_PROVIDERS.md)
- [Web 功能基线](validation/WEB_BASELINE.md)
- [问题与迁移条件](validation/ISSUES.md)
- [A01–A18 验收证据索引](validation/TEST_CASES.md)
- [127 条路由索引](api/route-inventory.md) · [逐条正文](api/upstream/route-details.md) · [JSON 台账](api/upstream/route-details.json)
- [上游引入与初始许可](upstream/UPSTREAM_INTAKE.md) · [依赖许可初始台账](upstream/third-party-license-inventory.csv)
- [M1 前端引入](upstream/FRONTEND_INTAKE.md) · [当前浏览器产物许可](../frontend/licenses/README.md)

## 按需查阅

- [PlantUML 源图](architecture/diagrams/)
- [测试夹具](validation/fixtures/README.md) · [原始证据](validation/evidence/) · [协议取证脚本](validation/scripts/README.md)
- [文档维护与只读检查](scripts/README.md)
- [历史报告归档](archive/README.md)：旧安装说明和阶段过程不进入默认阅读路径。

- [M2 私有 MinIO 媒体存储接入](validation/M2_MINIO.md)：独立开发桶、V4、四个媒体操作与真实联调证据。
