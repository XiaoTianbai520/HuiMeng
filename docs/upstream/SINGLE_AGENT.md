# 上游单 Agent（singleAgent）原版行为

> 基于固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d` 只读核对，未运行。
> 目的：在决定「我们是否要迁」之前，先看清它到底解决什么问题、边界在哪。

## 1. 它是什么

一个**可复用的「带工具的临时 Agent 会话」**：调用方开一个会话，指定要哪些工具、可选的系统提示词和工作目录，然后发一轮 prompt，服务端用 NDJSON 把思考/正文/工具调用流式推回来，前端负责渲染和（可选的）执行客户端工具。

当前**唯一的产品入口**是「AI 生成媒体供应商」：在添加自定义媒体供应商时，用这个会话让 Agent 自己浏览接口文档、提取协议、生成 provider `.ts` 源码。

关键定位差异：它不是「聊天助手」，而是一个**用完即弃的任务执行器**。会话存在进程内存里，关闭弹窗就销毁。

## 2. 接口

| 路由 | 作用 |
| --- | --- |
| `POST /api/singleAgent/create` | 建会话：`tools`（最多 128 项）、可选 `directory`、可选 `systemPrompt` |
| `POST /api/singleAgent/run` | 跑一轮：`sessionId/runId/prompt/providerId/modelId/thinkingLevel`，返回 NDJSON |
| `POST /api/singleAgent/toolResult` | 客户端工具回报结果，支持 `partial` 增量更新 |
| `POST /api/singleAgent/answer` | 回答 `askUser` 提问 |
| `POST /api/singleAgent/control` | `pause` / `resume` / `stop` / `close` |

四个写接口都先走 `mcpControl.assertAppRequest(req)`——**只允许应用自身调用**，外部 MCP/A2A 客户端拿不到这个能力。

## 3. 工具装配（三种形态）

`create` 的 `tools` 数组每项可以是：

1. **插件名字符串**：`"browser"` → 载入该已安装工具插件，取它 `createTools` 返回的全部工具；未安装/未启用直接 400，`loadError` 非空直接抛错。
2. **`{plugin, names}`**：只取该插件里的指定工具，名字不存在则 400。
3. **内联描述符**：`{name, label, description, parameters, executionMode}` → 变成**客户端工具**，服务端不执行，只发 `clientTool` 事件等前端回填。

装配时会做三件事：工具重名拒绝；把插件元数据的 `prompt` 与工具自带的 `promptGuidelines` 收集起来；插件配置经 `validateToolConfig` 校验后才传入。

「AI 生成供应商」这一处的实际组合是：`["browser", ("askUser"？), submitTool(客户端工具)]`。

## 4. 运行时语义

| 项 | 实现 |
| --- | --- |
| SDK | `@earendil-works/pi-coding-agent`，每轮新建 `AgentSession` |
| 压缩 / 重试 | **关闭**（`compaction.enabled=false`、`retry.enabled=false`） |
| 技能 / 提示模板 / 主题 / 上下文文件 / 扩展 | 全部不加载（`noSkills/noPromptTemplates/noThemes/noContextFiles/noExtensions`） |
| 系统提示词 | 调用方给的 `systemPrompt`，为空则一句默认「请根据用户要求完成当前任务，按需使用提供的工具。」；末尾拼接各工具 `promptGuidelines`（去重） |
| 并发 | 同一会话同时只能跑一轮，第二轮 409 |
| 工作目录 | 给了 `directory` 就用它（不清理）；没给则在 `conf.path/singleAgent/<id>` 建私有目录，`close` 时删除 |

## 5. 暂停/恢复是「边界级」的

不是打断模型，而是在**工具执行与流式请求的边界**上挂闸门：

- `activeActions` 计数正在进行的动作；
- `pause` 只置 `pauseRequested=true`，动作还在跑时状态是 `pausing`，跑完变 `paused`；
- 下一个动作开始前 `waitForResume` 卡在 gate 上；
- `resume` 放行；`stop` 直接 abort 并等 `finished`。

状态机：`idle → running → pausing → paused → running`。

## 6. 事件流（NDJSON）

`text` / `thinking` / `tool` / `toolResult` / `status` / `complete`，外加两类交互事件：`clientTool`（要前端执行）与提问事件。服务端每秒写一个空行做心跳，避免反向代理掐断长连接。

图片类工具结果会额外留存（`imageToolResults`），供会话内附件引用。

## 7. 浏览器工具与临时会话绑定

`getBrowserScope` / `assertWorkspaceBrowserAccess` 两个校验把浏览器会话锁到「这个临时对话 + 这个 cwd + 这个 history session」上：别的会话不能接管，未绑定的调用直接 403。

前端在右侧给一个可交互的浏览器画面，**暂停后允许人工点击/登录**，因为 Agent 不允许代填密码、验证码或读取 Cookie。

## 8. 与「主 Agent / 画布对话」的区别

| | 单 Agent | 主 Agent（画布对话） |
| --- | --- | --- |
| 持久化 | 内存 Map，关闭即销毁 | `.jsonl` 会话文件，可恢复 |
| 工具来源 | 调用方按需指定，可内联客户端工具 | 固定的画布操作 + 内置生成 + 已装工具包 |
| 技能 | 不加载 | 已启用技能正文注入 |
| 压缩 | 关闭 | 有 |
| 入口 | 目前只有「生成供应商」 | 画布工作区 |
| 外部可调用 | 否（`assertAppRequest`） | MCP/A2A 有各自入口 |

## 9. 如果我们要迁，需要先定的事

1. **产品面**：只做「生成供应商」，还是做成通用的临时 Agent 容器（可复用给别的场景）？前者工作量小得多。
2. **客户端工具通道**：`clientTool` + `toolResult` 这套「服务端等前端执行」的机制，我们现在的 Agent 是「服务端下发画布指令、页面回填」，已经等价，可复用。
3. **浏览器能力**：原版依赖真实浏览器会话语义（可人工接管）。我们目前没有浏览器工具，这块要么不做，要么单独立项。
4. **会话生命周期**：内存态在多实例部署下不成立，需要落到 Redis 或复用现有 `.jsonl`。
5. **暂停语义**：我们要不要做「边界级暂停」，还是只做「停止」——原版的 `pausing/paused` 双态是为人工接管浏览器服务的，没有浏览器就不必这么复杂。
