# 绘梦 1.0

个人团队内部 AI 短剧、漫剧 Web 生产平台，基于 Toonflow 固定提交进行安全迁移。

**当前阶段：M0、M1 已完成，M2 安全基座与隔离部署已实现，部分验收待完成。** Spring/MySQL、会话/权限、密钥、外部凭证与审计已接通；局域网 HTTPS API 验证通过。团队管理与个人偏好已接通，本机真实 Spring 浏览器检查通过；CA 安装可选，局域网证书提示仍如实记录；文件、模型和协议业务按 M3 接续。M3–M4 未开始，1.0 未验收。见 [M2 验证记录](docs/validation/M2_REPORT.md)。

- 新接手的开发人员或 AI：先读[交接入口](docs/handoff/README.md)。
- 全部现行文档：[文档索引](docs/README.md)。
- 开发规则：[AGENTS.md](AGENTS.md)。

`backend/` 用于服务代码，`frontend/` 用于独立前端，`docs/` 用于需求、设计、契约、任务和证据。命令分别见[后端 README](backend/README.md)和[前端 README](frontend/README.md)，不要在根目录猜测应用命令。`frontend/local-nodes/` 是 M0 历史预设，不参加应用构建。
