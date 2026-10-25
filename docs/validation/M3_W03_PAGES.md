# M3-01 第三批：原文件树、文档面板与画布修订链

日期：2026-10-26。接续已推送的 `fd57510`。本批完成 M3-W03：原文件树、原文档面板与原画布接入现有安全文件 API，读取修订号、覆盖保存、409 冲突暂停/草稿保留及素材相对路径引用均通过真实浏览器验证。M3-01 的完整 A04/A05/A06/A17 尚未验收；M3-03 仍只有 M2 最小依赖，完整 M3/M4 与 1.0 未验收。没有升级正式 API/Web/Bun 或正式库。

## 实现与契约

沿用既有精确入口 `/api/v1/projects/{projectId}/creative/workspaces/files/*`，本批未新增后端业务接口、未改 SQL 迁移；开发库仍 V4、正式库仍 V3。

- **适配层**：`src/lib/workspaceFiles.ts` 新增 `readJsonSnapshot`、`writeExclusiveText`（返回首个修订号）及 `fileErrorCode`。后者同时识别新契约顶层字符串业务码与上游旧嵌套包装；旧包装顶层数字 `code` 不会被误当业务码。
- **画布修订链**：打开/新建画布记录随各画布对象的读取快照；自动保存和节点正文/画布写回均携带 `expectedRevision`，成功后更新为新返回修订号。快照按画布 ID 读写，保存期间切换目标不会串号；节点写回同步推进对应画布快照，避免后续自动保存误报冲突。复制产生的 `.toonflowCopy*` 临时项先读修订再覆盖，最终仍由 rename 保证不覆盖目标。
- **409 冲突**：自动保存遇 409/`REVISION_CONFLICT` 时暂停该画布自动覆盖，本地修改保留在内存，弹出“画布保存冲突”；可强制重新读取最新文件后恢复，继续自动保存。`documentSession` 同时识别新 `REVISION_CONFLICT` 与旧 `ESTALE`，冲突后暂停自动覆盖并保留草稿，“载入磁盘版本”或另存副本后恢复。
- **错误码兼容**：新建画布/目录的 `EEXIST`、复制/重命名/删除的 `ENOENT` 等旧判定兼容新 `REVISION_CONFLICT`、`FILE_ALREADY_EXISTS`、`NOT_FOUND`；目录非空删除仍为 `DIRECTORY_NOT_EMPTY`。
- **内置纯文本编辑器**：`ext-plaintext` 在远程扩展清单未接通或无可用扩展时回退，支持常见文本后缀与 canvasNode 正文编辑；清单恢复后优先使用获批远程扩展。它只是 M3-W03 接通文档面板的最小依赖，**不是恢复旧供应商或扩展体系**，富文本、节点/工具/技能/扩展包仍属 M3-05。
- **验收夹具**：`WorkspaceBrowserAcceptanceHarness` 端口可用 `-Dhm.browser.port` 覆盖（默认 18091），本批用回环 18096，因为 18090/18091 已是既有 MinIO 映射，未停止或复用这些服务。临时 JDK 镜像缺 `unzip` 时 wrapper 会改下 `.tar.gz` 而与发行版 `.zip` 校验值不符，须先安装 `unzip`，未删除或替换 SHA-256 校验。

## 真实浏览器验证

使用生产构建和本机 `127.0.0.1:5187` 静态/代理服务，经仅回环 SSH 转发连接独立 Linux Spring/H2（回环 18096），Playwright Chromium 驱动真实页面，第二个 API 会话模拟另一成员保存。**19 项**全部通过，见[结果](evidence/m3-w03-browser.json)：

| 分组 | 通过内容 |
| --- | --- |
| 进入与画布保存 | 登录进入工作区、自动创建画布；连续两次视口修改均自动落盘，第二次使用第一次返回的新修订号，无 409 |
| 文档读取/保存 | 文件树显示原文件；内置编辑器读取原文，编辑后自动保存，重新读取内容一致 |
| 文档冲突 | 另一会话先保存后，本页出现冲突提示；草稿保留、另一会话内容不被覆盖、自动保存暂停；载入磁盘版本后可继续保存 |
| 画布冲突 | 另一会话修改画布后，本页弹出冲突；不覆盖对方内容；重新读取载入对方视口（50%），随后恢复自动保存 |
| 素材引用 | `assets/` 目录与相对路径文档创建、打开、编辑保存及原字节下载可读 |

截图：[文档保存](evidence/m3-w03-document-saved.png)、[文档冲突草稿](evidence/m3-w03-document-conflict.png)、[画布冲突](evidence/m3-w03-canvas-conflict.png)、[最终文件树与编辑器](evidence/m3-w03-final-workspace.png)。同账号不同会话用于冲突场景，不能据此声明两名不同用户或实时共同编辑已验收。

## 其他验证与证据

| 范围 | 结果 |
| --- | --- |
| 后端默认回归 | Linux Docker Maven `verify`、Spotless、JAR：**84 项，0 失败/错误/跳过**；含 SecureWorkspaceOperationsTests 5 项 Linux 原生检查 |
| 前端 | **26 项测试/102 断言**（新增 5 项修订链适配测试）、边界/严格类型检查、生产构建通过；既有大 chunk 提示保留 |
| HTTP 文件操作 | [39 项](evidence/m3-workspace-http.json)复跑通过：复制/重命名/递归删除/根目录保护/跨项目/撤权等 |
| 文档/契约 | 相对链接、JSON、脚本语法与前端契约生成一致性检查通过 |
| 验收环境 | Vite 优化器触删除保护改用生产 dist；Bun fetch 代理间歇 Tomcat 400 改 Node 原生 http；后台标签 rAF 暂停使 enter 过渡停在 opacity 0，改用 Playwright 前台页面。均为验收环境处理，不是产品缺陷，未改安全行为 |

本批后端业务代码未变，46 项 Linux 保存回归沿用 M3-W02 结论；本批以 84 项默认回归（含 Linux 原生 5 项）与 39 项 HTTP 复跑确认无回归。临时 JDK 容器、随机 H2/工作区、合成账号及测试密钥仅用于夹具，未访问真实 MySQL/MinIO/ComfyUI。

## 尚未完成的范围

- M3-01 完整 A04/A05/A06/A17 未验收；独立文档区域、原文件树/文档面板/画布通过不能替代完整素材页面、导出物及 A05 全量回归。
- 富文本与扩展市场、节点类型及 canvasNode 节点正文实测待 M3-04/05；当前节点列表为空，未新增节点类型或模型能力。
- `mcp/control/events` 的 403 与 capabilities/ext 的 404 属未接通范围（M3-05/06/07），本批不宣称已清理或开放。
- Windows 原工作区仍缺安全目录句柄，相关操作 503；未做不安全降级。单 Spring 写入者与文件系统/审计非原子事务的限制不变。
- 未测试移动视口；未更新正式部署、正式库或源路由 publicEnabled。

重跑命令见[后端 README](../../backend/README.md#m3-01-原页面修订链接收)与[验证脚本](scripts/README.md#m3-01-原页面浏览器验收)。当前任务和下一步见[TASKS](../plan/TASKS.md)与[交接入口](../handoff/README.md)。
