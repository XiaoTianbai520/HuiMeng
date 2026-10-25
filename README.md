# 绘梦 1.0

个人团队内部 AI 短剧、漫剧 Web 生产平台，基于 Toonflow 固定提交进行安全迁移。

**当前阶段：M0、M1 和 M2 安全基座及当前接通范围的开发/隔离验收完成。** 项目文件页、私有 MinIO、成员获批 Qwen 文生图及撤权检查已验证；用户已确认安全配置下载。M3-01 已开始安全保存批次，M3-03 仍为最小依赖，完整 M3/M4 和 1.0 未验收。最新源码尚未部署，内部 CA 安装可选。见[M2 最终验收](docs/validation/M2_ACCEPTANCE.md)。

- 新接手的开发人员或 AI：先读[交接入口](docs/handoff/README.md)。
- 全部现行文档：[文档索引](docs/README.md)。
- 开发规则：[AGENTS.md](AGENTS.md)。

`backend/` 用于服务代码，`frontend/` 用于独立前端，`docs/` 用于需求、设计、契约、任务和证据。命令分别见[后端 README](backend/README.md)和[前端 README](frontend/README.md)，不要在根目录猜测应用命令。`frontend/local-nodes/` 是 M0 历史预设，不参加应用构建。

当前开发库 V4、私有媒体 API、文件页及固定 Qwen 文生图 1K/1:1 可联调；原文档写入/冲突、完整素材与其他创作/协议/FFmpeg 仍按 M3 推进。正式库仍 V3，正式部署尚未更新。见[存储接入](docs/validation/M2_MINIO.md)和[任务计划](docs/plan/TASKS.md)。
