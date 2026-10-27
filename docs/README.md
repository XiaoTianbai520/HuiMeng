# 文档入口

> 当前阶段：**M0、M1、M2 安全基座及当前接通范围的开发/隔离验收完成**，见[M2 最终验收](validation/M2_ACCEPTANCE.md)。M3-01 已完成安全保存、目录操作与独立文档区域验证，原文件树/画布继续接线，M3-03 仍为最小依赖；完整 M3/M4、1.0 及最新源码的正式部署仍待完成。内部 CA 安装可选。

## 接手与实施

**首次接手先读[交接入口](handoff/README.md)**，其中包含阅读顺序、环境、仓库状态、M2 验收边界及可复制的交接提示。

本次整理的检查范围与接手审阅结果见[审阅记录](handoff/REVIEW.md)。

| 文档 | 职责 |
| --- | --- |
| [PRD](product/PRD.md) | 业务需求与版本边界 |
| [DESIGN](architecture/DESIGN.md) | 架构、安全、数据与部署设计 |
| [媒体执行队列与时限](architecture/MEDIA_EXECUTION_QUEUE.md) | 单卡串行派发、排队、超时与取消语义 |
| [CONTRACTS](api/CONTRACTS.md) | 公共契约、已核对的上游协议及兼容接口细化边界 |
| [OpenAPI](api/openapi.yaml) · [冻结记录](api/M1_CONTRACT_REVIEW.md) | 当前 66 操作/59 路径，其中 61 实现；固定 Qwen、私有媒体、文件保存、目录、Agent 与能力目录已接通 |
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

- [M3-01 首批安全保存与修订冲突](validation/M3_FILE_WRITE.md)：77 项后端、46 项 Linux HTTP/文件检查，完整工作区页面和文件 CRUD 仍待接续。

- [M3-01 安全文件操作与文档浏览器验收](validation/M3_WORKSPACE.md)：84 项后端、39 项文件操作 HTTP、21 项真实浏览器检查；M3-W02 完成，M3-W03 原页面/画布继续。
