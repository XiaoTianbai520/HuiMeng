# M2 私有媒体对象存储接入

> 后续最小页面/模型依赖及 M2 当前范围验收已完成，见[M2 最终验收](M2_ACCEPTANCE.md)。本报告的 55/50 等数量与待办描述保留本批采集时含义，正式部署仍未升级。

日期：2026-10-25。用户已部署 MinIO 并授权更新计划及接入；本批完成 [TASKS](../plan/TASKS.md) 的 M2-S01–S04，不表示 M2-03/04、M3 或 1.0 全部验收。PRD、DESIGN 与 CONTRACTS 已同步；没有恢复旧供应商或开放 Bun 通配代理。

## 1. 已完成的运行接线

| 项目 | 结果 |
| --- | --- |
| MinIO 现场 | 既有容器 healthy；镜像 digest `87896cb33f93ecfcca4e883c3fb2f86a0a1a600c003a104011ed30631c1869c3`；S3/管理端只绑定局域网；未重启或改暴露配置 |
| 业务隔离 | 新建独立私有开发桶 `huimeng-dev-media`、随机专用身份；只允许该桶 projects/* 对象读写/删除及桶区域读取；列桶、跨桶、前缀外和匿名访问拒绝 |
| MySQL | 独立开发库 huimeng_dev 从 V3 迁移至 V4，重复执行迁移通过；仅为既有开发运行身份增 project_media 的 SELECT/INSERT/UPDATE/DELETE；没有 DDL 或其他库授权 |
| 本机 | 显式 local profile 可选导入 `.runtime/object-storage/application.properties`；受限目录仅当前用户和 SYSTEM 可读；仍用原 MySQL TLS 信任库，IDEA 无需 SSH 隧道 |
| 生产边界 | 正式 huimeng 库仍 V3，正式绘梦 API/Web/Bun 未更新；没有给正式运行进程挂载开发 MinIO 身份，也未迁移业务文件 |

MinIO 管理身份仅用于一次性建桶/分配最小权限，没有复制给 Spring。本机及服务器凭据仅在忽略/受限文件，不在报告、夹具、日志或 Git。服务器配置文件为 `/opt/apps/huimeng-dev/secrets/object-storage.properties`（0600）；本机文件不随仓库交付。

## 2. 代码与契约

- 固定 AWS SDK Java v2 2.57.2，S3 path-style，显式静态应用身份，连接 5 秒/读取 60 秒/单次调用 10 分钟，上传关闭 SDK 自动重试；默认不开启存储，缺配置时媒体存储明确 503，不回退到系统身份。
- Controller → ProjectMediaService/Impl → ProjectMediaMapper，独立 DO/RespDTO，统一异常；ObjectStorage 隔离 S3 SDK，便于调试和后续替换。
- V4 project_media 保存项目、随机媒体 ID、内部对象键、显示文件名、字节数、SHA-256、状态及创建者/时间。参数化 SQL 同时限定 projectId 和 mediaId；HTTP DTO 不返回内部定位或凭据。
- 四个精确平台操作：媒体上传、READY 列表、原字节/Range 下载、删除。OpenAPI 共 44 操作、37 路径，32 已实现；127 条源创作路由全部 publicEnabled=false，8 frozen/92 proposed/25 excluded/2 internal 不变。
- 上传采用 Content-Length 和流式 SHA-256，0–512 MiB；文件名不参与对象键生成。未加入项目的管理员同样拒绝，上传结束再次检查成员/活动项目。写请求要求 CSRF/Origin，下载再次检查成员；无公开桶、预签名 URL 或服务器目录输入。
- READY 与上传审计同一个数据库事务；上传失败尝试精确键补偿并记 FAILED。删除失败保留 DELETING 可重试。宕机残留保留可核对状态，不声称 DB/S3 跨服务原子提交；本批不提供自动清桶或自动孤儿删除。

## 3. 验证证据

| 验证 | 结果 |
| --- | --- |
| `backend` Maven spotless:apply verify | 55 项测试、0 失败、0 跳过；含分层、会话/权限、V4、摘要/Range、上传中途撤权、短流、审计失败回滚和删除重试 |
| 显式 MinioAcceptanceHarness | 1 项真实集成测试、9 项 S3 检查通过：[证据](evidence/m2-minio-policy.json)；合成探测对象精确删除 |
| 正常 Spring + MySQL + MinIO | 50 项 HTTP 检查通过（包括多次 CSRF 获取、数据清理和端口关闭验证）：[证据](evidence/m2-minio-http.json)；Windows 上传约 2 MiB 二进制、摘要及原字节一致、Range/416、跨项目、成员移除立即拒绝、删除及空对象通过 |
| 独立开发库迁移 | V4 成功，再次执行无新增迁移；与 Spring 相同的应用专用 TLS 信任参数；正式库未涉及 |
| 契约 | OpenAPI 标准校验及前端生成类型/路由一致性通过；最终文档/前端检查见交接入口 |

实际联调 JAR SHA-256：`21cedc78c0912bc6843f05ea1303a0f10081b3e4c599fb3f66d88795e0bf4f5f`。正常生产 main 在独立回环 18088 验证；临时进程已停止，合成媒体已删、项目已归档、合成账号已停用，不停止用户 IDEA 进程。

后端 79 个运行 JAR 的 135 份内嵌法律文本及摘要已[归档](../../backend/licenses/README.md)；9 个依赖无内嵌文本，清单保留缺口，发布前继续核对。许可归档不等于安全扫描或发布批准。

## 4. 重跑与调试

所有真实集成工具均为显式选择，不在默认 verify 连接共享服务。参考[后端 README](../../backend/README.md)和[脚本说明](../../backend/scripts/README.md)：先准备受限配置与独立开发资源，再迁移、按表授权，最后运行 HTTP/MinIO 验收。一次性 provision 遇已有资源会拒绝，不能反复重跑来重置账号或桶。

本轮迁移工具初次只复用 JDBC URL，未带 Hikari 的专用 TLS 信任参数，连接报信任链错误；已将三个信任库参数传入迁移专用连接，严格 VERIFY_CA 下迁移及重复迁移通过。没有关闭证书校验。中途自动审批曾因额度限制无法完成，未执行当次操作；用户续行后获批重试已完成，不存在尚未处理的审批阻塞。

## 5. 仍待完成

- M2-03/04 的完整文件页面、原格式写入/冲突和公开获批模型链保持待验收；本批媒体 API 不代表原素材页面、生成结果、导出及 FFmpeg 已接线。
- M3-S01 随 M3-01/03/05 完成原相对路径引用兼容、媒体 ID 引用、执行器隔离暂存/结果上传/清理。FFmpeg 仍处理音视频，MinIO 保存原字节。
- Windows MinIO 媒体操作已通过；原工作区文档 read 仍因缺少 SecureDirectoryStream 返回 503，M2-I07 未整体关闭。
- 正式部署切换需独立运行桶/身份、运行库 V4 迁移与按表授权，并复验 HTTPS 入口；不能直接用新 JAR 覆盖仍为 V3 的正式应用。
- 现有 S3 链路仍为受信任局域网 HTTP，自动备份与元数据/对象成套恢复未验收。已有第三方镜像的维护、法律文本缺口及安全更新按 M4 检查；本批未升级镜像或增加公网访问。

最终前端 21 项测试/78 断言及生产构建通过（保留既有大 chunk 提示），文档链接与 OpenAPI/生成一致性通过。最初受限环境出现依赖类型解析错误，锁文件安装核对无版本变化，正常依赖读取环境复验通过；未改 TypeScript 严格设置。Windows Java 启动器首次残留自有 18088 子进程导致打包锁，现已精确清理，脚本改为终止自有进程树并验证端口关闭；最终 JAR 与 HTTP 验收均重跑通过。迁移工具输出统一脱敏完整 URL、认证及信任库参数，工作区凭据精确匹配扫描单独执行。

最终验证摘要见[机器可读证据](evidence/m2-minio-summary.json)。服务器只读复核：开发迁移为 V1–V4、正式为 V1–V3，READY 合成媒体/活动合成账号/活动合成项目均为 0，开发运行账号 schema DDL 授权为 0；源码与自有验证日志的凭据精确匹配扫描通过。
