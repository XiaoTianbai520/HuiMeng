# 插件运行期收口：本轮五批改动

> 2026-10-26，起点基线 `8b91541`。范围：交接清单「插件加载与使用」的 1～5 项，外加内置节点下线清理与音频入口语义。真实市场凭据复跑（第 6 项）未做，需团队提供 TF-Router 子账号。

## 1. 批次与提交

| 批次 | 内容 | 提交 |
| --- | --- | --- |
| 1 | 内置 textNode/imageNode 入库，启动按摘要幂等登记为 `builtin` | `9e53027` |
| 2 | `renderers` 按包头 `components` 发布，`tools/client` 下发内嵌 `code`+`css`；内置节点下线清理 | `0107210` |
| 3 | 已启用技能正文注入 Web Agent 提示词 | `840e120` |
| 4 | 市场在线安装：字段映射、服务端目录回查、逐跳来源校验、获批摘要 | `aed7a02` |
| 5 | 进程内工具执行器（GraalJS）；音频入口显式拒绝 | `afa7146` |

## 2. 工具界面契约（批次 2）

对齐上游源码，而不是沿用文档推测：

- 第一行 `/*! toonflowTool:{...} */` 是元数据，`components` 为**真实工具名**列表（`^[a-z][a-zA-Z0-9_]*$`，≤100、不重复）；为空表示纯 Agent 工具。
- 第二行 `/*! toonflowToolClient:{"code","css"} */` 才是浏览器界面。`tools/client` 下发 `code` + 一次性样式注入（`style` 元素 id 固定为 `toonflowToolStyle:<name>`）。
- 早先实现把整个 `.tool.js`（ESM 正文）当界面脚本下发，浏览器必然解析失败；`renderers` 又把包名当工具名，页面匹配不到。

## 3. 技能注入（批次 3）

`CapabilityService.skillPrompt()` 读取已启用技能的 `SKILL.md`（缺失回退第一个 `.md`），单条 6000、合计 20000 字符上限，`AgentServiceImpl.systemPrompt()` 追加「可用技能」段；停用即消失，不向客户端暴露路径，`references` 不进提示词。

## 4. 市场在线安装（批次 4）

| 项 | 实现 |
| --- | --- |
| 字段映射 | `identifier`→安装名、中文 `name`→显示名、`supplier`/`desc`→作者/说明；兼容 `data.list` 与 `data.items`（真实返回是 `list`，测试先暴露了这个差异） |
| 可安装判定 | 类型、文件名规范、`state`、下载来源四项；不可装时返回 `reason`，页面直接显示 |
| 请求体 | 只接受 `{pluginId,force?}`；多带 `url`/`sha256` → 400 |
| 地址来源 | `V8 market_catalog` 落库，安装按 `plugin_id` 回查，从不采信请求体 |
| 主机白名单 | 默认 `api.toonflow.net,toonflow.tos-cn-beijing.volces.com`；最多 5 跳逐跳校验，签名 302 可跟但每跳都须在清单内 |
| 摘要 | `fileHash` **不作**校验依据；首次下载记录字节 SHA-256 为获批摘要，之后不一致即 409 |

## 5. 工具执行器（批次 5）

选型：**JVM 内 GraalJS**，不引入 Node/Bun sidecar。

隔离与限额：`HostAccess.NONE`、`IOAccess.NONE`、禁止宿主类查找/建线程/原生访问/读环境；语句上限 2000 万、墙钟 20 秒、结果 10 万字符；每次调用新建上下文，用完销毁。

支持：单文件、无模块引用的工具包（`export default {validateConfig, createTools}`，`execute` 可为 async）。参数以 JSON 进入、结果按 Promise 收敛回宿主。Web Agent 工具清单追加已装工具包并按名字分派；与内置工具重名则跳过。

不支持：任何 `import`/`export ... from`/`require`。真实市场把 `zod` 外置为 `toonflow:tool-zod`，这类包加载期被跳过并记日志；补它需要在上下文里注入该虚拟模块（把 zod 打成单文件 ESM），未做。

代价：JAR 增加约 59 MB（`js-language` 25、`icu4j` 16.9、`truffle-api` 12.3 等）。试过排除 `icu4j`，但它是 `js-language` 的传递依赖，独立排除无效，改为关闭 `js.intl-402`。

## 6. 音频入口（批次 5）

`mediaType=audio` 在占名额前返回 409 `MEDIA_AUDIO_UNSUPPORTED`，不再被当成图片处理。1.0 没有音频供应商，按 PRD 只保留语义、不做成功流。

## 7. 验证

| 项 | 结果 |
| --- | --- |
| 后端全量 | `mvnw verify` **143 项通过**（起点 132），Spotless 干净 |
| 前端全量 | `bun run check` **26 项通过**（含三个节点包 `vue-tsc`） |
| 内置节点浏览器 | 17/17；QA 独立 43 项（其中 3 项为 Windows 平台限制，见 [M3_BUILTIN_NODES](M3_BUILTIN_NODES.md)） |
| 重新构建产物 | `bun run build:nodes` 字节与原产物一致 |
| 可执行 JAR | 两个节点产物在 `BOOT-INF/classes/capabilities/nodes/` |

新增/更新的测试类：`BuiltinNodeTests`（7）、`CapabilityCatalogTests`（7）、`PluginMarketProxyTests`（5）、`ToolRuntimeTests`（3）、`AgentServiceImplTests`（10，含市场工具分派）。

## 8. 仍未做

1. **真实市场凭据复跑**与 M0-I12～I14：需 TF-Router 子账号。
2. **`toonflow:tool-zod` 虚拟模块**：真实市场工具包依赖它；补上才能执行真实制品。
3. **MCP 工具清单**：仍硬编码，未接 `ToolRuntime`。
4. **自定义媒体供应商**（`.ts` provider 文件）：需要同一套 JS 执行加媒体客户端桥接，未做。
5. **A2A 流式事件与服务端推送**：仍是 SendMessage/GetTask/CancelTask 三件套。
6. **M3-S01** 原素材页/导出物、FFmpeg 暂存链路。
7. **画布内渲染**的 Linux 复跑；其余 6 个上游节点。
