# 绘梦 1.0

公司内部 AI 短剧、漫剧 Web 生产平台，基于 Toonflow 固定提交进行安全迁移。

**当前阶段：M0、M1 已完成，下一项为 M2-01。** 独立 Vue 前端、统一客户端、OpenAPI、离线契约回归及浏览器产物许可归档已交付；Spring Boot 后端尚未引入，完整业务尚未验收。见 [M1 验证记录](docs/validation/M1_REPORT.md)。

- 新接手的开发人员或 AI：先读[交接入口](docs/handoff/README.md)。
- 全部现行文档：[文档索引](docs/README.md)。
- 开发规则：[AGENTS.md](AGENTS.md)。

`backend/` 用于服务代码，`frontend/` 用于独立前端，`docs/` 用于需求、设计、契约、任务和证据。安装、检查、构建与本地开发命令见[前端 README](frontend/README.md)。`frontend/local-nodes/` 是 M0 历史预设，不参加应用构建。
