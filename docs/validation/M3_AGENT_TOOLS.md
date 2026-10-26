# M3-04 Agent 工具循环、画布控制与会话原格式

> 2026-10-26。范围：NDJSON 事件顺序、问答与工具回调、画布控制（页面执行）、单 Agent 内部生图不重复占位、会话改为 `.jsonl` 原格式并兼容旧 `.json`。仅使用合成账号、夹具项目与合成文本供应商；未调用真实模型。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| NDJSON 顺序 | 通过：`session`/`userMessage` → `text`/`tool`/`canvasCall`/`question` → `done`；错误以 `error` 收尾，取消收尾为 `已停止生成` |
| 工具回调 | 通过：模型工具调用 → 服务端发出 `tool`（running→success/error）→ 结果作为 `tool_call_id` 消息回填 → 下一轮继续生成；未知工具 400 `TOOL_UNKNOWN` |
| 画布控制 | 通过：18 个画布操作（与 `@toonflow/tool-canvas` 同名同参数）由页面校验并执行，服务端只发 `canvasCall`、等 `/canvasResult` 回执，超时 120 秒；无激活画布时拒绝调用 |
| 问答 | 通过：`askQuestion` 发出 `question`，页面 `/answer` 回传（含取消/跳过），超时 300 秒；`callId` 不属本项目返回 404 |
| 取消 | 通过：`/cancel` 立即唤醒等待中的画布/提问，本次生成以 `error` 收尾，不写入半成品消息 |
| 名额不重复占位 | 通过：文本会话本身不占名额；`generateImage` 工具只创建一次生成运行（`agent-<callId>` 幂等键），HTTP 入口不被重复调用 |
| 会话原格式 | 通过：新会话为 `.jsonl`（`session` 头 + 逐条 `message`），旧 `.json` 仍可读取；工具调用与结果作为 `parts[].tool` 持久化 |
| 首屏 404 | 修复：首次进入项目尚无 `.agent/sessions` 时，会话列表按空处理，不再把 404 暴露给页面 |

## 2. 实现要点

- **工具目录固定在服务端**（`agent/canvas-tools.json` + `AgentToolCatalog`）：浏览器不提供工具定义，避免页面伪造函数签名；画布未激活时不下发画布工具。参数 schema 与前端 zod 一致，页面仍做最终校验。
- **`LlmClient.converse`**：一轮对话内边流式输出文本边累积 `tool_calls` 片段，结束后返回 `Turn(text, calls)`；`chat`/`LlmStream` 保留用于纯文本调用。消息支持 `assistant.tool_calls` 与 `tool` 角色，便于多轮工具对话续接。
- **等待编排**：`pending` 以 `callId` 索引并绑定项目与会话，`/cancel` 会完成所有等待项；等待期间每 200 ms 检查取消标志，避免线程长期挂起。
- **内部生图**：`MediaGenerationService.generateInternal` 复用同一套校验、幂等与五名额逻辑（`admit`），同步返回生成件；流式 HTTP 入口与内部入口共用 `execute`，仅结果消费方式不同。

## 3. 验证证据

| 层次 | 内容 | 证据 |
| --- | --- | --- |
| 后端 | 104 项默认测试（新增 5 项：画布回执、跨项目拒绝、提问回填、内部生图单次占位、取消唤醒） | Linux `mvnw verify` + Spotless + JAR |
| 前端 | 26 项测试 102 断言、类型、边界与生产构建 | `bun run check` / `bun run build` |
| 浏览器 | 9 项：进入画布工作区、发起提问、工具卡片渲染、回复引用真实画布字段、供应商两次请求一次工具结果、`.jsonl` 列表与持久化、工具结果为真实画布数据、无未捕获错误 | [m3-m04-browser.json](evidence/m3-m04-browser.json) + [截图](evidence/m3-m04-canvas-tool.png) |

浏览器验收使用 `WorkspaceBrowserAcceptanceHarness`（真实 Spring 入口与工作区）+ `m3M04LlmMock.py`（首轮返回 `getCanvas` 工具调用、次轮回显工具结果）+ `m3W03DevServer.mjs` 静态代理，脚本 `m3M04AgentToolsCheck.mjs`。页面返回的真实画布数据为 `{"canvasId":"画布1.json","nodeCount":0,"edgeCount":0,"selectedCount":0,"viewport":{...}}`。

## 4. 未覆盖

- **单 Agent 委派（subAgent）**：`/singleAgent/*` 路由（创建、运行、工具结果、控制）仍在 M3-06/M3-07 范围，本批未实现；前端 `subAgentEvent` 分支保留。
- **技能与节点函数**：`skills`、`tools/*`、`nodes/get` 等能力接口未接通，页面相关菜单仍返回 403；属 M3-05（内置节点/工具/技能）。
- **真实模型**：仅用合成供应商验证协议与编排；真实 DeepSeek/Qwen 文本模型需要团队密钥，未在验收中调用。
- **上下文压缩、统计与用量**：`compaction`/`stats` 事件未实现，会话 token 统计不展示。

## 5. 环境清理

夹具容器、合成文本供应商、静态代理与回环转发均已停止；未写入用户项目或真实存储，未启动/停止用户的 ComfyUI 或其他服务。
