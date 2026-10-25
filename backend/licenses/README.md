# 后端运行依赖许可证据

从实际打包 JAR 的 BOOT-INF/lib 提取内嵌 LICENSE/NOTICE/COPYING，保留原字节。清单及 SHA-256 见 [runtime-inventory.json](runtime-inventory.json)，文本位于 texts/。

Git 对 texts/ 禁止行尾转换并按原始制品比较，保证重新克隆后的字节与清单一致；本批修复既有后端归档缺少此规则的问题，恢复 JAR 原始 CRLF 文本。暂存的 136 份内嵌文本已逐项核对 SHA-256；不改法律内容。

在 backend 工作目录构建后运行 `python scripts/Archive-RuntimeLicenses.py` 重新生成。新增 S3 SDK 固定为 AWS SDK Java v2 2.57.2，采用 Apache-2.0；选用 S3 兼容适配层，不向应用引入 MinIO 服务端代码。服务器镜像的独立许可和维护状态仍需部署/发布检查。

本目录保存依赖携带的法律文本证据，不表示每个依赖已完成许可解释、安全更新或发布审核。无内嵌文本的条目保留在清单中，M4 发布前须对照 POM、官方源码和实际分发要求补齐核对。依赖改变后重新生成，不把旧清单当新产物验收。

本批 JNA 固定 5.17.0，当前构建包含 80 个运行依赖 JAR、136 份内嵌法律文本；9 个无内嵌文本项仍留 M4 核对。JNA 提供 Apache-2.0 / LGPL-2.1-or-later 双重选择，本项目选择 Apache-2.0。原 JAR 的 META-INF/LICENSE 保持原字节，另从固定官方标签归档 [Apache 原文](texts/jna-5.17.0.jar/JNA-AL2.0.txt)及其[原生 libffi 许可文本](texts/jna-5.17.0.jar/LIBFFI-LICENSE.txt)；后两份不计入 136 个内嵌文件。

[native-runtime.json](native-runtime.json)记录从实际应用 JAR 提取的 Linux x86_64 本地库及源 JAR、补充文本摘要和固定上游地址；`Prepare-NativeLibraries.py` 将库和两份文本一同准备到 target/native，Docker 镜像继续携带。补充文本保留法律内容，不能把人工归档的文本摘要等同远端原始字节校验。没有下载不同版本库替代，也没有因此宣称依赖安全更新或整个发布许可核对完成。
