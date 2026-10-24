# M1 契约冻结记录

2026-10-24。依据 PRD、DESIGN、CONTRACTS、127 条固定提交路由明细及 M0 已有协议样例；没有重新运行 M0 或调用供应商。本文记录实现决策，不表示 Spring、Bun 或新平台业务验收已完成。

## 1. 可用交付物与状态

- [OpenAPI](openapi.yaml)：38 个操作，涵盖新增平台接口、媒体/文本/Agent、FFmpeg、文件快照与修订写入的机器可读契约，`x-contract-status: frozen`。`x-implemented: false` 表示尚待 M2/M3 实现。
- [逐路由目标映射](route-map.json)：完整覆盖 R001–R127，保留方法、来源哈希、调用点、请求字段、响应源码表达式、角色、项目约束、CSRF、幂等与 A 编号。它不是可执行代理白名单。
- `proposed` 表示源字段已取证、目标 DTO 仍需在相应 M3 批次细化。例如插件运行资源、Agent 嵌套事件与工具参数，不能用宽泛 object 代替最终字段字典后声称全部冻结。排除路由和内部诊断无公共目标路径，全部 `publicEnabled=false`。
- 文本、Agent、FFmpeg、MCP/A2A 的现有线协议继续以 [CONTRACTS](CONTRACTS.md) 与 M0 原始记录为准；[回归夹具](../validation/contracts/README.md)检查分块、终态与错误。M1-04 不验证新平台权限，也不运行媒体模型。

## 2. 平台通用规则

| 项目 | 冻结规则 |
| --- | --- |
| 会话 | `__Host-huimeng-session`，Secure、HttpOnly、SameSite=Lax、Path=/，无 Domain；预登录与登录后会话均由服务器维护 |
| CSRF | `GET /api/v1/auth/csrf` 返回 `{token}` 并设预会话 Cookie，no-store；所有会话写请求（含登录）传 `X-CSRF-Token`，校验同源 Origin；登录轮换会话和令牌，客户端重新取令牌；失败 403/CSRF_INVALID |
| 分页 | 普通列表 cursor/limit，默认 20、最大 50；`nextCursor:null` 表示结束；按创建时间、ID 稳定排序，cursor 与过滤条件绑定，非法 cursor 返回 400；市场保留 page/limit/total |
| ID/时间 | 平台 ID 是不透明的字母数字/下划线/连字符字符串，最长 128；UTC ISO 8601；不得将目录作为 ID。市场数值 ID 在安全 DTO 中转换为十进制字符串 |
| 写操作 | 不自动重试创建、收藏 toggle、安装或其他非幂等变更；PATCH 至少提供一个字段。密钥只写，密码/原文凭证不进入查询响应 |
| 项目权限 | 管理员只管理元数据，创作仍需成员关系；归档项目拒绝新创作写入及运行；运行查询/取消还要求发起人身份。管理列表不授予内容访问权 |
| 错误 | 开始流前使用 Error DTO；401 重新登录，403 显示权限错误，409 保留本地修改，429 显示容量或限速；流内沿用原事件，不拼接平台 JSON |

平台实现默认值：会话空闲 8 小时、最长 24 小时；幂等记录至少保留到运行终态后 24 小时，活跃记录不因 TTL 清除；文件单次上传默认上限 512 MiB，可部署配置覆盖；审计默认保留 180 天。它们是实现配置，不是发布性能门槛。HTTPS 端口、证书及 ComfyUI 地址在 M2-08 实测后确定；不在前端写本机服务地址。

## 3. 运行、流与取消

媒体、项目 Agent、项目内单 Agent 的新运行要求 `Idempotency-Key`，最长 128。同 actor、project、operation、key 原子关联一个运行；对验证并应用默认值后的 JSON 按对象键排序求摘要（数组保留顺序，文件按字节及规范化元数据），不同内容返回 409/IDEMPOTENCY_CONFLICT。请求头和 JSON-RPC 关联 id 不参与业务摘要。同内容重试不重放副作用：Web 创作入口返回 **202/application/json 的 Run DTO**，附 X-Run-Id 与同源 Location，由页面查询，不能当作生成成功；不得再次调用供应商。每次重试仍重新检查身份和项目权限。

MCP JSON-RPC notification/握手/只读调用不要求幂等键；启动媒体/Agent 的 tools/call 与 A2A SendMessage 使用同一头规则，网关在分派前判断。MCP/A2A 重试保持原协议：返回原任务/协议结果或带原运行标识的协议错误，并回显本次 RPC id，不能套用 Web 的 202/Run 包装。

流式成功响应返回 `X-Run-Id`，客户端断开后以 `GET .../runs/{runId}` 查询。`POST .../runs/{runId}/cancel` 返回 202 和状态，只表示接收；`CANCELLING`、`LOST` 仍可能 `capacityHeld=true`。执行器确认停止才释放，状态查询不得只按终态枚举推断名额。文本不占五名额，停止文本传输使用 AbortSignal；计名额运行的显式取消必须走取消端点。

| 协议 | 帧及终态 | 注意事项 |
| --- | --- | --- |
| 文本 SSE | `data:` JSON，type=text/reasoning/done/error，注释心跳；空行结束帧 | UTF-8 可跨块；多行 data 拼接；error 是失败；无 done/error 的 EOF 视为中断 |
| Agent NDJSON | 每行 JSON，可插空行；保留 text/thinking/tool/question/canvasCall 等原顺序；done 或 error 终态 | HTTP 200/error 行不是成功；子 Agent 事件不能结束父运行；单 Agent complete 与项目 Agent done 分开处理 |
| 媒体 JSON | 任意前导 JSON 空白 + 最终 `{code,data,message}` | 仅 code=200 成功；code=404 等必须失败；不当成 SSE；只有空白或截断 JSON 是中断 |
| FFmpeg | prepare/cancel 普通 JSON，执行 SSE 的 `{event,args}`，start/progress/end/error | 以同 requestId 取消，收到进程结束确认才完成；程序 download/progress/cancel 是管理员安装操作 |
| MCP | SDK 原协议，工具错误包括 isError、JSON-RPC error | 不套平台业务包装；连接与页面控制须绑定调用方、项目；协议握手不能消耗五名额 |
| A2A | 协议 1.0，A2A-Version: 1.0，SendMessage/GetTask/CancelTask | 异步提交 configuration.returnImmediately=true；任务绑定 client+project；终态 completed/canceled/failed/rejected；查询越权不得泄露存在性 |

MCP/A2A 的认证失败在协议分派前返回 HTTP 401，授权失败 403，容量 429。可解析的 JSON-RPC 请求保留 id；网关新增拒绝使用服务器错误范围内的 -32099，错误对象为 `error:{code:-32099,message,data:{code,requestId}}`，data.code 对应 CLIENT_TOKEN_INVALID/CAPABILITY_FORBIDDEN/PROJECT_CAPACITY_EXCEEDED/RUN_LOST。已有 SDK 协议错误及数字码保持原样，尤其不能将 A2A 的 TaskNotFound（-32001）重定义为认证错误。通知不伪造 id；以 HTTP 拒绝。A2A Card 的 URL 必须替换成当前 HTTPS 公共地址，不保留 Bun 地址。

## 4. 文件与插件决策

读取快照保留原编码、原字节 SHA-256 修订号；保存已有文件必须传 expectedRevision，新建必须 exclusive=true，两者互斥。画布需与文档一样维护修订链；409 暂停自动覆盖，不能用自动重读后强写隐藏冲突。请求中的 directory、absolutePath、自报 actor 一律拒绝；outputDirectory 是经过项目边界校验的相对输出子目录。资源 URL 和错误正文也要消除绝对路径。

FFmpeg 为保持现有节点调用能力，选择**项目文件系统隔离的执行环境**，包括禁用未批准的网络协议、限制输入输出及资源；不是在 Spring 校验几个显式参数后透传。隔离实现与负例测试留在 M3-05；未通过前不开放 execute。M0-I09 保持未关闭。

市场 DTO 由上游 `identifier/name/supplier/desc` 映射为安全标识、显示名称、作者与描述；缺失的展示字符串用空串，缺失或不合法的类型/ID/版本拒绝该项。目录不返回下载 URL、认证参数、含算法不明的 fileHash。`installable=false` 包括 Agent 市场项及没有获批明确摘要的制品；保留发现但不开放原关闭的 Agent 安装。

在线安装使用目录 pluginId 和 `manifest:{type,name,version,sha256}`，后端重新查目录并对获批制品清单校验，不能信任前端给出的摘要。无明确摘要不允许安装；类型、标识、版本、SHA-256 任一不一致返回 409/PLUGIN_INTEGRITY_FAILED；不能声称验证了数字签名。本地文件安装同样校验包内元数据与获批摘要。覆盖/降级需用户明确确认 force；占用返回 409/PLUGIN_IN_USE，失败保留旧版本。扩展安装器应识别构建产物的有效元数据，而不是依赖只匹配单双引号的正则；真实反引号产物复验留在 M3-08。

Qwen 切换模型时保留新模型支持的已选规格；只有缺省或不支持时使用推荐值并在提交前展示。2K 不在本次契约中被禁用，也不将推荐 1K 当作强制转换。M0-I15 的真实 2K、取消和新平台回归仍未完成。

## 5. 验证与后续

契约静态检查覆盖 OpenAPI 结构/引用、路径参数、角色、CSRF、DTO、127 条映射、25 条排除及禁止通配。回归夹具覆盖传输分块及错误终态。它们不代替 A01–A18 业务验收。

M1 独立前端、统一客户端及 UI 清理已完成，见[M1 报告](../validation/M1_REPORT.md)；下一项为 M2-01。`proposed` 兼容接口在 M3 对应批次冻结完整响应字典并补成功/失败与权限测试后，才能成为 Spring 显式白名单。A15 备份恢复不是新增在线 API；A18 重启失效与容量保守释放由 M3/M4 实测。
