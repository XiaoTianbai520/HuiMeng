# M3-06 MCP 工具调用、A2A 团队任务与项目隔离

> 2026-10-26。范围：`/mcp` 的 MCP 协议子集、`/a2a/{team}` 的 A2A 1.0 团队任务、外部凭证的单项目边界、页面控制桥、跨入口项目的成员判定。夹具使用 H2 与合成凭证；A2A 团队任务只调用合成文本供应商，不调用真实模型或媒体执行器。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| MCP 握手 | 通过：`initialize` 返回单帧 SSE 与 `Mcp-Session-Id`，`serverInfo.name=huimeng`，`tools.listChanged=false`；`GET /mcp` 返回 405，`DELETE /mcp` 返回 204 |
| 凭证边界 | 通过：无凭证 401 并带 `WWW-Authenticate: Bearer`；伪造令牌 401；未授权工具在分派前 403 `CAPABILITY_FORBIDDEN` |
| 工具目录 | 通过：只返回凭证已授权工具；`cancelRun`/`generateMedia`/`writeDocument` 不出现在无权客户端的 `tools/list` |
| 服务端工具 | 通过：`listWorkspaceFiles`、`readWorkspaceFile`、`saveWorkspaceFile` 走真实工作区；独占新建返回 64 位修订号；覆盖缺修订号按冲突拒绝；`../外部协议隔离/私有.md` 不可达 |
| 页面控制桥 | 通过：外部调用经同项目已连接页面执行；页面未连接时 `getCanvas` 返回可读错误而非伪造结果；页面状态用 revision 丢弃乱序旧值 |
| A2A 入口 | 通过：Agent Card 指向当前 Spring 入口（不保留上游 Bun 地址），`supportedInterfaces[0].protocolVersion=1.0`，`securitySchemes.bearer` 存在 |
| A2A 版本头 | 通过：缺 `A2A-Version: 1.0` 返回 400 `VERSION_NOT_SUPPORTED`，并保留请求关联 id |
| A2A 任务 | 通过：`SendMessage` 返回 `{"task": ...}`，`GetTask` 读取终态与 artifact，`CancelTask` 生效；任务与客户端、项目绑定，跨客户端按未找到处理 |
| 凭证撤销 | 通过：撤销后同一令牌立即 401，已建任务仍不越权可见 |
| 跨入口隔离 | 通过：外部主体只能读绑定项目；Beta 客户端即使同团队名授权也读不到 Alpha 项目文件与任务 |

## 2. 实现要点

- **边界在分派前**（`ExternalBoundaryFilter`）：一次性读取请求体（上限 64 KiB），先解析令牌得到单项目 `ExternalActor`，再按 MCP 工具名单或 A2A 团队名单判定；拒绝时仍按 JSON-RPC 结构回错，保留请求 `id`，并写入 `X-Request-Id` 与 `Cache-Control: no-store`。控制器与请求体都不参与身份/项目判定。
- **工具目录固定在服务端**（`McpToolServiceImpl`）：18 个画布工具与 8 个服务端工具（`listWorkspaceFiles`、`readWorkspaceFile`、`saveWorkspaceFile`、`listMedia`、`generateMedia`、`getRun`、`cancelRun`、`listTeams`）统一按凭证能力裁剪，浏览器无法注入工具定义。
- **页面控制桥**（`McpControlServiceImpl`）：页面经 `GET /api/v1/projects/{id}/creative/mcp/control/events` 建立 SSE，外部调用以 `callId` 推送；页面用 `POST .../control/state` 上报工作区快照（带 revision）、用 `POST .../control/result` 回传结果。同一项目新连接替换旧连接，超时与未连接都返回明确错误。
- **A2A 任务归属**（`a2a_tasks`，`V6__external_protocols.sql`）：任务记录 `client_id` 与 `project_id`，查询/取消都按两者过滤，不依赖调用方自报的项目或团队；团队名相同但项目不同不可见。
- **外部主体复用既有服务**：`ProjectServiceImpl.member` 对绑定项目放行外部客户端，`MediaGenerationServiceImpl` 支持 `EXTERNAL` 主体并提供 `start` 异步入口，避免为协议另开旁路。
- **安全链**：`/mcp`、`/a2a` 由独立 `SecurityFilterChain` 管到 `authenticated()`（凭证校验在过滤器内完成）；能力目录端点只读，供页面展示协议状态（`/api/v1/capabilities/mcp/status` 返回协议版本、连接数、工具总数）。

## 3. 验证证据

| 层次 | 内容 | 证据 |
| --- | --- | --- |
| 后端 | 113 项默认测试（含外部协议边界、A2A 归属、撤销、跨项目拒绝） | `mvnw verify` + Spotless + JAR（BUILD SUCCESS） |
| 前端 | 26 项测试 102 断言、边界、类型 | `bun run check` |
| A13 外部协议 | 54 项：握手/会话头/目录裁剪/服务端文件工具/路径穿越/未授权拒绝/卡片/版本头/任务提交查询取消/跨客户端与撤销 | [m3-m06-external.json](evidence/m3-m06-external.json) |
| 浏览器 | 11 项：页面连接、`getAppState` 真实项目、`getCanvas` 真实节点计数、`addCanvas` 建画布并在菜单出现、`switchCanvas` 切回、无未捕获错误 | [m3-m06-browser.json](evidence/m3-m06-browser.json) + [截图](evidence/m3-m06-mcp-canvas.png) |

夹具为 `ExternalProtocolHarness`（H2、随机主密钥、两项目两凭证）容器内运行于 `127.0.0.1:18099`，经 SSH 隧道映射到 `127.0.0.1:18086`；页面侧用 `m3W03DevServer`（Vite 开发服务器，`HUIMENG_API_TARGET=http://127.0.0.1:18086`）。A2A 任务的文本来自 `m3M06LlmMock.py`（OpenAI 兼容流式响应，端口 18140）。

复跑命令（容器内）：

```bash
HM_EXTERNAL_PROTOCOL=true MAVEN_USER_HOME="$PWD/.m2" \
  sh ./mvnw -s .mvn/settings.xml -B -ntp '-Dtest=ExternalProtocolHarness' test
```

```bash
M306_PORT=18086 M306_READY=backend/target/external-protocol.ready \
  M306_EVIDENCE=docs/validation/evidence/m3-m06-external.json \
  python docs/validation/scripts/m3M06ExternalCheck.py

PLAYWRIGHT_CORE_PATH=<playwright-core> HM_BROWSER_ORIGIN=http://localhost:5187 \
  HM_MCP_ORIGIN=http://127.0.0.1:18086 HM_BROWSER_PROJECT=<alphaProject> \
  HM_MCP_TOKEN=<alphaToken> node docs/validation/scripts/m3M06BrowserCheck.mjs
```

## 4. 未覆盖

- **真实传输层**：验收走本机 HTTP 与 SSH 隧道，未在本轮复验团队 HTTPS 证书与浏览器信任；部署侧 HTTPS 结论仍以 M2 记录为准。
- **服务端主动推送**：`GET /mcp` 明确 405，客户端只走 POST；MCP 通知（progress、resources）未实现。
- **A2A 流式事件**：只实现 `SendMessage`/`GetTask`/`CancelTask` 与终态 artifact，未实现 `message/stream` 的增量事件。
- **`generateMedia` 外部入口**：只做能力裁剪与幂等键校验，未在外部路径跑真实视频/图片生成；跨入口容量仍在 M4-02。
- **单 Agent 委派与技能接口**：`/singleAgent/*` 仍待 M3-07/M3-08 收口，本批未实现。
