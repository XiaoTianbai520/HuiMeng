# 后端运行依赖许可证据

从实际打包 JAR 的 BOOT-INF/lib 提取内嵌 LICENSE/NOTICE/COPYING，保留原字节。清单及 SHA-256 见 [runtime-inventory.json](runtime-inventory.json)，文本位于 texts/。

在 backend 工作目录构建后运行 `python scripts/Archive-RuntimeLicenses.py` 重新生成。新增 S3 SDK 固定为 AWS SDK Java v2 2.57.2，采用 Apache-2.0；选用 S3 兼容适配层，不向应用引入 MinIO 服务端代码。服务器镜像的独立许可和维护状态仍需部署/发布检查。

本目录保存依赖携带的法律文本证据，不表示每个依赖已完成许可解释、安全更新或发布审核。无内嵌文本的条目保留在清单中，M4 发布前须对照 POM、官方源码和实际分发要求补齐核对。依赖改变后重新生成，不把旧清单当新产物验收。
