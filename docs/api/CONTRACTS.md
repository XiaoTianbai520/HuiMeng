# 绘梦 1.0 接口与数据契约（CONTRACTS）

> **状态：M1 冻结契约；当前 49 操作/42 路径中 41 有实现。** M2 当前接通范围的开发/隔离验收完成，生成仅固定 Qwen 文生图 1K/1:1，正式部署未升级，完整创作/安装/协议继续 M3。字段以[OpenAPI](openapi.yaml)和[冻结记录](M1_CONTRACT_REVIEW.md)为准；127 条映射中 13 条 frozen、87 条兼容细项仍为 proposed，源台账 publicEnabled 全部 false。见[M2 最终验收](../validation/M2_ACCEPTANCE.md)。

## 1. 版本、域名与认证

| 类型 | 1.0 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| 平台业务 API | `/api/v1/**` | 内部账号会话 Cookie | 新增账号、项目、管理员能力 |
| 项目创作兼容 API | `/api/v1/projects/{projectId}/creative/{upstreamPath}` | 内部账号会话 Cookie | 仅在逐条白名单中的原 Web 路由；隐藏 `directory`，不做任意通配转发 |
| 外部 MCP | `/mcp` 及上游协议要求的子路径 | 专用 Bearer 凭证 | 仅供局域网获授权客户端访问；原 MCP 协议、工具名和事件以 M0 样例为准 |
| 外部 A2A | `/a2a/{teamName}` 及协议子路径 | 专用 Bearer 凭证 | 仅供局域网获授权客户端访问；原 A2A Agent Card、任务提交/查询/取消以 M0 样例为准 |
| Bun 内部 API | 内网地址，仅 Spring 可访问 | 服务身份 + 已验证项目上下文 | 不属于公开契约 |

所有对局域网开放的入口经 HTTPS，不发布到公网。会话接口需要 CSRF 防护；外部 Bearer 凭证不得放 URL、浏览器 LocalStorage 或日志。一个外部客户端凭证在 1.0 绑定一个项目及一组允许的 MCP 工具/A2A 团队；这是一项**新约定**，上游全局 token 不再对外使用。

### 1.1 通用头与标识

| 项 | 约定 |
| --- | --- |
| `X-Request-Id` | 可由调用方传入；服务端生成或规范化，响应回传，用于日志关联 |
| `Content-Type` | JSON 请求为 `application/json`；原始文件上传为 `application/octet-stream`；流式按对应协议 |
| `Idempotency-Key` | 计名额的生成/Agent 新运行请求必填；同调用方、同项目、同操作重复请求返回同一运行或明确状态，内容不一致返回冲突 |
| `projectId` | 项目 API 路径中的不透明 ID；服务端映射目录。客户端不得提交服务器绝对路径 |
| 时间 | ISO 8601 UTC；文件字节与上游项目 JSON 内容不被网关转码 |

### 1.2 1.0 新增错误结构

平台入口在**开始流式响应前**发生的校验/授权/限额错误统一返回：

```json
{"code":"PROJECT_FORBIDDEN","message":"无权访问该项目","requestId":"req_123","details":null}
```

| HTTP | code | 典型含义 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` / `PATH_NOT_ALLOWED` | 参数或相对路径非法 |
| 401 | `UNAUTHENTICATED` / `CLIENT_TOKEN_INVALID` | 会话或外部凭证无效 |
| 403 | `PROJECT_FORBIDDEN` / `CAPABILITY_FORBIDDEN` | 项目或工具/团队无授权 |
| 404 | `NOT_FOUND` | 不存在或不对该调用方可见 |
| 409 | `REVISION_CONFLICT` / `IDEMPOTENCY_CONFLICT` | 保存版本或幂等键冲突 |
| 429 | `PROJECT_CAPACITY_EXCEEDED` / `RATE_LIMITED` | 五名额已满或请求限速 |
| 502/503 | `EXECUTOR_UNAVAILABLE` / `RUN_LOST` | 内部执行器不可用或运行状态丢失 |

流一旦建立，不再插入新的 HTTP JSON 错误体；按该路由实际协议传送错误，并把运行记录置为失败。上游 Agent 在模型未配置时返回 HTTP 200 与 NDJSON `error` 行；媒体生成在响应头已发出后可返回 HTTP 200、JSON 正文 `code:404`。Spring 适配层须同时检查正文与传输状态，不能仅按 HTTP 200 判成功。上游普通成功响应不强制包裹在这个错误结构里，避免破坏原 Web 代码。[M0 原始失败样例](../validation/evidence/upstream-protocol-limits.json)与[成功流补测](../archive/m0/M0_CLOSEOUT.md)已保存；目标平台仍须解析实际业务终态。

### M2 本轮实现补充（2026-10-25）

源码新增精确文件 read 实现及管理员 GET `/api/v1/admin/configuration/export`。后者返回 `ConfigurationExport`（版本、导出时间、供应商状态、市场配置布尔值、模型批准标识），写导出审计；不返回任意 options、密钥、密文、端点或目录。文件快照上限 8 MiB、原字节下载上限 512 MiB，单 Range/416；实际打开要求安全目录句柄，Windows 不支持时返回 503。完整 schema 由 OpenAPI 定义，当前 28/40 个操作有实现。源码实现与正式发布不同：本轮未更新正式部署，127 条源台账 publicEnabled 仍为 false。验证和剩余范围见[M2 推进记录](../validation/M2_REMAINING_PROGRESS.md)。

## 2. 新增平台 API

### 2026-10-25 私有媒体对象 API 增补

本批新增四个独立平台操作，精确字段见 OpenAPI `ProjectMedia/ProjectMediaPage`。不替换原 workspaces/files 的快照或修订契约，不改变原创作源路由发布开关。

| 方法与路径 | 输入与成功响应 | 权限及限制 |
| --- | --- | --- |
| `POST /api/v1/projects/{projectId}/media?filename=...` | `application/octet-stream` 原字节；`201 ProjectMedia` | 成员、活动项目、CSRF/Origin；Content-Length 必填，0–512 MiB；禁止自动重试 |
| `GET /api/v1/projects/{projectId}/media` | cursor/limit；`ProjectMediaPage` | 当前成员；只含 READY；每页 1–50 |
| `GET /api/v1/projects/{projectId}/media/{mediaId}/content` | 原字节 attachment；单 Range 206/416 | 每次重新授权；媒体 ID 同时按项目查找；不重定向存储地址 |
| `DELETE /api/v1/projects/{projectId}/media/{mediaId}` | `204` | 成员、活动项目、CSRF/Origin；DELETING 失败可重试；上传中 409 |

ProjectMedia 仅含 id、projectId、filename、byteSize、sha256、createdAt；无桶、对象键、端点或凭据。文件名只用于显示，拒绝目录分隔、冒号、百分号、控制字符和点路径；不接收额外路径/URL/自报身份参数。缺少长度或无效输入 400、超限 413、存储失败 503，均使用既有错误码。失败记录不出现在成员列表；管理员受控核对残留状态，DB 与 S3 不声称原子提交。兼容页面接线仍属 M3。

以下方法、字段是**1.0 设计**，以 M1 的 OpenAPI 为精确字段和响应依据。所有会话写接口（含登录）要求 `X-CSRF-Token` 并校验同源 Origin；由 `GET /api/v1/auth/csrf` 取得预登录/当前令牌，登录后轮换。会话 Cookie、分页、默认配置见[冻结记录](M1_CONTRACT_REVIEW.md#2-平台通用规则)。外部 Bearer 请求不使用浏览器会话 CSRF。

### 2.1 账号与会话

| 方法与路径 | 请求 | 成功响应 | 权限 |
| --- | --- | --- | --- |
| `GET /api/v1/auth/csrf` | 无 | `{token}`，no-store，必要时设预会话 Cookie | 未登录/已登录 |
| `POST /api/v1/auth/login` | `{username,password}` | `{user:{id,username,role}}`，设置安全 Cookie | 未登录 |
| `GET /api/v1/auth/me` | 无 | `{id,username,role,status}` | 已登录 |
| `POST /api/v1/auth/logout` | 无 | `204`，会话失效 | 已登录 |

管理员账号接口：`POST /api/v1/admin/users` 创建、`GET /api/v1/admin/users` 列表、`PATCH /api/v1/admin/users/{userId}` 停用/启用或重置授权。请求和响应绝不返回 password_hash；初始密码仅通过受控渠道交付。公开注册接口不存在。

### 2.2 项目与成员

| 方法与路径 | 请求 | 成功响应 | 权限 |
| --- | --- | --- | --- |
| `GET /api/v1/projects` | 分页参数 | `{items:[{id,name,status}],nextCursor}` | 成员仅获授权项目；管理员可管理全部 |
| `POST /api/v1/projects` | `{name}` | `201 {id,name,status}` | 管理员 |
| `GET /api/v1/projects/{projectId}` | 无 | `{id,name,status}` | 项目成员可读；管理员仅有元数据管理权限 |
| `POST /api/v1/projects/{projectId}/members` | `{userId}` | `201 {userId,projectId}` | 管理员 |
| `DELETE /api/v1/projects/{projectId}/members/{userId}` | 无 | `204` | 管理员 |

任何响应不返回 `workspace_key` 或服务器 `absolutePath`。被移除成员的后续请求立即失效；已启动生成按运行清理策略处理，不得继续读取项目结果。

### 2.3 团队配置与外部凭证

| 方法与路径 | 请求 | 成功响应 | 权限 |
| --- | --- | --- | --- |
| `GET /api/v1/models` | 无 | `{items:[{providerId,modelId,capabilities}]}` | 已登录，仅获批模型 |
| `PUT /api/v1/admin/providers/{providerId}/secret` | `{apiKey}` | `204` | 管理员；Key 只写 |
| `GET /api/v1/admin/providers` | 无 | 仅返回标识、是否已配置、更新时间 | 管理员 |
| `POST /api/v1/admin/external-clients` | `{name,projectId,mcpTools:[],a2aTeams:[]}` | `201 {id,token,scope}` | 管理员；token 仅此响应可见 |
| `GET /api/v1/admin/external-clients` | 无 | 列表，不含 token 或 secret_hash | 管理员 |
| `POST /api/v1/admin/external-clients/{clientId}/revoke` | 无 | `204` | 管理员 |

当前本地测试模型标识以[供应商清单](../validation/LOCAL_PROVIDERS.md)为准：h3Local3060 的 5 项模型，以及 qwenImage21Local 的 qwenImage21Text/qwenImage21Edit。旧 localQwenImage21/qwen-image-2.1 已停用，不能继续作为默认请求样例；模型选择仍从安全模型列表查询。现用 Qwen 两模型的真实 Web 请求与成功响应已[独立归档](../validation/evidence/qwen-final-closeout.json)，包含正文 code 200、相对落盘路径和 JSON 保活空白；新平台事件契约回归仍待 M1/M3。

### 2.4 插件市场与管理

在线发现和已安装能力管理属于 1.0 现行范围。以下新增平台 DTO/OpenAPI 已在 M1 冻结，尚未实现后端；原插件管理兼容细项在 M3-08 细化。市场使用团队 TF-Router 账号，不要求团队成员个人账号；收藏和“我的”沿用团队市场账号语义，收藏变更限管理员。

| 方法与路径 | 请求 / 返回 | 权限 |
| --- | --- | --- |
| `POST /api/v1/admin/plugin-market/search` | Body `{page,limit,type,searchKeyword?}`；返回 `{items,total}`，安全目录 DTO | 管理员 |
| `POST /api/v1/admin/plugin-market/collections/toggle` | Body `{pluginId}`；返回 `{collected}`；上游为切换操作，网络异常不自动重试 | 管理员 |
| `PUT /api/v1/admin/plugin-market/secret` | Body `{apiKey}`；成功 `204`，只写不回显 | 管理员 |
| `GET /api/v1/admin/plugin-market/status` | 无请求参数；返回 `{configured}`，不含凭据 | 管理员 |
| `GET /api/v1/admin/plugins` | 安全已安装元数据，包含停用项；无配置密钥 | 管理员 |
| `POST /api/v1/admin/plugin-market/install` | `{pluginId,manifest:{type,name,version,sha256},force?}`；核对服务器获批清单 | 管理员 |

`page` 正整数；`limit` 为 1–50；`type` 沿用 `all/node/ext/skill/tool/agent/my/collection`，原版关闭的 Agent 市场安装入口保持关闭。`items` 保留 `id,type,name,displayName,author,description,version,fileName,isCollected` 等展示字段，并给出 installable/获批 manifest；内网目录、凭据、远端认证参数不返回。M0-06 已录制真实原响应 `{code:200,data:{list,total},message,id}` 与无效凭据 HTTP 401；M1 的安全 DTO 字段、缺省和可空性见 OpenAPI 与冻结记录，来源见[市场基线](../archive/m0/M0_PLUGIN_MARKET.md)。

上游源码已核对的外部调用为 `POST https://api.toonflow.net/v1/plugin/getPlugin` 和 `POST .../v1/plugin/toggleCollection`，使用 TF-Router Bearer；不是现有 127 条 Bun 自动路由。新 Web 只调用 Spring，凭据由 Spring 注入，不复用浏览器 settings 中的明文 Key。真实目录、分类、分页、搜索、收藏切换与恢复、官方文档技能下载已取证。

安装、保存、配置、启停、导出、卸载按原 `nodes/tools/skills/ext/agents` 实际支持的方法逐项映射到 `/api/v1/admin/...` 白名单，禁止作为项目创作通配接口透传。管理员可从市场选择插件或上传原版支持的本地包；市场安装先由后端核对目录项、下载地址、类型、版本和摘要，再交 Bun 安装。任意 URL 不因出现在目录中而自动可信；逐跳下载授权及归档校验见[设计](../architecture/DESIGN.md#81-插件市场迁移)。成员调用全部写管理接口返回 403；MCP/A2A 凭证不能安装插件或修改团队配置。

成员能力查询只返回批准且启用的运行元数据和必要客户端资源，配置密钥不回显；内置工具应随发布安装并真实加载。安装冲突、占用、坏包、超时、断网及更新失败均须有可识别错误且不破坏现有可用版本；完整性失败为 409/PLUGIN_INTEGRITY_FAILED，占用为 409/PLUGIN_IN_USE。M0 发现官方条目版本与包内版本不一致、`fileHash` 未定义算法且未被原安装器使用：目标须核对类型/标识/版本、获批 SHA-256 清单，不能信任前端自报摘要。原构建扩展反引号导出被拒绝亦须修复复验。

## 3. 项目创作兼容 API

兼容入口中的 `{upstreamPath}` 是**经过白名单匹配的固定路由**，不是任意代理。上游路径来自[生成路由表](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/router.ts)。方法、字段与响应以下游固定提交为源，网关只进行必要变换：注入服务端目录、过滤不允许能力、校验授权、加审计和容量。所有创作请求带路径中的 `projectId`；请求体或查询中的 `directory`、`absolutePath`、自报用户 ID 必须被拒绝或剥离，不能优先于服务端映射。

| 创作分组 | 路径例子 | 关键契约 |
| --- | --- | --- |
| 工作区/文件 | `workspaces/files/{list,read,write,copy,remove,rename}` | 项目相对路径；读写授权；写入修订号 |
| 素材 | `assets/{list,read,save,remove,rename}` | 仅项目或明确组织作用域；大文件流式上传下载 |
| 文本/模型 | `ai/models`、`ai/generate` | 已批准模型；文本 SSE 原帧 |
| 媒体 | `ai/media/{models,generate,balance,price}` | 管理模型/费用字段脱敏；生成占项目名额 |
| Agent | `agent`、`agent/{create,get,list,message,answer,canvasResult,...}` | 项目及发起人绑定；NDJSON 原事件 |
| 单 Agent | `singleAgent/{create,run,answer,control,toolResult}` | 项目内计名额；项目外按账号限流 |
| FFmpeg | `ffmpeg/{execute,events,status}`；程序安装另走管理员入口 | 转码由 execute 返回 SSE，转码取消复用 requestId；download/cancel/progress 是程序安装管理，不是成片下载 |
| 插件能力 | `nodes/**`、`tools/**`、`skills/**`、`agents/**`、`ext/**` | 成员使用获批能力；市场与安装/修改走独立管理员入口，见 §2.4 |
| MCP 控制 | `mcp/{status,skill,control/**}` | Web 页面连接与项目绑定，不暴露外部凭证 |

### 3.1 已核对的关键上游形态

- [文件写入](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/routes/workspaces/files/write.ts) 是 `PUT /api/workspaces/files/write`，查询中原有 `directory`、`path`、可选 `expectedRevision`（SHA-256 十六进制）、`exclusive` 和 `encoding`；请求体是 `application/octet-stream`。原实现旧修订号不符返回 409/`ESTALE`。1.0 入口为 `PUT /api/v1/projects/{projectId}/creative/workspaces/files/write?path=...&expectedRevision=...`；不接受外部 `directory`，冲突对页面规范化为 409/`REVISION_CONFLICT`，保留当前内容。首次新建可用 `exclusive=true`。
- [文本生成](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/routes/ai/generate.ts) 是 `POST /api/ai/generate`，请求含 `providerId`、`modelId`、`context`，可选 `references`；返回 `text/event-stream`，含 `text`、`reasoning`、`done`、`error` 类型的 `data:` JSON 帧和 `: keepalive`。1.0 不能缓冲成普通 JSON；单独文本调用不占 5 名额。
- [媒体生成](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/routes/ai/media/generate.ts) 是 `POST /api/ai/media/generate`，请求含 `mediaType=image|video|audio` 和该类型生成参数。响应最终为 JSON；等待时可能写入前导空白保活，客户端须容忍；不是 SSE。M0 实测无提供商时 HTTP 为 200，但正文 `code:404`，新平台必须把此情形识别为失败。1.0 先抢占名额，结束或确认取消后释放。
- [项目 Agent](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/routes/agent.ts) 是 `POST /api/agent`，请求含 `prompt`、`providerId`、`modelId`、可选 `canvas`/附件/会话文件；返回 `application/x-ndjson`，每行一个事件对象，包含 `done` 或 `error`，可插空行保活。1.0 保持事件顺序，并把整个 Agent 运行计 1 个名额。

### 3.2 FFmpeg 原协议与安全边界

`POST /api/ffmpeg/execute` 的 prepare 操作返回普通 JSON，转码返回 SSE，事件含 start/progress/end/error；取消转码使用同一 requestId 和 operation.method=cancel，须等子进程结束后确认取消。`ffmpeg/download`、`cancel`、`progress` 对应程序下载/安装，属于管理员能力；生成媒体通过项目文件接口下载。上游原始 inputOptions/outputOptions、滤镜和清单可产生间接 I/O，显式文件校验不是进程沙箱；成员入口须限制获批操作/参数，或在项目文件系统隔离环境中执行。[实测](../validation/evidence/upstream-ffmpeg-closeout.json)

### 3.3 文件修订与路径

上游 `GET /api/workspaces/files/read?directory=...&path=...&snapshot=true` 返回 `{data:{text,encoding,revision}}`；`revision` 是原文件字节的 SHA-256。普通读取返回文件内容，不带这个 JSON 快照。1.0 的项目文件快照读取应保留 `text`、`encoding`、`revision`，移除外部 `directory` 参数。文档编辑已有 `expectedRevision` 保存与 409/`ESTALE` 冲突处理；画布原自动保存未传该字段，1.0 必须改为读取修订号后提交 `expectedRevision`，并在 409 时停止该画布的继续自动覆盖。新建文件继续支持 `exclusive=true`。冲突响应示例：

```json
{"code":"REVISION_CONFLICT","message":"文件已被其他人修改，请重新载入后处理","requestId":"req_124","details":{"currentRevision":"<sha256>"}}
```

所有 `path` 是项目根目录下相对路径。拒绝绝对路径、`..` 穿越、编码变形及符号链接逃逸；文件 ID、下载链接和重命名目标都必须重新授权。响应不得暴露服务器绝对路径。

## 4. 外部 MCP/A2A 协议边界

上游 [MCP 控制](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/utils/mcp/control.ts) 使用单进程页面连接；[A2A 入口](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/agent/a2a/index.ts) 使用内存任务。1.0 不自定义新的 MCP/A2A JSON 结构，保持固定上游协议与 SDK 兼容；M0 已录制 Agent Card、工具发现、调用、任务提交/查询/取消及错误样例；M1 将其整理为自动契约测试夹具。

固定提交 A2A 为协议 1.0，客户端须携带 `A2A-Version: 1.0`。缺少该头会被 SDK 视为 0.3 并返回 VERSION_NOT_SUPPORTED。JSON-RPC 方法使用 `SendMessage`、`GetTask`、`CancelTask`，不能套用旧版 message/send 名称；异步提交使用 `configuration.returnImmediately=true`。[已录制样例](../validation/evidence/upstream-a2a-closeout.json)

在协议处理**之前**，Spring 校验 Bearer 凭证、归属项目与工具/团队名单；运行类调用争抢同项目名额。A2A 任务 ID 同时绑定 clientId 和 projectId，查询/取消不因知道 ID 而越权。MCP 画布控制只查找同项目页面连接。上游进程重启导致任务丢失时返回明确的失效/重试错误，不伪造完成结果。原协议若无法表达上述错误，在网关预处理阶段用 HTTP 状态和标准 MCP/A2A 错误对象表达，具体字段由 M1 对照 M0 样例冻结。

## 5. 已有台账与待冻结接口

截至本稿，[迁移清单](../product/TOONFLOW_WEB_MIGRATION.md#3-基于代码路由的迁移清单)列出 127 条自动路由，其中 25 条明确排除，102 条为已有源事实及目标入口处置的 Web 候选；另有独立挂载的 MCP/A2A。102 是**候选数量，不是无条件发布数量**。现有 `docs/api/route-inventory.md` 和逐条明细已记录源事实及目标处置；M1–M3 继续完善：上游方法与路径、前端调用点、请求/响应示例、流式事件、1.0 入口、角色与项目规则、是否占名额、Bun 实现位置、自动测试、迁移状态、验收编号；不设固定负责人。没有台账与测试的路由不得加入公开白名单。

明确拒绝 `/api/desktop/**`、`/api/mobileLink/**`、`/api/browser/{input,stream}`、`/api/workspaces/selectDirectory`、`/api/workspaces/files/reveal`；插件市场与受控远程安装纳入独立管理员契约。管理类路由按实际所需独立授权，不得借创作兼容入口给普通成员透传。

2026-10-22 已完成 [127 条源事实与入口处置](upstream/route-details.md)、[机器可读台账](upstream/route-details.json)和 102 条无参/空输入基础探测。2026-10-24 的 M1 交付 38 个 OpenAPI 操作与 127 条目标映射：8 frozen、92 proposed、25 excluded、2 internal，全部 publicEnabled=false。全部业务成功流及 Spring 身份/项目测试仍由 M2/M3 完成。

## 6. 契约验证与变更规则

1. M0 固定上游提交并录制原 Web 关键路径、MCP/A2A 样例和原项目文件；M1 把平台 API 转成 OpenAPI，把 SSE/NDJSON/MCP/A2A 样例作为测试夹具。
2. 前端适配层与 Spring 使用同一契约生成类型或同一测试夹具；每批迁移比较响应、事件顺序、取消与错误。
3. 任何字段或协议变化先更新此文档与机器可读契约，标明兼容性影响，再实现与测试；不得直接修改已冻结的原项目文件正文格式。
4. 已冻结的错误码、DTO 和线协议可作为客户端依赖；proposed 细项仍须在对应批次评审、测试后冻结。具体上游未核对的路由不得凭路径名称推断方法、参数或状态码。

## 6. M2 实现状态

[实现清单](../../backend/api-implementation.json)驱动 OpenAPI 的 x-implemented：当前 49 个操作中 41 个为 true（含安全导出、精确文件读取/写入及五个目录操作、四个媒体操作与三个生成/运行操作），其余 8 个保持 false。生成操作用 x-implementation-scope 标明仅 Qwen 文生图 1K/1:1；接口实现不代表关联 A 编号所有业务已验收，最新范围见[M2 验收](../validation/M2_ACCEPTANCE.md)。所有源创作路由 publicEnabled 仍为 false。

MCP/A2A 的 M2 入口只执行凭证及能力拒绝，不运行 SDK 或上游；合法凭证的未迁移业务返回 503/EXECUTOR_UNAVAILABLE，可解析 JSON-RPC 保留 id，通知不伪造 id。此临时拒绝不是协议握手或业务实现，M3-06 再补原协议分派。登录成功严格使用 OpenAPI User DTO（含 status）；CONTRACTS §2.1 的精简示意不替代字段字典。

### 6.1 M2 界面收尾增补

新增 `GET /api/v1/admin/models`：ADMIN 会话只读，响应 `{items:Model[]}` 包含已登记但尚未批准的模型；不返回端点、密钥或目录。使用既有 approveModel 启停，成员 listModels 仍仅返回批准项。新增接口保持原 38 操作兼容，当前共 39 操作/33 路径，26 实现。新增读接口的 FR-09/A11、角色与 DTO 元数据已通过契约检查。

既有 `GET/PUT /api/v1/preferences` 已实现，仅存当前会话账号的 ui/general/stores/documentExtensions。对象最多 32 KiB、深度最多 32；不接受跨账号 userId 或嵌套密钥/令牌/供应商管理字段。以上是实现约束，不放宽原业务安全边界。[验证证据](../validation/M2_UI_REPORT.md)。


### 6.2 最小受控图片链

POST `/api/v1/projects/{projectId}/creative/ai/media/generate` 要求成员会话、CSRF、已批准模型和 Idempotency-Key；只接受声明的提示词/模型/规格字段。当前接通 qwenImage21Local/qwenImage21Text、image、1K/1:1，其他规格拒绝 400，其他未接通模型拒绝 503；不接受客户端工作流、ComfyUI URL、文件路径或对象键。

首次请求维持原 JSON 空白保活/业务终态协议，以 X-Run-Id 和 Location 标识运行；同一幂等键返回原 Run（202），不同请求摘要为 409。GET `/api/v1/projects/{projectId}/runs/{runId}`、POST 同路径加 `/cancel` 均要求仍有效的成员和原发起人，取消另需 CSRF。成功结果含受控媒体 ID 和虚拟相对路径，下载经项目授权；取消/LOST 等状态不伪装成功。完整字段继续以 OpenAPI 为准。

`.huimeng-media/{mediaId}.png` 为项目 read 的虚拟对象引用，不支持 snapshot 文档语义。公开模型 options 只返回确认的 resolutions/defaultResolution，保留 2K 元数据；其他私有字段、端点与任意嵌套配置均不回显。批准或成员关系撤回后，旧客户端调用同样拒绝。


### 6.3 M3-01 首批保存接口

PUT 项目 creative/workspaces/files/write 已实现。现阶段正文及编码后大小均不超过 8 MiB，Content-Length 必填；父目录必须存在。新建 exclusive=true，覆盖 expectedRevision=读取时字节 SHA-256，二者恰选一种；缺少前置或非法/重复参数为 400。正文原字节保存；指定 encoding 时输入为 UTF-8 文本，按 UTF-8/BOM/UTF-16 LE/BE 保存，非法文本 415、超限 413。成功返回 WriteResult，冲突 409/REVISION_CONFLICT 的 details.currentRevision 为当前摘要或文件已删除时 null。

当前仅单 Spring 写入者，通过安全目录句柄、项目变更协调及文件锁保存，Windows 不支持时 503；不隐式建目录，不使用对象键或任意服务器路径。写入不表示原画布自动保存或整个 CRUD 已接通，页面修订链继续 M3-W03。真实允许/拒绝与兼容证据见[M3 记录](../validation/M3_FILE_WRITE.md)。

### 6.4 M3-01 目录操作与独立文档区域

精确 GET list 只接 path（空值为根），返回 FileListResult，directory=项目 ID；POST mkdir/copy/rename 与 DELETE remove 使用 JSON，字段为 path、可选 target 或 recursive。拒绝未知/重复查询及未知 JSON 字段；不能操作项目根或跨项目目标。目标存在返回 409/FILE_ALREADY_EXISTS；非递归删除非空目录为 409/DIRECTORY_NOT_EMPTY；I/O 可能部分完成时为 503/FILE_OPERATION_INCOMPLETE，应刷新核对。列表 5000 项、复制 512 MiB/10000 项/64 层上限和安全原语限制均见 OpenAPI。

R117/R119/R120/R122/R123 本批冻结，当前 13 frozen、87 proposed、25 excluded、2 internal；源 publicEnabled 不变。独立文档区域已有快照/手动保存、冲突暂停和另存，原文件树/文档面板与画布自动保存仍待迁移。实际范围及双标签浏览器证据见[本批报告](../validation/M3_WORKSPACE.md)。

### 6.5 M3-01 原页面修订链接通（2026-10-26）

原文件树/文档面板/画布已复用同一精确文件 API 完成读取修订与覆盖保存：画布打开/新建记录随各画布对象的快照，自动保存、节点正文与画布写回、复制临时项覆盖均携带 `expectedRevision`；成功后更新为新返回修订号，快照按画布 ID 定位。409/`REVISION_CONFLICT` 时前端暂停该画布自动覆盖并保留内存修改，可强制重读恢复；文档会话冲突后同样暂停自动覆盖、保留草稿。`write` 的 exclusive 已存在与覆盖修订不符在本实现中均为 409/`REVISION_CONFLICT`，前端已据此统一识别；mkdir 已存在为 409/`FILE_ALREADY_EXISTS`，删除/读取缺失为 404/`NOT_FOUND`。

`ext-plaintext` 为内置最小文本编辑器，仅在远程扩展清单未接通或无可用扩展时回退，支持常见文本后缀与 canvasNode 正文；它不是新的公开 API，也不代表扩展体系已完成。19 项真实浏览器检查覆盖连续保存、文档/画布冲突保留与重读、素材相对引用，见[M3-W03 记录](../validation/M3_W03_PAGES.md)。

### 6.6 M3-02 模型列表与文本生成（2026-10-26）

`POST /creative/agent` 输出 `session/userMessage/text/done/error` NDJSON；会话存于项目 `.agent/sessions`，支持 `create/get/list/rename/message/cancel`；同会话生成互斥（409 `AGENT_RUNNING`），`cancel` 在下一增量前生效并关闭上游流。`LlmClient` 支持 OpenAI 兼容 `deepseek`/`qwen`，端点来自服务端配置；密钥仅内存使用，执行前重新校验项目成员与数据库批准状态。`R128` 注册取消兼容路由。工具回调、画布控制与 `.jsonl` 原格式兼容在 M3-04；A07/A11 证据见[M3-02 记录](../validation/M3_TEXT_GENERATION.md)。

### 6.7 M3-03 图片生成规格与取消（2026-10-26）

`generateMedia` 的图片分支接通现用 Qwen 两模型：`qwenImage21Text` 只接受提示词，`qwenImage21Edit` 必须且只能带一项 `images`，其 `path` 须为 `.huimeng-media/<uuid>.png`、`mimeType` 为 `image/png`。`size` 取 1K/0.5MP/2K、`ratio` 取 8 种画幅，省略时服务端按 1K、1:1 提交；非法组合 400，不做静默降级，`data[].spec` 回显实际提交的规格与像素尺寸。名额由数据库事务先占，之后才探测执行器，因此第 6 个请求 429 且不触达供应商；幂等重放仍为 202 原运行。执行器返回的 `prompt_id` 取代本地运行 ID 作为查询/取消参照。视频与音频分支保持原契约但本批未实现，A08 图片部分证据见[M3-03 记录](../validation/M3_MEDIA_GENERATION.md)。

### 6.8 M3-04 Agent 工具循环与画布回执（2026-10-26）

`POST /creative/agent` 请求可带 `canvas: {id}`；服务端据此下发 18 个画布工具（与 `@toonflow/tool-canvas` 同名同参数）和 `askQuestion`、`generateImage`。模型调用画布工具时服务端只发 `canvasCall`，由页面校验并执行后经 `POST /creative/agent/canvasResult` 回传（`result` 或 `error`，120 秒超时）；提问经 `POST /creative/agent/answer` 回传（`answer`/`values`/`cancelled`/`skipped`，300 秒超时）。两个回执端点要求 `callId` 属于本项目正在等待的调用，否则 404 `TOOL_CALL_NOT_FOUND`；`R002`/`R003` 本批冻结并标记实现。`generateImage` 在服务端同步执行，复用同一名额与幂等语义，文本会话本身不占名额。会话文件为 `.jsonl`（`session` 头 + 逐条 `message`），旧 `.json` 仍可读取。证据见[M3-04 记录](../validation/M3_AGENT_TOOLS.md)。

### 6.9 M3-05 能力目录与受控转码（2026-10-26）

`GET /capabilities/nodes/get`、`/capabilities/tools/get`、`/capabilities/tools/renderers`、`/capabilities/ffmpeg/status` 与 `GET /projects/{projectId}/creative/agent/skills` 为成员只读目录：没有随镜像分发的内容返回空列表，不返回 404。`POST /projects/{projectId}/creative/ffmpeg/execute` 只接受 `prepare`/`cancel`/`transcode`/`concat`/`extractAudio`；素材必须是 `.huimeng-media/<uuid>.<受支持扩展名>`，预设固定为 mp4-720p、mp4-1080p、webm-720p、mp3-192k，命令由服务端拼装。`prepare`/`cancel` 返回 JSON，其余返回 `text/event-stream`，事件为 `{event:"progress",args:[{outTimeMs,percent}]}`、`{event:"done",args:[{path,mimeType,mediaType,preset}]}` 或 `{event:"error",args:[{message}]}`；开流前的参数错误仍按 JSON 400 返回。R010/R059/R061/R073/R074/R109/R110/R112 本批冻结并标记实现。证据见[M3-05 记录](../validation/M3_CAPABILITIES_FFMPEG.md)。

### 6.10 M3-05 H3 视频生成（2026-10-26）

`generateMedia` 在 `mediaType=video` 时只接受 `providerId=h3Local3060` 与 `h3T2v/h3I2v/h3Fl2v/h3L2v/h3R2v`：`size` 取 352p/480p/544p（缺省 352p），`ratio` 取 16:9/9:16/1:1/4:3/3:4（缺省 16:9），`duration` 取 5/8/10/15（缺省 5），`images` 按模式要求（t2v 0 张、i2v/l2v 1 张、fl2v 首尾 2 张、r2v 1–9 张）。`data[].spec` 回显形如 `5s 352p 16:9 608×352`；产物为 `.huimeng-media/<uuid>.mp4`（`video/mp4`）。非法时长/分辨率/比例/参考数量一律 400，不静默降级；名额、幂等与取消语义与图片完全一致。证据见[H3 记录](../validation/M3_H3_VIDEO.md)。
