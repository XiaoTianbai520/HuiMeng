# Toonflow 固定提交 Web 功能基线

> 固定提交静态与运行核对，更新至 2026-10-22。上游参考克隆固定为 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`。页面、文件和 MCP 的实际结果见 [M0 报告](../archive/m0/M0_REPORT.md)，现用 Qwen/H3、DeepSeek、Agent、协议与插件市场的 M0 基线均已完成；当前结论见[最终收尾](M0_FINAL.md)。接口明细见 [路由台账](../api/route-inventory.md)。

## 1. 页面路由与功能

| 原版 Web 路由 | 源码证据 | 原版可见能力 | 1.0 处理与待验证点 |
| --- | --- | --- | --- |
| `/#/` → `/#/hello` | `apps/web/src/router/index.ts` | 首次使用引导；按状态跳转首页 | 改为内部账号登录和首登状态；验证未登录、过期与退出 |
| `/#/hello` | `apps/web/src/pages/hello/index.vue` | Toonflow 引导、TF-Router 登录、私有提供商/设备相关入口 | 移除 TF-Router 登录及设备互联；管理员统一设置提供商；核对其余引导能力 |
| `/#/home` | `apps/web/src/pages/home/index.vue` | 选本机工作目录、提示词创建项目、项目列表、添加项目和附件 | 由服务端创建项目并分配项目 ID；入口只显示本人有权项目；保留提示词创建与附件体验 |
| `/#/canvas` → `/#/workspace` | `apps/web/src/router/index.ts` | 旧画布地址跳转 | 保留兼容跳转，登录后进入有权项目 |
| `/#/workspace` | `apps/web/src/pages/workspace/index.vue` | 画布/文档面板切换、节点工具、浮动 Agent、设置、MCP 控制 | 保留创作界面；所有操作绑定当前项目；无权项目不能通过旧 URL 打开 |

页面没有独立的角色、场景或素材顶层路由；这些能力位于工作区画布节点、资产库及文档面板中。不能只按顶层路由数量缩减迁移范围。

供应商更新（2026-10-22）：当前使用 h3Local3060 2.0.0 和 qwenImage21Local 1.0.0，见[现用清单](LOCAL_PROVIDERS.md)。下表旧 Qwen 取证仅对应历史适配器；现用 Qwen 两模型的 1K Web 生成、落盘及刷新预览已独立通过，见[最终收尾](M0_FINAL.md)。

## 2. 创作链路核对

| 链路 | 已确认的源码行为 | 1.0 迁移条件 | 状态 |
| --- | --- | --- | --- |
| 项目创建与选择 | `pages/home/index.vue`、`stores/workspace.ts` 使用本地 `directory`；新建时写 `画布1.json` | 项目 ID 映射服务端目录；创建、列出、切换需权限校验 | 页面创建、打开已录制 |
| 画布与节点 | `pages/workspace/panels/canvas/index.vue` 读写 `{toonflowCanvas:true,nodes,edges,viewport}`；`canvasFiles.ts` 检查节点 ID、边并复制节点素材 | 保留 JSON 结构、节点及素材路径语义；保存增加版本检查 | 文本节点创建、生成、自动保存、Agent 新增节点和刷新重开已录制，见[补测](../archive/m0/M0_CLOSEOUT.md) |
| 剧本/文档 | `panels/document/index.vue` 提供文件树、搜索、快速打开、编辑器；`documentSession.ts` 以文本快照读写 | 保留编辑、搜索、编码、分屏与文件操作；授权按项目 | 编辑、保存及 409 冲突已录制；搜索端到端回归纳入 M3-01/M4 |
| 素材库 | `panels/canvas/components/assetLibrary.vue` 调用工作区文件列出、创建目录、读写；画布复制会处理 `assets/{nodeId}` | 保留素材引用与复制行为；上传/下载限制到项目根 | PNG 接口写读、复制和列出已录制 |
| 文本及媒体生成 | `packages/nodeScaffold/src/nodeAi.ts` 调用 `/api/ai/generate`、`/api/ai/media/generate`、模型与报价接口 | 文本 SSE、媒体 JSON 保活语义不变；图/视频/音频按项目 5 名额 | [现用 Qwen 文生及编辑](M0_FINAL.md)、[现用 H3 三模式及 Web 播放](../archive/m0/M0_CLOSEOUT.md#3-新-h3-实际出片)、[DeepSeek 文本与 Agent](../archive/m0/DEEPSEEK_BASELINE.md)已取证；独立音频本轮不测 |
| Agent | `components/agent/` 调用会话、问答、画布结果等接口；服务端 `/api/agent` 返回 NDJSON | 保留会话和回调顺序；一次 Agent 运行计 1 名额 | [DeepSeek 基本会话、只读工具、取消与继续](../archive/m0/DEEPSEEK_BASELINE.md)已录制；页面 getCanvas/addNode 与回调亦已通过，见[补测](../archive/m0/M0_CLOSEOUT.md) |
| FFmpeg 与导出 | `packages/ffmpeg/src/browser.ts` 调用 `/api/ffmpeg/execute`；`lib/saveFile.ts` 有普通浏览器 Blob 下载分支 | 保留媒体处理和浏览器导出；移除桌面/移动专属分支 | 已补 FFmpeg 转码/进度/取消/输出下载；素材库浏览器下载与原字节匹配，见[补测](../archive/m0/M0_CLOSEOUT.md) |
| 设置与扩展 | `components/settings/index.vue` 包含模型、MCP、插件市场和设备页；插件市场还承载已安装能力管理 | 管理员统一密钥配置；保留在线市场与已安装能力管理，移除设备页；市场凭据改后端管理 | 在线发现/安装、文件更新、配置启停、导出卸载、凭据错误与断网已取证；已安装技能说明可查看，见[市场报告](../archive/m0/M0_PLUGIN_MARKET.md) |

## 3. 已发现的迁移差异

1. **文档已有修订保护**：`lib/workspaceFiles.ts` 的 `readTextSnapshot()` 向 `GET /api/workspaces/files/read` 传 `snapshot=true`，上游返回 `{text,encoding,revision}`，其中 `revision` 为文件原字节 SHA-256。`writeTextSnapshot()` 将旧值作为 `expectedRevision` 提交；服务端不匹配时返回 409/`ESTALE`；`documentSession.ts` 将其标记为冲突。
2. **画布缺跨客户端保护**：`panels/canvas/index.vue` 的自动保存及节点文本写回均调用 `writeJson()`，未提交 `expectedRevision`。源码注释也明确只保证同页保存顺序。1.0 须在读取画布时取得修订号，所有画布写入都附带旧修订号，更新成功后保存新修订号；409 时暂停对该文件的后续自动覆盖并给出重新载入/人工处理入口。原 JSON 正文不增加版本字段。
3. **工作区路径是授权边界**：原客户端会传本机 `directory`。1.0 Web 只传项目 ID 与项目相对路径；Spring 映射目录并校验权限，Bun 不接受客户端决定的绝对路径。
4. **导出有三个平台分支**：`lib/saveFile.ts` 分别走移动暂存、桌面保存对话框、普通浏览器 Blob 下载。1.0 只需浏览器分支，仍须回归文件名、类型及下载完成。
5. **127 条不是所有 HTTP 入口**：`/mcp`、`/a2a` 另挂；桌面/移动应用的 `/api/connection/*`、`/api/mobile/exports*` 也在自动路由外。详细边界见[路由台账 §3](../api/route-inventory.md#3-自动路由以外的入口)。

## 4. 后续回归所复用的原版样例

| 序号 | 基线行为与后续回归内容 | 对应验收 |
| --- | --- | --- |
| B01 | 新建样例项目；打开画布、增加节点/连接、保存、刷新、重新打开；保存 JSON 与 `assets/` 样例 | A04、A06 |
| B02 | 文档新建、编辑、搜索、保存；两个页面用旧修订号复现 `409/ESTALE`；保存请求与响应 | A04、A05 |
| B03 | 文本生成 SSE 全部事件、图片/视频/音频生成最终 JSON 与保活；本地 ComfyUI 样例 | A07、A08 |
| B04 | Agent 会话、问答、画布操作与取消；逐行 NDJSON | A09 |
| B05 | FFmpeg 处理、素材读写复制、浏览器导出文件 | A06、A10 |
| B06 | MCP 工具发现/调用；A2A Agent Card、提交/查询/取消与错误 | A13 |

固定提交的 `package.json` 要求 Bun `1.3.14`，本机只有 `1.4.2`。已以固定提交副本启动原版 Web、连接 ComfyUI 并保存页面/网络样例；2026-10-22 已通过 Ubuntu 宿主机上 Bun 1.3.14 完整构建及产物启动，并补齐 Web 文本/Agent、H3 三模式、FFmpeg、MCP 工具和 A2A，见[收尾记录](../archive/m0/M0_CLOSEOUT.md)。原版单人画布本身不会产生预期的跨客户端 409，B02 的画布冲突应在 1.0 实现后验证。
