# M3-01 第一批：原格式保存与修订冲突

日期：2026-10-25。前置 M2 成果已以中文提交 `8621501` 推送 GitHub master，远端 SHA 已核对。本批开始 M3-01，只完成文件保存底座；文件列表、目录及复制/重命名/删除、原页面接线、画布修订链和完整 A04/A05/A06 尚未验收。

## 实现

精确 PUT `/api/v1/projects/{projectId}/creative/workspaces/files/write`，沿用会话、Origin/CSRF、逐次项目成员和活动状态。新建必须 exclusive=true，覆盖必须提供读取时的 expectedRevision，二者不可同时提供；不接受目录、自报身份或重复查询参数。正文为原字节，或以 encoding 指定保存为 UTF-8/BOM、UTF-16 LE/BE；文本输入要求有效 UTF-8，保留原换行。声明正文与编码后大小均最多 8 MiB，Content-Length 必填；媒体大文件继续走独立 MinIO API。

同一规范化文件路径在单 Spring 进程内使用有界条带锁，锁内读原字节 SHA-256 并比较。旧修订返回 409/REVISION_CONFLICT 和 details.currentRevision；文件已删除则该字段为 null，绝不悄悄重建。新建目标存在同样冲突。成功返回原 WriteResult 协议及新修订号，不改原文档正文格式。

从文件系统根安全句柄逐层 NOFOLLOW 打开父目录，临时文件以随机名称和 0600 权限创建在同一父句柄内；写完并 force 后再以同目录句柄原子替换。替换前复核成员并记写入尝试，成功后留审计。失败清理精确临时项，不删除旧文件再复制，不隐式创建父目录。原子移动依据 [JDK 17 SecureDirectoryStream 说明](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/SecureDirectoryStream.html#move(T,java.nio.file.SecureDirectoryStream,T))，本批在 Linux 实际验证可替换已有文件；不支持的文件系统返回 503，不作路径预检降级。

## 验证

| 项目 | 结果 |
| --- | --- |
| 默认后端 | Maven verify / Spotless / JAR：77 项，0 失败、0 错误、0 跳过；Windows 验证参数/权限及不支持安全句柄时拒绝且原字节不变 |
| 真实 Linux | [46 项 HTTP/文件检查](evidence/m3-file-write-linux.json)通过：原格式字节与换行、四种编码读写、空文件、精确大小限制、双写恰好一次成功、冲突修订、成员/跨项目/CSRF、符号链接拒绝、上传中撤权及临时项清理 |
| Linux JUnit | 同一隔离环境执行 4 项，通过；覆盖真实安全句柄、提交前拒绝保留旧文件和清理临时项、并发 CAS，以及真实 Spring/H2 HTTP 工具 |
| 前端 | 统一客户端边界、类型和 21 项测试/78 断言通过；本批没有页面或画布逻辑改动，不冒充浏览器保存验收 |
| 契约 | 44 操作/37 路径中 36 实现；writeFile 的当前实现范围明确登记，所有 127 条源路由 publicEnabled 仍 false |
| 文档与凭据 | 相对链接/JSON/脚本、OpenAPI 及生成一致性、diff 空白检查通过；[精确凭据扫描](evidence/m3-file-credential-scan.json)覆盖本批源码、测试报告与自有日志，8 个受限运行值及 URL 编码形式未命中；不替代 M4 全业务泄漏验收 |

Linux 使用既有 JDK 21.0.9 镜像和本机编译测试类/依赖，新建专用验证目录；镜像无 Python，宿主 Python 只连接同一夹具回环 18090，并访问同路径绑定的随机测试目录。没有连接 MySQL、MinIO 或 ComfyUI，没有挂载业务工作区/真实凭据，没有发布外部端口。临时容器退出、18090 关闭，成功及失败尝试创建的随机夹具均已清理，证据已取回。

首次工具尝试未处理会话 Cookie 轮换，CSRF 被拒；随后匿名写预期也校正为现有 CSRF 边界的 403，匿名读为 401。修复的是验收客户端，失败尝试未计入通过。Windows 受限进程环境中的初次回归停于启动阶段，终止自有命令后，本机完整回归通过；没有修改依赖或放松安全边界。

## 范围与后续

- 当前锁只协调一个 Spring 进程；Bun 尚未提供文件写入。后续所有写/复制/重命名/删除必须共用保存协调；不允许另一个进程绕过接口直写同一工作区。多 Spring 或外部文件写入者尚未支持。
- 文件系统与数据库审计不构成原子事务。替换前审计失败阻止保存；替换后审计或目录关闭失败可能返回 503 而正文已改变，此时须重新读取核对，不能无条件覆盖重试。断电持久性和成套恢复继续 M4。
- Windows 默认文件系统读写仍可能 503；MinIO 媒体可用。本批没有原文件页面/画布双浏览器联动证据，后续批次不得把接口 CAS 通过当成完整 A05。
- 下一批先实现目录/文件列表和其余操作的安全 I/O，再接原文档、文件树与画布读取修订/保存暂停提示。其他 M3 模型/Agent/FFmpeg/市场/协议继续按依赖执行。

没有新 SQL 迁移，开发库仍 V4、正式库仍 V3；正式 API/Web/Bun 未更新。最新状态见[TASKS](../plan/TASKS.md)和[交接入口](../handoff/README.md)。本批不是完整 M3 或 1.0 验收。
