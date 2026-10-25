# 文档入口

> 当前阶段：**M0、M1 完成，M2 基座已实现、部分验收待完成，M3–M4 未开始**。团队管理、账号/会话和部署基础收尾已验证；M2-03/04 仍待跨阶段业务验收，CA 安装可选。见 [M2 报告](validation/M2_REPORT.md)。

## 接手与实施

**首次接手先读[交接入口](handoff/README.md)**，其中包含阅读顺序、环境、仓库状态、M2 验收边界及可复制的交接提示。

本次整理的检查范围与接手审阅结果见[审阅记录](handoff/REVIEW.md)。

| 文档 | 职责 |
| --- | --- |
| [PRD](product/PRD.md) | 业务需求与版本边界 |
| [DESIGN](architecture/DESIGN.md) | 架构、安全、数据与部署设计 |
| [CONTRACTS](api/CONTRACTS.md) | 公共契约、已核对的上游协议及兼容接口细化边界 |
| [OpenAPI](api/openapi.yaml) · [冻结记录](api/M1_CONTRACT_REVIEW.md) | M1 的 38 操作加 M2 新增 1 个安全管理读接口；39 个操作中已实现 26 个，创作及安装业务继续关闭 |
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
