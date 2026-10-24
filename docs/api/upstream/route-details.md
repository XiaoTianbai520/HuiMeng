# 固定提交逐路由基线

> 2026-10-22。源码固定为 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`，127/127 路由文件与 Git archive 原字节相同。

本文件记录上游事实与 1.0 入口处置。102 条候选逐条执行了无参请求或缺字段负例：20 条 HTTP 200（含 1 条幂等取消程序下载），82 条 HTTP 400。此结果不表示所有业务成功流或新平台的权限测试完成；M1 冻结 OpenAPI，M2/M3 再实现并验证权限。所有路由的 publicEnabled 仍为 false。

原始样例见 [102 条基础探测](../../validation/evidence/upstream-route-probes.json)，按 `routes[].id` 定位；机器可读定义见 [JSON 台账](route-details.json)。完整业务样例另见 [M0 补测报告](../../archive/m0/M0_CLOSEOUT.md)。

## 入口处置

| 分类 | 数量 |
| --- | --- |
| 项目创作适配 | 39 |
| 管理员适配 | 44 |
| 成员安全视图 | 12 |
| 排除 | 25 |
| 仅内部诊断 | 2 |
| 拆分配置 DTO | 2 |
| 个人配置适配 | 2 |
| 替换项目入口 | 1 |

2026-10-22 用户新增插件市场：管理员安装支持获批在线来源与本地文件；远端下载先校验来源、重定向、文件和版本。新增市场查询/收藏不在127条本地路由中，见[市场契约](../CONTRACTS.md#24-插件市场与管理)。成员素材、会话和控制连接必须绑定项目与账号。管理员访问项目数据仍遵循项目授权和审计要求。

## agent

### R001 POST /api/agent

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：整个运行 1；子工具/子 Agent 沿用父名额。
- 实现：`apps/server/src/routes/agent.ts`；SHA256 `136993f32b4534653ad0ec9331ebedc4cafcd11d974a01fbb6f51fb07aab280c`。
- 原始调用点：apps/web/src/components/agent/conversation.vue。
- 实际直写调用：`apps/web/src/components/agent/conversation.vue:662`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `prompt` | 是 | `z.string().trim()` |
| `directory` | 是 | `z.string().min(1)` |
| `attachments` | 否 | `u.agent.agentAttachmentsSchema.optional()` |
| `mentions` | 否 | `u.agent.agentMentionsSchema.optional()` |
| `providerId` | 是 | `z.string().min(1)` |
| `modelId` | 是 | `z.string().min(1)` |
| `thinkingLevel` | 否 | `z.enum(["off", "low", "medium", "high"]).optional()` |
| `sessionFile` | 否 | `z.string().regex(/^[\w-]+\.jsonl$/).optional()` |
| `resendFrom` | 否 | `z.string().min(1).max(128).optional()` |
| `canvas` | 否 | `z.strictObject({
     id: z.string().min(1).max(256),
     tools: z.array(z.strictObject({
       nodeId: z.string().min(1).max(256),
       name: z.string().max(101).regex(/^node:[a-z][a-zA-Z0-9]*$/),
       nodeLabel: z.string().max(200).optional(),
       description: z.string().max(4000),
       parameters: z.record(z.string(), z.json()).refine(value => value.type === "object", "函数参数必须是 object JSON Schema"),
     })).max(1000),
   }).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R001-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R001]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R002 POST /api/agent/answer

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/answer；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/answer.ts`；SHA256 `13fc340f3f2b1ecdf5247c56dd31c035ab4a104e28cfd926ee33f07c01d84958`。
- 原始调用点：apps/web/src/components/agent/conversation.vue。
- 实际直写调用：`apps/web/src/components/agent/conversation.vue:734`。
- 实际直写调用：`packages/tools/askUser/src/questionCard.vue:147`。

#### 请求参数

位置：`body`；校验入口 `{

  directory: z.string().min(1).max(4096),

  callId: z.uuid(),

  cancelled: z.boolean().optional(),

  skipped: z.boolean().optional(),

  answer: z.string().trim().min(1).max(8000).optional(),

  values: z.record(z.string().max(64), z.union([z.string().max(8000), z.number(), z.boolean(), z.null(), z.array(z.string().max(300)).max(20)])).optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `callId` | 是 | `z.uuid()` |
| `cancelled` | 否 | `z.boolean().optional()` |
| `skipped` | 否 | `z.boolean().optional()` |
| `answer` | 否 | `z.string().trim().min(1).max(8000).optional()` |
| `values` | 否 | `z.record(z.string().max(64), z.union([z.string().max(8000), z.number(), z.boolean(), z.null(), z.array(z.string().max(300)).max(20)])).optional()` |

#### 返回数据与验证

基础样例 `M0-R002-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R002]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R003 POST /api/agent/canvasResult

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/canvasResult；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/canvasResult.ts`；SHA256 `708b2e8c73e2ba5b8509d606fcc5f47055fdf04ad446c39b64b42713832a1b45`。
- 原始调用点：apps/web/src/components/agent/conversation.vue。
- 实际直写调用：`apps/web/src/components/agent/conversation.vue:604`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1)` |
| `callId` | 是 | `z.uuid()` |
| `result` | 否 | `z.json().optional()` |
| `error` | 否 | `z.string().min(1).max(8000).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R003-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R003]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R004 POST /api/agent/create

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/create；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/create.ts`；SHA256 `31484443c006ae252401308f17dae5fbaf3ac16e131325d23282725e20c51cd6`。
- 原始调用点：apps/web/src/components/agent/index.vue。
- 实际直写调用：`apps/web/src/components/agent/index.vue:155`。

#### 请求参数

位置：`body`；校验入口 `{ directory: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R004-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R004]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R005 GET /api/agent/get

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/get；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/get.ts`；SHA256 `7ea9d4867f1de0a4158afcd5e6b1cfaae4a51e91b0053eecceb5bbd0748ff5fb`。
- 原始调用点：apps/web/src/components/agent/index.vue。
- 实际直写调用：`apps/web/src/components/agent/index.vue:174`。

#### 请求参数

位置：`query`；校验入口 `{

  directory: z.string().min(1).max(4096), sessionFile: z.string().regex(/^[\w-]+\.jsonl$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `sessionFile` | 是 | `z.string().regex(/^[\w-]+\.jsonl$/)` |

#### 返回数据与验证

基础样例 `M0-R005-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R005]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R006 GET /api/agent/list

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/list；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/list.ts`；SHA256 `82ba6b13aeee41eda5c58530401a172720406ea14d0537d83e26a2d76622813d`。
- 原始调用点：apps/web/src/components/agent/index.vue。
- 实际直写调用：`apps/web/src/components/agent/index.vue:188`。

#### 请求参数

位置：`query`；校验入口 `{ directory: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R006-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R006]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R007 GET /api/agent/mentionSource

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/mentionSource；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/mentionSource.ts`；SHA256 `f60d8d91e3dd4d612c0020cac6fc9df4e9180abf37ad50bd2ad3a3b79a8c7555`。
- 原始调用点：apps/web/src/components/agent/mentionSources.ts。
- 实际直写调用：`apps/web/src/components/agent/mentionSources.ts:14`。

#### 请求参数

位置：`query`；校验入口 `shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `operation` | 是 | `z.enum(["canvases", "nodes", "outputs", "selectCanvas", "assets", "selectAsset"])` |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `canvasId` | 否 | `z.string().min(1).max(4096).optional()` |
| `nodeId` | 否 | `z.string().min(1).max(256).optional()` |
| `outputId` | 否 | `z.string().min(1).max(256).optional()` |
| `path` | 否 | `z.string().max(4096).optional()` |
| `query` | 否 | `z.string().max(200).optional()` |
| `cursor` | 否 | `z.string().max(4096).optional()` |
| `limit` | 否 | `z.coerce.number().int().min(1).max(50).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R007-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R007]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R008 DELETE /api/agent/message

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/message；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/message.ts`；SHA256 `3ae5ceb2075cd410982895c6709d7a2965cd1862ad6a309119cd169bb064870e`。
- 原始调用点：apps/web/src/components/agent/conversation.vue。
- 实际直写调用：`apps/web/src/components/agent/conversation.vue:522`。

#### 请求参数

位置：`body`；校验入口 `{

  directory: z.string().min(1).max(4096),

  sessionFile: z.string().regex(/^[\w-]+\.jsonl$/),

  entryIds: z.array(z.string().min(1).max(128)).min(1).max(1000).optional(),

  replyTo: z.string().min(1).max(128).optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `sessionFile` | 是 | `z.string().regex(/^[\w-]+\.jsonl$/)` |
| `entryIds` | 否 | `z.array(z.string().min(1).max(128)).min(1).max(1000).optional()` |
| `replyTo` | 否 | `z.string().min(1).max(128).optional()` |

#### 返回数据与验证

基础样例 `M0-R008-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R008]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R009 PATCH /api/agent/rename

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/rename；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/rename.ts`；SHA256 `bb1cd6f8f2e12c9ab698e750aa223f4eb9c058d119c3fcdeffd8098a2173066f`。
- 原始调用点：apps/web/src/components/agent/index.vue。
- 实际直写调用：`apps/web/src/components/agent/index.vue:244`。

#### 请求参数

位置：`body`；校验入口 `{

  directory: z.string().min(1).max(4096),

  sessionFile: z.string().regex(/^[\w-]+\.jsonl$/),

  name: z.string().trim().min(1).max(80),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `sessionFile` | 是 | `z.string().regex(/^[\w-]+\.jsonl$/)` |
| `name` | 是 | `z.string().trim().min(1).max(80)` |

#### 返回数据与验证

基础样例 `M0-R009-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R009]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R010 GET /api/agent/skills

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/agent/skills；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/agent/skills.ts`；SHA256 `c64ef922efce7d090692b7f11a387654a009b39fe61eeade18e5423dfde852d1`。
- 原始调用点：apps/web/src/components/agent/skillMenu.vue。
- 实际直写调用：`apps/web/src/components/agent/skillMenu.vue:83`。

#### 请求参数

位置：`query`；校验入口 `{ directory: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R010-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R010]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## agents

### R011 GET /api/agents/a2a/get

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/a2a/get.ts`；SHA256 `f28a53eb2609473d21856b1764e2f6c53c0167964f41f47855bd4f2c35641630`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/a2aSettingsDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/a2aSettingsDialog.vue:59`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R011-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R011]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R012 PUT /api/agents/a2a/save

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/a2a/save.ts`；SHA256 `482ca2b821bdb2779929422c05bb219aab857c950220abafcf8f12ae86d2965b`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/a2aSettingsDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/a2aSettingsDialog.vue:82`。

#### 请求参数

位置：`body`；校验入口 `{

  enabled: z.boolean(),

  directory: z.string().max(4096),

  providerId: z.string().max(256),

  modelId: z.string().max(256),

  thinkingLevel: z.enum(["off", "low", "medium", "high"]).optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `enabled` | 是 | `z.boolean()` |
| `directory` | 是 | `z.string().max(4096)` |
| `providerId` | 是 | `z.string().max(256)` |
| `modelId` | 是 | `z.string().max(256)` |
| `thinkingLevel` | 否 | `z.enum(["off", "low", "medium", "high"]).optional()` |

#### 返回数据与验证

基础样例 `M0-R012-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R012]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R013 POST /api/agents/connect

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/connect.ts`；SHA256 `16f2c55e4ad0336dc0c35f26b4e14a50d0442008efbb96d1f3c314c5a357bf54`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/agentConnectDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/agentConnectDialog.vue:42`。

#### 请求参数

位置：`body`；校验入口 `{

  name: z.string().regex(u.teams.teamNamePattern),

  cardUrl: z.string().url().max(4096).refine(value => {

    const url = new URL(value);

    return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password && !url.hash;

  }),

  token: z.string().max(8192).optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(u.teams.teamNamePattern)` |
| `cardUrl` | 是 | `z.string().url().max(4096).refine(value => {
     const url = new URL(value);
     return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password && !url.hash;
   })` |
| `token` | 否 | `z.string().max(8192).optional()` |

#### 返回数据与验证

基础样例 `M0-R013-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R013]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R014 GET /api/agents/get

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/get.ts`；SHA256 `ef6083b96c97bf4c2dc556e7cb003c528829f98f6f107f72b1ea8298d7b32269`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R014-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R014]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R015 POST /api/agents/install

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/install.ts`；SHA256 `cff8b8d10a0f59412e9e09dd15ef3fc903212db44da43a5e5080ecbee8d12c3e`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: z.string().max(128).optional(),

  base64: z.string().min(1).max(Math.ceil(20 * 1024 * 1024 / 3) * 4).base64().optional(),

  url: z.string().url().max(4096).optional(),

  force: z.boolean().optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 否 | `z.string().max(128).optional()` |
| `base64` | 否 | `z.string().min(1).max(Math.ceil(20 * 1024 * 1024 / 3) * 4).base64().optional()` |
| `url` | 否 | `z.string().url().max(4096).optional()` |
| `force` | 否 | `z.boolean().optional()` |

#### 返回数据与验证

基础样例 `M0-R015-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R015]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R016 GET /api/agents/read

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/read.ts`；SHA256 `a33b637c9b54221ceca6515a2520be227e029689346c688fe5fb657014fc3851`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/agentEditorDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/agentEditorDialog.vue:47`。

#### 请求参数

位置：`query`；校验入口 `{ name: z.string().regex(u.teams.teamNamePattern) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(u.teams.teamNamePattern)` |

#### 返回数据与验证

基础样例 `M0-R016-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R016]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R017 PUT /api/agents/save

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/save.ts`；SHA256 `9ad79ea80aa52cea31c81014bfbb1da7072cb48ab33d1e185be7a6109aea36cc`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/agentEditorDialog.vue。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/agentEditorDialog.vue:86`。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().regex(u.teams.teamNamePattern), path: z.string().min(1).max(1024), content: z.string() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(u.teams.teamNamePattern)` |
| `path` | 是 | `z.string().min(1).max(1024)` |
| `content` | 是 | `z.string()` |

#### 返回数据与验证

基础样例 `M0-R017-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R017]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R018 PUT /api/agents/setEnabled

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/setEnabled.ts`；SHA256 `e7766186a4b4fdc2b1afb1788a341f7b499448244ee7a4474d768cbb976a8b66`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().regex(u.teams.teamNamePattern), enabled: z.boolean() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(u.teams.teamNamePattern)` |
| `enabled` | 是 | `z.boolean()` |

#### 返回数据与验证

基础样例 `M0-R018-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R018]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R019 DELETE /api/agents/uninstall

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：获批团队与 A2A 配置管理；原关闭的 Agent 市场安装入口沿用；原 token 不回显。
- 五名额：无。
- 实现：`apps/server/src/routes/agents/uninstall.ts`；SHA256 `b130468a375499f6f7426c7aa64e0394110fb4d801ffdf784b62efc86389dd43`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().regex(u.teams.teamNamePattern) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(u.teams.teamNamePattern)` |

#### 返回数据与验证

基础样例 `M0-R019-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R019]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## ai

### R020 POST /api/ai/generate

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/ai/generate；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/ai/generate.ts`；SHA256 `8e6f50bf6c9cc79ee9caa8ca16b76cf587e92a295a21dd543812e07105b4600c`。
- 原始调用点：packages/nodeScaffold/src/nodeAi.ts（内置节点）。
- 实际直写调用：`packages/nodeScaffold/src/nodeAi.ts:130`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `providerId` | 是 | `z.string().min(1)` |
| `modelId` | 是 | `z.string().min(1)` |
| `context` | 是 | `contextSchema` |
| `directory` | 否 | `z.string().min(1).max(4096).optional()` |
| `references` | 否 | `z.array(u.ai.aiReferenceSchema).max(32).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R020-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R020]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R021 POST /api/ai/media/balance

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/ai/media/balance.ts`；SHA256 `7b5c1244a86fc0d191ab7cfce5e3d03ef0c35abf76c7d01f3d375f2154bcd82d`。
- 原始调用点：apps/web/src/lib/providerBilling.ts。
- 实际直写调用：`apps/web/src/lib/providerBilling.ts:15`。
- 注意：上游可接收 apiKey；目标由公司密钥服务注入，不能接收成员传入的任意 Key。

#### 请求参数

位置：`body`；校验入口 `{

  providerId: z.string().regex(/^[a-z][a-zA-Z0-9]{0,95}$/), apiKey: z.string().max(16384).optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `providerId` | 是 | `z.string().regex(/^[a-z][a-zA-Z0-9]{0,95}$/)` |
| `apiKey` | 否 | `z.string().max(16384).optional()` |

#### 返回数据与验证

基础样例 `M0-R021-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R021]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R022 GET /api/ai/media/balanceEvents

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/ai/media/balanceEvents.ts`；SHA256 `432305b43fe00771c950ce7a18bed1f5e6e142cb1fd6bb86758dcc4169fb4912`。
- 原始调用点：apps/web/src/lib/providerBilling.ts。
- 实际直写调用：`apps/web/src/lib/providerBilling.ts:126`。

#### 请求参数

位置：`query`；校验入口 `{

  accountIds: z.string().max(6499).regex(/^[a-f0-9]{64}(,[a-f0-9]{64}){0,99}$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `accountIds` | 是 | `z.string().max(6499).regex(/^[a-f0-9]{64}(,[a-f0-9]{64}){0,99}$/)` |

#### 返回数据与验证

基础样例 `M0-R022-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R022]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R023 POST /api/ai/media/generate

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/ai/media/generate；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：图片/视频/独立音频运行各 1；Agent 内嵌沿用父名额。
- 实现：`apps/server/src/routes/ai/media/generate.ts`；SHA256 `4e26abe261581a59408e9449e718cb4b2cb9e3cd86ab9697cb428bf3fcf34423`。
- 原始调用点：packages/nodeScaffold/src/nodeAi.ts（内置节点）。
- 实际直写调用：`packages/nodeScaffold/src/nodeAi.ts:251`。
- 注意：还须按 mediaType 校验 packages/tools/mediaGeneration/src/runtime.ts 中 image/video/audioGenerationSchema；媒体 HTTP 200 不代表业务成功。

#### 请求参数

位置：`body`；校验入口 `{

  directory: z.string().min(1).max(4096), mediaType: z.enum(["image", "video", "audio"]),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `mediaType` | 是 | `z.enum(["image", "video", "audio"])` |

#### 返回数据与验证

基础样例 `M0-R023-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R023]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R024 GET /api/ai/media/models

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/ai/media/models；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/ai/media/models.ts`；SHA256 `662c98826c5bdda99262e001f8b7875bc2c59844454f9521fc82a21e441e8c47`。
- 原始调用点：packages/nodeScaffold/src/nodeAi.ts（内置节点）。
- 实际直写调用：`packages/nodeScaffold/src/nodeAi.ts:93`。
- 实际直写调用：`packages/nodeScaffold/src/nodeAi.ts:180`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R024-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R024]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R025 POST /api/ai/media/price

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/ai/media/price；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/ai/media/price.ts`；SHA256 `d2404f34d13329c4967340de6f3cedf52e5b44e8535ba95b5c9ffa93d76bfa96`。
- 原始调用点：packages/nodeScaffold/src/nodeAi.ts（内置节点）。
- 实际直写调用：`packages/nodeScaffold/src/nodeAi.ts:218`。
- 注意：还须按 mediaType 使用同一生成 schema（prompt 在报价时允许空字符串）；不得因价格查询触发生成。

#### 请求参数

位置：`body`；校验入口 `{

  directory: z.string().min(1).max(4096).optional(), mediaType: z.enum(["image", "video", "audio"]),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 否 | `z.string().min(1).max(4096).optional()` |
| `mediaType` | 是 | `z.enum(["image", "video", "audio"])` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R025-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R025]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R026 GET /api/ai/models

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/ai/models；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/ai/models.ts`；SHA256 `7489911f662f200d8ae93151eaa3b806391b0ada2d1ccccb833fe350503d6fef`。
- 原始调用点：packages/nodeScaffold/src/nodeAi.ts（内置节点）。
- 实际直写调用：`packages/nodeScaffold/src/nodeAi.ts:93`。
- 实际直写调用：`packages/nodeScaffold/src/nodeAi.ts:176`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R026-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R026]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## assets

### R027 GET /api/assets/list

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/assets/list；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/assets/list.ts`；SHA256 `7c32b383a8500712922ba95075df1935488c8bcb1f68a2df5cd24b0936800630`。
- 原始调用点：apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue:222`。
- 注意：原版素材库根目录是全局 data/assets；新平台须明确改为项目素材或获授权组织素材，不能直接共享原根目录。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R027-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R027]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R028 POST /api/assets/mkdir

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/assets/mkdir；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/assets/mkdir.ts`；SHA256 `2ab127b18ad4f8f2f5fa0e98b91ef7f1f5e9852aec4c3901da2b55da722e3d2f`。
- 原始调用点：apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue<br>apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue:275`。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue:112`。

#### 请求参数

位置：`body`；校验入口 `{ path: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `path` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R028-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R028]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R029 GET /api/assets/read

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/assets/read；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/assets/read.ts`；SHA256 `3dc0da8fffb4e34692d0bad8cf847e15d441be9c337a62cb16bb3272540450db`。
- 原始调用点：apps/web/src/components/agent/mentionContent.vue<br>apps/web/src/components/agent/mentionThumbnail.vue 等 5 处。
- 实际直写调用：`apps/web/src/components/agent/mentionContent.vue:70`。
- 实际直写调用：`apps/web/src/components/agent/mentionThumbnail.vue:31`。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/canvasDrop.ts:83`。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue:125`。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue:130`。

#### 请求参数

位置：`query`；校验入口 `{ path: z.string().min(1).max(4096), download: z.literal("true").optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `path` | 是 | `z.string().min(1).max(4096)` |
| `download` | 否 | `z.literal("true").optional()` |

#### 返回数据与验证

基础样例 `M0-R029-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R029]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R030 DELETE /api/assets/remove

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/assets/remove；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/assets/remove.ts`；SHA256 `0c18d45b7914b0ff7cb4da698a8a4a888b4e6b6f6a1daa576b06f35cc4ebd96f`。
- 原始调用点：apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue:147`。

#### 请求参数

位置：`body`；校验入口 `{ path: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `path` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R030-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R030]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R031 POST /api/assets/rename

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/assets/rename；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/assets/rename.ts`；SHA256 `69d7f20ae248bc705b2a73c4024c634eac2417466430f0eb8cce2d3986a7ce81`。
- 原始调用点：apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue:82`。

#### 请求参数

位置：`body`；校验入口 `{ path: z.string().min(1).max(4096), target: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `path` | 是 | `z.string().min(1).max(4096)` |
| `target` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R031-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R031]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R032 PUT /api/assets/save

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/assets/save；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/assets/save.ts`；SHA256 `70d57a0989cba122fbb7a0dad7ad2cfeee84813be4729daf942c7c21b9800644`。
- 原始调用点：apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue:304`。
- 注意：上游 Body 为 application/octet-stream；path 位于 query。

#### 请求参数

位置：`query`；校验入口 `{ path: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `path` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R032-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R032]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## browser

### R033 POST /api/browser/input

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/browser/input.ts`；SHA256 `0abd2ec3de4b89317f681fc0a794194a09eabb9be6f3f372f381d1c56b80c7b6`。
- 原始调用点：packages/tools/browser/src/browserPanel.vue（须同步移除内置浏览器入口）。
- 实际直写调用：`packages/tools/browser/src/browserPanel.vue:197`。
- 实际直写调用：`packages/tools/browser/src/browserPanel.vue:347`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 否 | `z.string().min(1).max(4096).optional()` |
| `toolSessionId` | 否 | `z.uuid().optional()` |
| `sessionId` | 是 | `z.uuid()` |
| `tabId` | 是 | `z.uuid()` |
| `input` | 是 | `browserInputSchema` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R034 GET /api/browser/stream

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/browser/stream.ts`；SHA256 `173d89ba58e259f22785e0e5c55c1fca17f39912f612c1161419ccaba3097704`。
- 原始调用点：packages/tools/browser/src/browserPanel.vue（须同步移除内置浏览器入口）。
- 实际直写调用：`packages/tools/browser/src/browserPanel.vue:263`。

#### 请求参数

位置：`query`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 否 | `z.string().min(1).max(4096).optional()` |
| `toolSessionId` | 否 | `z.uuid().optional()` |
| `sessionId` | 是 | `z.uuid()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## desktop

### R035 POST /api/desktop/clipboard/read

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/clipboard/read.ts`；SHA256 `e63e6ef57cf94b5164c0d2e67c6aba852a92f44a0268c1913a0886f3f13a5ad6`。
- 原始调用点：apps/web/src/lib/clipboard.ts。
- 实际直写调用：`apps/web/src/lib/clipboard.ts:8`。
- 实际直写调用：`apps/web/src/lib/clipboard.ts:15`。
- 实际直写调用：`apps/web/src/lib/clipboard.ts:38`。

#### 请求参数

位置：`body`；校验入口 `{ format: z.enum(["image", "files"]).optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `format` | 否 | `z.enum(["image", "files"]).optional()` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R036 POST /api/desktop/clipboard/write

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/clipboard/write.ts`；SHA256 `f0e2c9e35b5da8cc66df1c989d6491647c971a5116a60cde59564aacbd18ebf7`。
- 原始调用点：apps/web/src/lib/clipboard.ts。
- 实际直写调用：`apps/web/src/lib/clipboard.ts:82`。
- 实际直写调用：`apps/web/src/lib/clipboard.ts:94`。

#### 请求参数

位置：`body`；校验入口 `{

  text: z.string().optional(),

  format: z.literal("image").optional(),

  image: z.string().optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `text` | 否 | `z.string().optional()` |
| `format` | 否 | `z.literal("image").optional()` |
| `image` | 否 | `z.string().optional()` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R037 POST /api/desktop/devtools

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/devtools.ts`；SHA256 `b01dea2a482626ac5bbfc838685b67f217fd0b18f01921b2f0e25b3026e6deb8`。
- 原始调用点：apps/web/src/components/settings/panels/developer/index.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/index.vue:382`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R038 POST /api/desktop/openUrl

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/openUrl.ts`；SHA256 `6161bde21dc2ffcb4fd53d7dff76e686e5c42e7f0b0789a4a002f15896b34dc0`。
- 原始调用点：未发现直写路径；需查封装/内置插件。

#### 请求参数

位置：`body`；校验入口 `{ url: z.string().min(1).max(8192) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `url` | 是 | `z.string().min(1).max(8192)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R039 POST /api/desktop/plugins/install

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/plugins/install.ts`；SHA256 `26ba86559fffcffb178a5d6d86fd2bdc3d1dc47fcfb332a7abc06bbfd25151ea`。
- 原始调用点：apps/web/src/lib/desktopProtocol.ts。
- 实际直写调用：`apps/web/src/lib/desktopProtocol.ts:50`。

#### 请求参数

位置：`body`；校验入口 `{

  type: z.enum(["node", "tool", "skill", "provider", "agent", "ext"]),

  url: z.string().url().max(4096),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `type` | 是 | `z.enum(["node", "tool", "skill", "provider", "agent", "ext"])` |
| `url` | 是 | `z.string().url().max(4096)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R040 POST /api/desktop/providerFile/read

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/providerFile/read.ts`；SHA256 `e0d1067817a218b9d84d9e18de9e195372d506fdaeaeaf7cca313ff88e9b1970`。
- 原始调用点：apps/web/src/components/settings/panels/developer/providerDebugDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/providerDebugDialog.vue:185`。

#### 请求参数

位置：`body`；校验入口 `{ token: z.string().uuid() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `token` | 是 | `z.string().uuid()` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R041 POST /api/desktop/providerFile/select

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/providerFile/select.ts`；SHA256 `051eb23c45c52bf6fa0f727a0d1b01a08b92e5f139166814a77480c626427554`。
- 原始调用点：apps/web/src/components/settings/panels/developer/providerDebugDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/providerDebugDialog.vue:220`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R042 POST /api/desktop/ready

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/ready.ts`；SHA256 `06d45e86289b74f5a80034c28935ce279218344960f3464f194c0849dc92cfb3`。
- 原始调用点：apps/web/src/main.ts。
- 实际直写调用：`apps/web/src/main.ts:32`。

#### 请求参数

位置：`body`；校验入口 `{ failed: z.boolean().optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `failed` | 否 | `z.boolean().optional()` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R043 POST /api/desktop/saveFile

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/saveFile.ts`；SHA256 `62a3934e2268629f95987435f195d793e38b5b8f5f5b87c59d354b34c74ddc7c`。
- 原始调用点：apps/web/src/lib/saveFile.ts。
- 实际直写调用：`apps/web/src/lib/saveFile.ts:46`。

#### 请求参数

位置：`query`；校验入口 `{

  token: z.string().min(1).max(200),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `token` | 是 | `z.string().min(1).max(200)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R044 POST /api/desktop/selectDirectory

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/selectDirectory.ts`；SHA256 `c0899d408838a19506655bb98048962e1b1da1b6f3be0299802e1c361e444736`。
- 原始调用点：apps/web/src/pages/home/workspacePicker.vue。
- 实际直写调用：`apps/web/src/pages/home/workspacePicker.vue:69`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R045 POST /api/desktop/selectSaveFile

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/selectSaveFile.ts`；SHA256 `242c453134f33bae850796f503cc755a9a815817935914e02a28fe0e5b676f87`。
- 原始调用点：apps/web/src/lib/saveFile.ts。
- 实际直写调用：`apps/web/src/lib/saveFile.ts:38`。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: z.string().min(1).max(240).regex(/^[^\\/:*?"<>|\u0000-\u001f]+$/).refine(name => name !== "." && name !== ".." && !/[. ]$/.test(name)),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 是 | `z.string().min(1).max(240).regex(/^[^\\/:*?"<>\|\u0000-\u001f]+$/).refine(name => name !== "." && name !== ".." && !/[. ]$/.test(name))` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R046 POST /api/desktop/update/apply

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/update/apply.ts`；SHA256 `a05b09d9ab669ffdd259ee8b3cae9f836e9a64b692e8830c7cf0c76379a06865`。
- 原始调用点：未发现直写路径；需查封装/内置插件。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R047 POST /api/desktop/update/check

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/update/check.ts`；SHA256 `84666e17e64405b0455b4dfddb1f1181eabcbe704d7acab07103a06b7dbf3e18`。
- 原始调用点：未发现直写路径；需查封装/内置插件。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R048 POST /api/desktop/update/download

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/update/download.ts`；SHA256 `e94202b01c0f3eff5d32ce0ad704c45317dbc1260c8b1345916b6f3cde85419a`。
- 原始调用点：未发现直写路径；需查封装/内置插件。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R049 GET /api/desktop/update

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/desktop/update/index.ts`；SHA256 `150bf8cb146408f6512d9a1edb08b38b522f511e79925cb1006bc5133fab1e9b`。
- 原始调用点：apps/web/src/components/settings/panels/about.vue<br>apps/web/src/components/settings/panels/developer/index.vue 等 3 处。
- 实际直写调用：`apps/web/src/components/settings/panels/about.vue:296`。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/index.vue:161`。
- 实际直写调用：`apps/web/src/stores/desktopUpdate.ts:52`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## ext

### R050 POST /api/ext/install

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/ext/install.ts`；SHA256 `f69619639f19d2d13bb97219bb59d587a353a3c629af2c6d442cc5a2bfaa8a87`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: z.string().max(128).optional(), source: z.string().max(20 * 1024 * 1024).optional(),

  url: z.string().url().max(4096).optional(), force: z.boolean().optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 否 | `z.string().max(128).optional()` |
| `source` | 否 | `z.string().max(20 * 1024 * 1024).optional()` |
| `url` | 否 | `z.string().url().max(4096).optional()` |
| `force` | 否 | `z.boolean().optional()` |

#### 返回数据与验证

基础样例 `M0-R050-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R050]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R051 GET /api/ext/list

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/ext/list.ts`；SHA256 `6986e81e67edbe0eca8bfa581bbe61ca0db536401a88ef63a0c7007c33a6078a`。
- 原始调用点：apps/web/src/pages/workspace/panels/document/extensions.ts。
- 实际直写调用：`apps/web/src/pages/workspace/panels/document/extensions.ts:53`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R051-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R051]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R052 GET /api/ext/read

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/ext/read.ts`；SHA256 `a79b118f2dd842e5d9e0a0a4187f23243bbfcf7e2d85c8354fa99830825e895e`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/server/src/routes/ext/list.ts 返回 url=/api/ext/read?name=...；apps/web/src/pages/workspace/panels/document/extensions.ts 的 loadExtension 读取该 URL。

#### 请求参数

位置：`query`；校验入口 `{ name: u.extPlugins.extNameSchema }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.extPlugins.extNameSchema` |

#### 返回数据与验证

基础样例 `M0-R052-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R052]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R053 PUT /api/ext/save

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/ext/save.ts`；SHA256 `19663006e6d01b7cbd1db4f1faad1c5f9d185b506904403008f1deec9a611eda`。
- 原始调用点：apps/web/src/pages/workspace/panels/document/extensions.ts。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。
- 实际直写调用：`apps/web/src/pages/workspace/panels/document/extensions.ts:146`。

#### 请求参数

位置：`body`；校验入口 `{ name: u.extPlugins.extNameSchema, config: z.record(z.string(), z.json()) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.extPlugins.extNameSchema` |
| `config` | 是 | `z.record(z.string(), z.json())` |

#### 返回数据与验证

基础样例 `M0-R053-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R053]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R054 PUT /api/ext/setEnabled

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/ext/setEnabled.ts`；SHA256 `88b205574bfc1d8fa0a1dd47b27963ff5c6f6daf72128d0666fcce33f194200a`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: u.extPlugins.extNameSchema, enabled: z.boolean() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.extPlugins.extNameSchema` |
| `enabled` | 是 | `z.boolean()` |

#### 返回数据与验证

基础样例 `M0-R054-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R054]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R055 DELETE /api/ext/uninstall

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/ext/uninstall.ts`；SHA256 `313aa8f5e8daeaf5df638041154f36597f6440ae65f66dbdd6dbdbbc68d739a3`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: u.extPlugins.extNameSchema }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.extPlugins.extNameSchema` |

#### 返回数据与验证

基础样例 `M0-R055-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R055]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## ffmpeg

### R056 POST /api/ffmpeg/cancel

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/ffmpeg/cancel.ts`；SHA256 `54438cf5693dc2bd29ce04001fade1bf2b0c4fab8fa1a1f0cb0bea78a61a8cd9`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue:229；submitDownload(action) 构造 /api/ffmpeg/${action}。
- 注意：此 cancel 取消的是 FFmpeg 二进制下载，不能用于取消转码。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R056-INPUT`：`isolated-download-cancel`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R056]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R057 POST /api/ffmpeg/download

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/ffmpeg/download.ts`；SHA256 `65ed3e83a994ee7ca6e47d55edbf2397e2213116eccbbc808c8f28218f220a3f`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue:229；submitDownload(action) 构造 /api/ffmpeg/${action}。
- 注意：此 download 下载/安装 FFmpeg 程序，不能当作媒体文件下载。

#### 请求参数

位置：`body`；校验入口 `{ source: z.string().min(1).max(40) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `source` | 是 | `z.string().min(1).max(40)` |

#### 返回数据与验证

基础样例 `M0-R057-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R057]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R058 GET /api/ffmpeg/events

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/ffmpeg/events.ts`；SHA256 `cca20efcb86ef20b3e2dc049700cc2f75666c874861d7fc992a783cd63698bcc`。
- 原始调用点：apps/web/src/components/settings/ffmpegRequired.vue。
- 实际直写调用：`apps/web/src/components/settings/ffmpegRequired.vue:16`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R058-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `text/event-stream; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R058]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R059 POST /api/ffmpeg/execute

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/ffmpeg/execute；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/ffmpeg/execute.ts`；SHA256 `dabe74c0484c7f6bf31602d0ea52ec09bc5d10c2383066a0b461eca977d7b94b`。
- 原始调用点：packages/ffmpeg/src/browser.ts（内置 FFmpeg）。
- 实际直写调用：`packages/ffmpeg/src/browser.ts:33`。
- 注意：prepare 为 JSON；执行为 SSE。转码取消通过相同 requestId 的 operation.method=cancel。原始 inputOptions/outputOptions/滤镜可能间接访问路径，不能把显式文件检查当作进程沙箱；M3 需限制方法/参数或使用项目隔离进程。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `requestId` | 是 | `z.uuid()` |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `options` | 是 | `z.object({
     source: z.string().min(1).max(4096).optional(),
     cwd: z.string().min(1).max(4096).optional(),
     niceness: z.number().int().min(-20).max(20).optional(),
     priority: z.number().int().min(-20).max(20).optional(),
     stdoutLines: z.number().int().nonnegative().optional(),
     timeout: z.number().nonnegative().optional(),
   }).strict()` |
| `calls` | 是 | `z.array(callSchema).max(2048)` |
| `operation` | 是 | `callSchema` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R059-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R059]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R060 GET /api/ffmpeg/progress

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/ffmpeg/progress.ts`；SHA256 `c243167dc23e26754d18fd0dddc05e71c35612c34640878e3826ede8ca55606f`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue:171`。
- 注意：此 progress 是 FFmpeg 安装下载进度；媒体转码进度来自 execute SSE。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R060-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R060]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R061 GET /api/ffmpeg/status

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/ffmpeg/status.ts`；SHA256 `ae8ec0147a840e3afc46b9dd75205322b9e3d327e26e97d031bdd44f614de471`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue:191`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R061-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R061]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## mcp

### R062 GET /api/mcp/control/events

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/mcp/control/events；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/mcp/control/events.ts`；SHA256 `294f2db735cc840f8304caec2c64e291a11c3dfdcca4ed07c887383fec97909e`。
- 原始调用点：apps/web/src/lib/mcpControl.ts。
- 实际直写调用：`apps/web/src/lib/mcpControl.ts:160`。
- 注意：connectionId 不能单独作为授权；须绑定账号、项目和当前画布。

#### 请求参数

位置：`query`；校验入口 `{ connectionId: z.uuid() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `connectionId` | 是 | `z.uuid()` |

#### 返回数据与验证

基础样例 `M0-R062-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R062]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R063 POST /api/mcp/control/result

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/mcp/control/result；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/mcp/control/result.ts`；SHA256 `ae49903b1c45d758ce0626fbc1afcdba8624de2a29f2e9760d15bcebbd8acd74`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/lib/mcpControl.ts:77-80；post(path) 构造 /api/mcp/control/${path}，path=result。

#### 请求参数

位置：`body`；校验入口 `{ connectionId: z.uuid(), callId: z.uuid(), result: z.json().optional(), error: z.string().max(20000).optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `connectionId` | 是 | `z.uuid()` |
| `callId` | 是 | `z.uuid()` |
| `result` | 否 | `z.json().optional()` |
| `error` | 否 | `z.string().max(20000).optional()` |

#### 返回数据与验证

基础样例 `M0-R063-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R063]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R064 POST /api/mcp/control/state

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/mcp/control/state；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/mcp/control/state.ts`；SHA256 `c2b6376dc0454db02bc6d70213d714b5961bdf61268eab2023cf9c7184617c6c`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/lib/mcpControl.ts:77-80；post(path) 构造 /api/mcp/control/${path}，path=state。

#### 请求参数

位置：`body`；校验入口 `{ connectionId: z.uuid(), revision: z.number().int().positive(), state: u.mcpControl.controlStateSchema }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `connectionId` | 是 | `z.uuid()` |
| `revision` | 是 | `z.number().int().positive()` |
| `state` | 是 | `u.mcpControl.controlStateSchema` |

#### 返回数据与验证

基础样例 `M0-R064-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R064]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R065 GET /api/mcp/skill

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/mcp/skill.ts`；SHA256 `e2920357d2ddfdccaf8ec9b685d2277e2c2c824efb8eeba0c074fc81a849ad1e`。
- 原始调用点：apps/web/src/components/settings/panels/mcp/index.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/mcp/index.vue:201`。
- 注意：skill 内容使用获批清单并去除执行器地址/绝对目录；外部 MCP 用自己的凭证入口。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R065-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `text/markdown; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R065]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R066 GET /api/mcp/status

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/mcp/status.ts`；SHA256 `9bff0a2887e4da0c6b05d1d0433767b8627ad4d1ee65220caff2dfc0f9ac0d16`。
- 原始调用点：apps/web/src/components/settings/panels/mcp/index.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/mcp/index.vue:124`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R066-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R066]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## mobileLink

### R067 PUT /api/mobileLink/config

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/mobileLink/config.ts`；SHA256 `55a2f762926d9b2834381f0a9f10b1c1c7b9938621b928c847f6d5100d82de60`。
- 原始调用点：apps/web/src/components/settings/panels/deviceLink.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/deviceLink.vue:258`。

#### 请求参数

位置：`body`；校验入口 `{ enabled: z.boolean(), port: z.number().int().min(1).max(65535),

  publicUrl: z.string().max(2048), directories: z.array(z.string().min(1).max(4096)).max(1000) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `enabled` | 是 | `z.boolean()` |
| `port` | 是 | `z.number().int().min(1).max(65535)` |
| `publicUrl` | 是 | `z.string().max(2048)` |
| `directories` | 是 | `z.array(z.string().min(1).max(4096)).max(1000)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R068 GET /api/mobileLink/heartbeat

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/mobileLink/heartbeat.ts`；SHA256 `224b7aab2b42e7f81fffa73b279bda8aaa441fb9c6307d7daa5b23603e0a05d0`。
- 原始调用点：未发现直写路径；需查封装/内置插件。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R069 POST /api/mobileLink/pair

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/mobileLink/pair.ts`；SHA256 `0bf4b5d277737cb34aa117e947f5ede7c68ab3e1a8ee31bc4f113e3f96e02b43`。
- 原始调用点：未发现直写路径；需查封装/内置插件。

#### 请求参数

位置：`body`；校验入口 `{ code: z.union([z.string().regex(/^\d{12}$/), z.string().min(32).max(128)]), name: z.string().trim().min(1).max(100) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `code` | 是 | `z.union([z.string().regex(/^\d{12}$/), z.string().min(32).max(128)])` |
| `name` | 是 | `z.string().trim().min(1).max(100)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R070 POST /api/mobileLink/pairing

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/mobileLink/pairing.ts`；SHA256 `1bce2642602bb9dceed71eee7c1fca8c77fcbe8e01ec3e67e0ea88b313a4de22`。
- 原始调用点：apps/web/src/components/settings/panels/deviceLink.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/deviceLink.vue:302`。

#### 请求参数

位置：`body`；校验入口 `{ url: z.url().max(2048) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `url` | 是 | `z.url().max(2048)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R071 POST /api/mobileLink/revoke

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/mobileLink/revoke.ts`；SHA256 `07d7b728cf2b45c448e57ab6071b33a2114c66a719d7866cf4d5284377a58af5`。
- 原始调用点：apps/web/src/components/settings/panels/deviceLink.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/deviceLink.vue:320`。

#### 请求参数

位置：`body`；校验入口 `{ id: z.string().min(1).max(100) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `id` | 是 | `z.string().min(1).max(100)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R072 GET /api/mobileLink/status

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/mobileLink/status.ts`；SHA256 `4d96b2467bfe700dfacd251a291728f9757c4a701d4d832721a5fab260dbaa78`。
- 原始调用点：apps/web/src/components/settings/panels/deviceLink.vue<br>apps/web/src/lib/mobile.ts。
- 实际直写调用：`apps/web/src/components/settings/panels/deviceLink.vue:214`。
- 实际直写调用：`apps/web/src/lib/mobile.ts:46`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## nodes

### R073 GET /api/nodes/files

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/nodes/files.ts`；SHA256 `b66972bd364a36ace38865612b26d0aec61bf21525e26361924b16c3f8534b61`。
- 原始调用点：apps/web/src/pages/workspace/panels/canvas/index.vue。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/index.vue:1074`。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/index.vue:1127`。

#### 请求参数

位置：`query`；校验入口 `{ name: z.string().regex(/^[a-z][a-zA-Z0-9]*$/) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(/^[a-z][a-zA-Z0-9]*$/)` |

#### 返回数据与验证

基础样例 `M0-R073-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R073]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R074 GET /api/nodes/get

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/nodes/get.ts`；SHA256 `5f791841a8728c0688236e38c61a0ba40926697d0dc23099a245f5288e6e9dba`。
- 原始调用点：apps/web/src/components/settings/panels/general/nodeMenuSettings.vue<br>apps/web/src/pages/workspace/panels/canvas/index.vue。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。
- 实际直写调用：`apps/web/src/components/settings/panels/general/nodeMenuSettings.vue:353`。
- 实际直写调用：`apps/web/src/pages/workspace/panels/canvas/index.vue:1121`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R074-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R074]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R075 POST /api/nodes/install

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/nodes/install.ts`；SHA256 `f1d78d764c220989593ca28d619b61cdf36f6aadbe5d010e1c3b86d542616662`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: z.string().max(128).optional(),

  source: z.string().max(maxBytes).optional(),

  url: z.string().url().max(4096).optional(),

  force: z.boolean().optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 否 | `z.string().max(128).optional()` |
| `source` | 否 | `z.string().max(maxBytes).optional()` |
| `url` | 否 | `z.string().url().max(4096).optional()` |
| `force` | 否 | `z.boolean().optional()` |

#### 返回数据与验证

基础样例 `M0-R075-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R075]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R076 PUT /api/nodes/save

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/nodes/save.ts`；SHA256 `f6196602faebb05e393ebbfa115b69bda032b81651d5b55e1712936d40b30c26`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: u.nodePlugins.nodeNameSchema, config: z.record(z.string(), z.json()) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.nodePlugins.nodeNameSchema` |
| `config` | 是 | `z.record(z.string(), z.json())` |

#### 返回数据与验证

基础样例 `M0-R076-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R076]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R077 PUT /api/nodes/setEnabled

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/nodes/setEnabled.ts`；SHA256 `c9eb7262a42b19eae7edec1247d01e4080cabb71542862aac9067d8c9348bc60`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().regex(/^[a-z][a-zA-Z0-9]*$/), enabled: z.boolean() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(/^[a-z][a-zA-Z0-9]*$/)` |
| `enabled` | 是 | `z.boolean()` |

#### 返回数据与验证

基础样例 `M0-R077-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R077]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R078 DELETE /api/nodes/uninstall

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/nodes/uninstall.ts`；SHA256 `c09ca2a372f4dc5bd6d87a168a2c74fe31900282e6d75cdf63bd394c00c21395`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().regex(/^[a-z][a-zA-Z0-9]*$/) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().regex(/^[a-z][a-zA-Z0-9]*$/)` |

#### 返回数据与验证

基础样例 `M0-R078-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R078]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## plugins

### R079 GET /api/plugins/export

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/plugins/export.ts`；SHA256 `ba28a0214447715951f18aec43e073b384c8bf3e98d03d183832bba599588777`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/index.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/index.vue:723`。
- 注意：导出仅获批内置或第三方制品；不含供应商 Key、个人凭证或项目私有数据。

#### 请求参数

位置：`query`；校验入口 `{

  type: z.enum(["node", "tool", "skill", "agent", "ext"]),

  name: z.string().min(1).max(96).regex(/^[a-zA-Z0-9][a-zA-Z0-9-]*$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `type` | 是 | `z.enum(["node", "tool", "skill", "agent", "ext"])` |
| `name` | 是 | `z.string().min(1).max(96).regex(/^[a-zA-Z0-9][a-zA-Z0-9-]*$/)` |

#### 返回数据与验证

基础样例 `M0-R079-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R079]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## providers

### R080 POST /api/providers/debug/inspect

#### 基本信息

- 1.0 处置：仅内部诊断。
- 目标权限：运维/管理员受控操作。
- 目标入口：1.0 不发布源码执行/检查代理；调试留在受控执行环境。
- 五名额：无；诊断不得调用真实供应商。
- 实现：`apps/server/src/routes/providers/debug/inspect.ts`；SHA256 `dfd1dafbee880e7dfbaeb15d7b811e938de5a6f8992263323f0c5622e31e903d`。
- 原始调用点：apps/web/src/components/settings/panels/developer/providerDebugDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/providerDebugDialog.vue:199`。

#### 请求参数

位置：`body`；校验入口 `{ source: u.providerDebug.providerDebugSchema.source }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `source` | 是 | `u.providerDebug.providerDebugSchema.source` |

#### 返回数据与验证

基础样例 `M0-R080-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R080]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R081 POST /api/providers/debug/run

#### 基本信息

- 1.0 处置：仅内部诊断。
- 目标权限：运维/管理员受控操作。
- 目标入口：1.0 不发布源码执行/检查代理；调试留在受控执行环境。
- 五名额：无；诊断不得调用真实供应商。
- 实现：`apps/server/src/routes/providers/debug/run.ts`；SHA256 `3f6dce582889470db9b75c165fc96ac4c5ef043684fcf6e55f450fdc5a341aac`。
- 原始调用点：apps/web/src/components/settings/panels/developer/providerDebugDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/providerDebugDialog.vue:346`。

#### 请求参数

位置：`body`；校验入口 `{

  ...u.providerDebug.providerDebugSchema,

  request: z.record(z.string(), z.json()),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| 展开 `u.providerDebug.providerDebugSchema` | 由引用 schema 定义 | 不能视为无额外字段；引用见 JSON imports/schemaDeclarations |
| `request` | 是 | `z.record(z.string(), z.json())` |

#### 返回数据与验证

基础样例 `M0-R081-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R081]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R082 POST /api/providers/media/add

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/providers/media/add.ts`；SHA256 `8e2aebeefa43f008b5036020a487d0b65c9b8e01cd9168100e3acbe0725ee964`。
- 原始调用点：apps/web/src/components/settings/panels/developer/providerDebugDialog.vue<br>apps/web/src/components/settings/panels/mediaModel/addCustomProviderDialog.vue 等 3 处。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/providerDebugDialog.vue:381`。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/addCustomProviderDialog.vue:234`。
- 实际直写调用：`apps/web/src/pages/hello/index.vue:155`。

#### 请求参数

位置：`body`；校验入口 `{ source: z.string().min(1).max(2 * 1024 * 1024) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `source` | 是 | `z.string().min(1).max(2 * 1024 * 1024)` |

#### 返回数据与验证

基础样例 `M0-R082-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R082]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R083 DELETE /api/providers/media/delete

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/providers/media/delete.ts`；SHA256 `b2b9de7279fce1192425be3aab99c02ec1b05caf9e333862d253f6317d0e16f0`。
- 原始调用点：apps/web/src/components/settings/panels/mediaModel/index.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/index.vue:142`。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: u.mediaProvider.mediaProviderFileSchema,

  revision: z.string().regex(/^[a-f0-9]{64}$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 是 | `u.mediaProvider.mediaProviderFileSchema` |
| `revision` | 是 | `z.string().regex(/^[a-f0-9]{64}$/)` |

#### 返回数据与验证

基础样例 `M0-R083-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R083]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R084 GET /api/providers/media/list

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/providers/media/list.ts`；SHA256 `430846cca2a468bb8029b75b7165965fe4ab7e8f79ff5e1b7ccff0ad07f0a6a3`。
- 原始调用点：apps/web/src/components/settings/panels/mediaModel/editProviderDialog.vue<br>apps/web/src/components/settings/panels/mediaModel/index.vue 等 4 处。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/editProviderDialog.vue:163`。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/index.vue:99`。
- 实际直写调用：`apps/web/src/lib/providerBilling.ts:159`。
- 实际直写调用：`apps/web/src/pages/hello/index.vue:151`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R084-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R084]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R085 POST /api/providers/media/models

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/providers/media/models.ts`；SHA256 `b23dac4b2022e339e5ab411e56c7e11c9eb0721f3073135211816364840f41e8`。
- 原始调用点：apps/web/src/components/settings/panels/mediaModel/index.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/index.vue:125`。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: u.mediaProvider.mediaProviderFileSchema,

  revision: z.string().regex(/^[a-f0-9]{64}$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 是 | `u.mediaProvider.mediaProviderFileSchema` |
| `revision` | 是 | `z.string().regex(/^[a-f0-9]{64}$/)` |

#### 返回数据与验证

基础样例 `M0-R085-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R085]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R086 PUT /api/providers/media/save

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/providers/media/save.ts`；SHA256 `650677950ac39d87f56d5076bc0472619b5479337583a148a891036f151828fa`。
- 原始调用点：apps/web/src/components/settings/panels/mediaModel/editProviderDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/editProviderDialog.vue:147`。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: u.mediaProvider.mediaProviderFileSchema,

  models: u.mediaProvider.mediaModelsSchema,

  revision: z.string().regex(/^[a-f0-9]{64}$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 是 | `u.mediaProvider.mediaProviderFileSchema` |
| `models` | 是 | `u.mediaProvider.mediaModelsSchema` |
| `revision` | 是 | `z.string().regex(/^[a-f0-9]{64}$/)` |

#### 返回数据与验证

基础样例 `M0-R086-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R086]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R087 POST /api/providers/media/validate

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/providers/media/validate.ts`；SHA256 `966a9e40b8ac9ff262f4a063608bfb352e748d97e3bf9ef4350ea69c1d5d5396`。
- 原始调用点：apps/web/src/components/settings/panels/mediaModel/providerAgentDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/providerAgentDialog.vue:115`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `source` | 是 | `z.string().min(1).max(2 * 1024 * 1024)` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R087-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R087]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R088 POST /api/providers/models

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/providers/models.ts`；SHA256 `88d577a5d1f25f1e43bd63953e10b8abfb060ab8c8361fad74b973b075a04b9a`。
- 原始调用点：apps/web/src/components/settings/panels/languageModel/addCustomProviderDialog.vue<br>apps/web/src/components/settings/panels/languageModel/addProviderDialog.vue 等 3 处。
- 实际直写调用：`apps/web/src/components/settings/panels/languageModel/addCustomProviderDialog.vue:220`。
- 实际直写调用：`apps/web/src/components/settings/panels/languageModel/addProviderDialog.vue:111`。
- 实际直写调用：`apps/web/src/components/settings/panels/languageModel/index.vue:113`。

#### 请求参数

位置：`body`；校验入口 `{

  apiUrl: z.url().refine(value => {

    const url = new URL(value);

    return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash;

  }, "请填写不含查询参数的 HTTP API 基础地址"),

  protocol: z.enum(["openai-completions", "openai-responses", "anthropic-messages"]),

  apiKey: z.string().max(8192),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `apiUrl` | 是 | `z.url().refine(value => {
     const url = new URL(value);
     return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash;
   }, "请填写不含查询参数的 HTTP API 基础地址")` |
| `protocol` | 是 | `z.enum(["openai-completions", "openai-responses", "anthropic-messages"])` |
| `apiKey` | 是 | `z.string().max(8192)` |

#### 返回数据与验证

基础样例 `M0-R088-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R088]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## settings

### R089 GET /api/settings/get

#### 基本信息

- 1.0 处置：拆分配置 DTO。
- 目标权限：本人偏好 / 管理员公司配置。
- 目标入口：个人偏好用安全字段 DTO；公司 Key 转 /api/v1/admin/providers；原始完整设置不公开。
- 五名额：无。
- 实现：`apps/server/src/routes/settings/get.ts`；SHA256 `40dcf0f550ff095a04d9a41f74752f8f492f14e14e4b820dcf5c2ea7e7d76f95`。
- 原始调用点：apps/web/src/stores/settings.ts。
- 实际直写调用：`apps/web/src/stores/settings.ts:103`。
- 注意：原设置响应包含供应商 Key。禁止将原 JSON 透传给成员；需拆分安全 DTO。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R089-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R089]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R090 GET /api/settings/personalization/get

#### 基本信息

- 1.0 处置：个人配置适配。
- 目标权限：本人；项目规则另校验成员。
- 目标入口：个人资料绑定当前账号；项目资料绑定项目；禁止读写其他账号或全局规则。
- 五名额：无。
- 实现：`apps/server/src/routes/settings/personalization/get.ts`；SHA256 `0ecf751cd1a82e9ed6a2616cac0950d02c65b80c8207947f02565fc78a0dfb78`。
- 原始调用点：apps/web/src/components/settings/panels/personalization.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/personalization.vue:196`。
- 实际直写调用：`apps/web/src/components/settings/panels/personalization.vue:276`。

#### 请求参数

位置：`query`；校验入口 `{ document: z.enum(["memory", "agents"]) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `document` | 是 | `z.enum(["memory", "agents"])` |

#### 返回数据与验证

基础样例 `M0-R090-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R090]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R091 PUT /api/settings/personalization/save

#### 基本信息

- 1.0 处置：个人配置适配。
- 目标权限：本人；项目规则另校验成员。
- 目标入口：个人资料绑定当前账号；项目资料绑定项目；禁止读写其他账号或全局规则。
- 五名额：无。
- 实现：`apps/server/src/routes/settings/personalization/save.ts`；SHA256 `42f840f177de4737de78313fdeef88732aaa62b59e563a903ca3c3bc5c5b7696`。
- 原始调用点：apps/web/src/components/settings/panels/personalization.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/personalization.vue:237`。
- 实际直写调用：`apps/web/src/components/settings/panels/personalization.vue:318`。

#### 请求参数

位置：`body`；校验入口 `{

  document: z.enum(["memory", "agents"]),

  content: z.string().max(u.personalization.maxDocumentLength),

  revision: z.string().regex(/^[a-f0-9]{64}$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `document` | 是 | `z.enum(["memory", "agents"])` |
| `content` | 是 | `z.string().max(u.personalization.maxDocumentLength)` |
| `revision` | 是 | `z.string().regex(/^[a-f0-9]{64}$/)` |

#### 返回数据与验证

基础样例 `M0-R091-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R091]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R092 PUT /api/settings/save

#### 基本信息

- 1.0 处置：拆分配置 DTO。
- 目标权限：本人偏好 / 管理员公司配置。
- 目标入口：个人偏好用安全字段 DTO；公司 Key 转 /api/v1/admin/providers；原始完整设置不公开。
- 五名额：无。
- 实现：`apps/server/src/routes/settings/save.ts`；SHA256 `460f823267202a8f9f7879c3dd380d2d5b6551b8f3348324e614f6e1f62ad1ac`。
- 原始调用点：apps/web/src/stores/settings.ts。
- 实际直写调用：`apps/web/src/stores/settings.ts:121`。

#### 请求参数

位置：`body`；校验入口 `{ settings: z.record(z.string(), z.json()).and(z.object({

  agentSystemPrompt: z.string().max(maxSystemPromptLength, { error: () => t`系统提示词不能超过 ${maxSystemPromptLength} 个字符` }).optional(),

  desktopUpdateSource: z.enum(["official", "github", "custom"]).optional(),

  desktopUpdateCustomUrl: z.string().max(2048).refine(value => !value || u.desktop.isValidUpdateUrl(value),

    "自定义更新源必须是不含账号、查询参数或锚点的 HTTP(S) 地址").optional(),

  mcp: z.object({ enabled: z.boolean().optional(), token: z.string().optional(), port: z.number().int().min(1).max(65535).optional() }).optional(),

})).refine(value => value.desktopUpdateSource !== "custom" || !!value.desktopUpdateCustomUrl, {

  path: ["desktopUpdateCustomUrl"], message: "选择自定义更新源前，请先填写有效地址",

}) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `settings` | 是 | `z.record(z.string(), z.json()).and(z.object({
   agentSystemPrompt: z.string().max(maxSystemPromptLength, { error: () => t`系统提示词不能超过 ${maxSystemPromptLength} 个字符` }).optional(),
   desktopUpdateSource: z.enum(["official", "github", "custom"]).optional(),
   desktopUpdateCustomUrl: z.string().max(2048).refine(value => !value \|\| u.desktop.isValidUpdateUrl(value),
     "自定义更新源必须是不含账号、查询参数或锚点的 HTTP(S) 地址").optional(),
   mcp: z.object({ enabled: z.boolean().optional(), token: z.string().optional(), port: z.number().int().min(1).max(65535).optional() }).optional(),
 })).refine(value => value.desktopUpdateSource !== "custom" \|\| !!value.desktopUpdateCustomUrl, {
   path: ["desktopUpdateCustomUrl"], message: "选择自定义更新源前，请先填写有效地址",
 })` |

#### 返回数据与验证

基础样例 `M0-R092-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R092]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R093 GET /api/settings/systemPrompt

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/settings/systemPrompt.ts`；SHA256 `d25170c00b843d74d9a643219ec543eab70dfdbcf3a000dead2cbe0a561a7a98`。
- 原始调用点：apps/web/src/components/settings/panels/developer/systemPromptDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/developer/systemPromptDialog.vue:51`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R093-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R093]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## singleAgent

### R094 POST /api/singleAgent/answer

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/singleAgent/answer；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/singleAgent/answer.ts`；SHA256 `c22f99994e0a39035a7b0814c5e00cc6b61b9c8754cf374d7f9c841c966c4c13`。
- 原始调用点：packages/tools/askUser/src/questionCard.vue（内置提问器）。
- 实际直写调用：`packages/tools/askUser/src/questionCard.vue:147`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `sessionId` | 是 | `z.uuid()` |
| `callId` | 是 | `z.uuid()` |
| `cancelled` | 否 | `z.boolean().optional()` |
| `skipped` | 否 | `z.boolean().optional()` |
| `answer` | 否 | `z.string().trim().min(1).max(8000).optional()` |
| `values` | 否 | `z.record(z.string().max(64), z.union([z.string().max(8000), z.number(), z.boolean(), z.null(), z.array(z.string().max(300)).max(20)])).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R094-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R094]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R095 POST /api/singleAgent/control

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/singleAgent/control；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/singleAgent/control.ts`；SHA256 `10908170aa6a0b8f62ec8c78eebe9bf6120f99cabfb8ea49f6ea7aa1fbaf6cc1`。
- 原始调用点：apps/web/src/components/singleAgentDialog.vue。
- 实际直写调用：`apps/web/src/components/singleAgentDialog.vue:135`。
- 实际直写调用：`apps/web/src/components/singleAgentDialog.vue:264`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `sessionId` | 是 | `z.uuid()` |
| `runId` | 否 | `z.uuid().optional()` |
| `action` | 是 | `z.enum(["pause", "resume", "stop", "close"])` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R095-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R095]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R096 POST /api/singleAgent/create

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/singleAgent/create；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/singleAgent/create.ts`；SHA256 `7c81fbbe071198dfd64756ce86900df32b365fc6e23605c99be0ddd6239a84ed`。
- 原始调用点：apps/web/src/components/singleAgentDialog.vue。
- 实际直写调用：`apps/web/src/components/singleAgentDialog.vue:147`。
- 注意：原工具声明与系统提示可由调用方提供；目标仅允许获批工具与受控配置，sessionId 绑定账号/项目。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `tools` | 是 | `z.array(z.union([
     toolName,
     z.strictObject({ plugin: toolName, names: z.array(toolName).min(1).refine(names => new Set(names).size === names.length, "工具名称不能重复").optional() }),
     z.strictObject({
       name: toolName, label: z.string().trim().min(1).max(200).optional(), description: z.string().trim().min(1).max(20000),
       parameters: z.record(z.string(), z.json()).refine(value => value.type === "object" && JSON.stringify(value).length <= 100000, "工具参数必须是 JSON 对象结构，且不超过 100 KB"),
       executionMode: z.enum(["sequential", "parallel"]).optional(),
     }),
   ])).max(128)` |
| `directory` | 否 | `z.string().min(1).max(4096).optional()` |
| `systemPrompt` | 否 | `z.string().max(100000).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R096-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R096]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R097 POST /api/singleAgent/run

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/singleAgent/run；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：整个运行 1；子工具/子 Agent 沿用父名额。
- 实现：`apps/server/src/routes/singleAgent/run.ts`；SHA256 `9c53d4e62840f0b858c596ff4d29507787d8da3db0877a3ec725777e0789262b`。
- 原始调用点：apps/web/src/components/singleAgentDialog.vue。
- 实际直写调用：`apps/web/src/components/singleAgentDialog.vue:221`。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `sessionId` | 是 | `z.uuid()` |
| `runId` | 是 | `z.uuid()` |
| `prompt` | 是 | `z.string().trim().min(1).max(100000)` |
| `providerId` | 是 | `z.string().min(1).max(200)` |
| `modelId` | 是 | `z.string().min(1).max(500)` |
| `thinkingLevel` | 否 | `z.enum(["off", "minimal", "low", "medium", "high", "xhigh"]).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R097-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R097]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R098 POST /api/singleAgent/toolResult

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/singleAgent/toolResult；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/singleAgent/toolResult.ts`；SHA256 `cccc1225d64173491c7fca25080032ec9a90b1487f070de050f30f73166ec12f`。
- 原始调用点：apps/web/src/components/singleAgentDialog.vue。
- 实际直写调用：`apps/web/src/components/singleAgentDialog.vue:170`。
- 注意：须同时满足 result/error 的 XOR 与 partial=>result 条件；不能只校验字段类型。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `sessionId` | 是 | `z.uuid()` |
| `callId` | 是 | `z.uuid()` |
| `partial` | 否 | `z.boolean().optional()` |
| `error` | 否 | `z.string().max(20000).optional()` |
| `result` | 否 | `z.looseObject({
     content: z.array(z.union([
       z.looseObject({ type: z.literal("text"), text: z.string() }),
       z.looseObject({ type: z.literal("image"), data: z.string(), mimeType: z.string().startsWith("image/") }),
     ])),
     details: z.json().default({}),
   }).optional()` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R098-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R098]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## skills

### R099 POST /api/skills/create

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/create.ts`；SHA256 `ee4aa41c0dbc7f39458a60c928e47fa78922d055455fac17f4b5b8573baaa9b5`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue:271`。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().min(1).max(1024), path: z.string().min(1).max(1024) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |
| `path` | 是 | `z.string().min(1).max(1024)` |

#### 返回数据与验证

基础样例 `M0-R099-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R099]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R100 GET /api/skills/get

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/get.ts`；SHA256 `e5be1d737f5861133d8cede5ce01038b733de0cfe7acbc1f36899155cce72883`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R100-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R100]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R101 POST /api/skills/install

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/install.ts`；SHA256 `a7d21243db661ea8cb1a4ee3ccf5eb511f43556aad3e0c754deec2f7435e05de`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: z.string().max(128).optional(),

  base64: z.string().min(1).max(Math.ceil(maxBytes / 3) * 4).base64().optional(),

  url: z.string().url().max(4096).optional(),

  force: z.boolean().optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 否 | `z.string().max(128).optional()` |
| `base64` | 否 | `z.string().min(1).max(Math.ceil(maxBytes / 3) * 4).base64().optional()` |
| `url` | 否 | `z.string().url().max(4096).optional()` |
| `force` | 否 | `z.boolean().optional()` |

#### 返回数据与验证

基础样例 `M0-R101-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R101]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R102 GET /api/skills/list

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/list.ts`；SHA256 `d8ce89745ca5970c7300657a4ac5effb725cad5a9dff834adc7fba5b64a72782`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue:131`。

#### 请求参数

位置：`query`；校验入口 `{ name: z.string().min(1).max(1024) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |

#### 返回数据与验证

基础样例 `M0-R102-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R102]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R103 PUT /api/skills/move

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/move.ts`；SHA256 `f2f485c9900bf94bbaa468a40629031bcfbc136be88b9a415e77690e9721f6b6`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue:222`。

#### 请求参数

位置：`body`；校验入口 `{

  name: z.string().min(1).max(1024),

  path: z.string().min(1).max(1024),

  target: z.string().min(1).max(1024),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |
| `path` | 是 | `z.string().min(1).max(1024)` |
| `target` | 是 | `z.string().min(1).max(1024)` |

#### 返回数据与验证

基础样例 `M0-R103-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R103]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R104 PUT /api/skills/order

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/order.ts`；SHA256 `5ce16a020beca1e43c3b4fd406f0287795f8cc67b86ddc7cfd3d152aea096eac`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue:243`。

#### 请求参数

位置：`body`；校验入口 `{

  name: z.string().min(1).max(1024),

  order: z.array(z.string().min(1).max(1024)).max(2000),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |
| `order` | 是 | `z.array(z.string().min(1).max(1024)).max(2000)` |

#### 返回数据与验证

基础样例 `M0-R104-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R104]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R105 GET /api/skills/read

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/read.ts`；SHA256 `76c1546150f153089cdb90473a5353132cc58ae1298e7e00e19ec7e4f6f5c202`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue:173`。

#### 请求参数

位置：`query`；校验入口 `{

  name: z.string().min(1).max(1024),

  path: z.string().min(1).max(1024).optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |
| `path` | 否 | `z.string().min(1).max(1024).optional()` |

#### 返回数据与验证

基础样例 `M0-R105-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R105]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R106 PUT /api/skills/save

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/save.ts`；SHA256 `01ceddc3056c2ea36210b5d3ac934c20d2a33caf408e79418c66c0c72faf8298`。
- 原始调用点：apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。
- 实际直写调用：`apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue:301`。

#### 请求参数

位置：`body`；校验入口 `{

  name: z.string().min(1).max(1024),

  path: z.string().min(1).max(1024).optional(),

  content: z.string(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |
| `path` | 否 | `z.string().min(1).max(1024).optional()` |
| `content` | 是 | `z.string()` |

#### 返回数据与验证

基础样例 `M0-R106-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R106]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R107 PUT /api/skills/setEnabled

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/setEnabled.ts`；SHA256 `f3b85cda2ea0cafec6166993d850ed5e858bf1546bd89124802b74c0ae2d1d29`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().min(1).max(1024), enabled: z.boolean() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |
| `enabled` | 是 | `z.boolean()` |

#### 返回数据与验证

基础样例 `M0-R107-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R107]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R108 DELETE /api/skills/uninstall

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：专用管理 API；不进入成员创作代理；插件受控远程安装按市场契约，其他 URL/源码按对应能力单独校验。
- 五名额：无；调试生成不得绕过名额。
- 实现：`apps/server/src/routes/skills/uninstall.ts`；SHA256 `2e5170aaee9a8f4d8d4140d9f7b315a4278ed07f60cbca4055cb7a6a6197eb3a`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: z.string().min(1).max(1024) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `z.string().min(1).max(1024)` |

#### 返回数据与验证

基础样例 `M0-R108-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R108]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## tools

### R109 GET /api/tools/client

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/tools/client.ts`；SHA256 `ecbb583b0bec93923e69b8fca58755d0d60da8f71c65cc20c66eb7c32c9be35d`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/server/src/routes/tools/renderers.ts 返回 renderer.url；packages/toolScaffold/src/client.ts 校验并动态加载 /api/tools/client?name=...&version=...。

#### 请求参数

位置：`query`；校验入口 `{

  name: u.plugins.toolNameSchema,

  version: z.string().regex(/^[a-f0-9]{64}$/),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.plugins.toolNameSchema` |
| `version` | 是 | `z.string().regex(/^[a-f0-9]{64}$/)` |

#### 返回数据与验证

基础样例 `M0-R109-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R109]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R110 GET /api/tools/get

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/tools/get.ts`；SHA256 `3e633b011a711ec429f8189d56005223ecd96d8479d6e38023e78c50ac003f5e`。
- 原始调用点：apps/web/src/components/agent/menu.vue<br>apps/web/src/components/settings/panels/mediaModel/addCustomProviderDialog.vue。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。
- 实际直写调用：`apps/web/src/components/agent/menu.vue:134`。
- 实际直写调用：`apps/web/src/components/settings/panels/mediaModel/addCustomProviderDialog.vue:178`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R110-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R110]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R111 POST /api/tools/install

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/tools/install.ts`；SHA256 `ce4a184dae06eaaef09f0a1aa9ea1b56f5399ccbc80c2f300b1bf9fe55a81019`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{

  fileName: z.string().max(104).regex(/^[a-z][a-zA-Z0-9]*\.tool\.js$/).optional(),

  source: z.string().min(1).max(maxBytes).optional(),

  url: z.string().url().max(4096).optional(),

  force: z.boolean().optional(),

}`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `fileName` | 否 | `z.string().max(104).regex(/^[a-z][a-zA-Z0-9]*\.tool\.js$/).optional()` |
| `source` | 否 | `z.string().min(1).max(maxBytes).optional()` |
| `url` | 否 | `z.string().url().max(4096).optional()` |
| `force` | 否 | `z.boolean().optional()` |

#### 返回数据与验证

基础样例 `M0-R111-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R111]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R112 GET /api/tools/renderers

#### 基本信息

- 1.0 处置：成员安全视图。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：仅获批能力；过滤 Key、源码配置、绝对路径及其他账号连接；能力脚本须匹配发布清单。
- 五名额：无。
- 实现：`apps/server/src/routes/tools/renderers.ts`；SHA256 `db830ee4bdd90bd0cbe9a31a8f076cbf362fabe7327426524a20babe0ad68945`。
- 原始调用点：packages/toolScaffold/src/client.ts（工具渲染器）。
- 实际直写调用：`packages/toolScaffold/src/client.ts:18`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

基础样例 `M0-R112-INPUT`：`no-argument-read`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R112]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R113 PUT /api/tools/save

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/tools/save.ts`；SHA256 `beefa9723d4f7ded94eb8200c4187577159b4b10a8086ba9c0e005a1fa41b4f9`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: u.plugins.toolNameSchema, config: z.record(z.string(), z.json()) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.plugins.toolNameSchema` |
| `config` | 是 | `z.record(z.string(), z.json())` |

#### 返回数据与验证

基础样例 `M0-R113-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R113]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R114 PUT /api/tools/setEnabled

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/tools/setEnabled.ts`；SHA256 `53cc5d8c3b788a9a461cd10e4d2056a14acadb33fa4bab5faeef7d41b1a9b8ac`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: u.plugins.toolNameSchema, enabled: z.boolean() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.plugins.toolNameSchema` |
| `enabled` | 是 | `z.boolean()` |

#### 返回数据与验证

基础样例 `M0-R114-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R114]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R115 DELETE /api/tools/uninstall

#### 基本信息

- 1.0 处置：管理员适配。
- 目标权限：平台管理员。
- 目标入口：管理员插件配置/发布；支持市场受控下载与本地文件安装，校验来源/版本/摘要；拒绝未经授权的任意 URL。
- 五名额：无。
- 实现：`apps/server/src/routes/tools/uninstall.ts`；SHA256 `a34ad51cd5a3921f69502258719fe3f54cbb4da8a7b58eabaf53ea40622f82bb`。
- 原始调用点：未发现直写路径；需查封装/内置插件。
- 动态调用补记：apps/web/src/components/settings/panels/pluginMarket/index.vue：按插件类型构造端点，loadInstalled/updatePlugin；创作端还加载获批资源。

#### 请求参数

位置：`body`；校验入口 `{ name: u.plugins.toolNameSchema }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `name` | 是 | `u.plugins.toolNameSchema` |

#### 返回数据与验证

基础样例 `M0-R115-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R115]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

## workspaces

### R116 GET /api/workspaces/check

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/check；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/check.ts`；SHA256 `7bb662b79b601f9db7a6d0dd69580b7a2bbabd4544ed1cb4ec0354fcf7cbce6d`。
- 原始调用点：apps/web/src/stores/workspace.ts。
- 实际直写调用：`apps/web/src/stores/workspace.ts:18`。

#### 请求参数

位置：`query`；校验入口 `{ directory: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R116-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R116]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R117 POST /api/workspaces/files/copy

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/copy；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/copy.ts`；SHA256 `5f780b53c60135be0c4d06ff5732418c6845e4c6f06a4731e9021b3d9ab3d803`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。

#### 请求参数

位置：`body`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().min(1).max(4096), target: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 是 | `z.string().min(1).max(4096)` |
| `target` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R117-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R117]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R118 POST /api/workspaces/files/importImage

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/importImage；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/importImage.ts`；SHA256 `cc689c3291cc290dd064e4d82c5b84b4690fd5f2d01f1ed675c5a426dbfb0b8a`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。
- 注意：URL 导入须防 SSRF、DNS 重绑定和跨来源重定向，并限制大小/类型。

#### 请求参数

位置：`body`；校验入口 `inputSchema.shape`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `url` | 是 | `z.string().min(1).max(16384)` |

局部 schema 的嵌套对象、枚举和跨字段约束保存在 JSON 的 `schemaDeclarations` 中；媒体生成还有共享 schema 二次校验，不可只使用上表第一层字段。

#### 返回数据与验证

基础样例 `M0-R118-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R118]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R119 GET /api/workspaces/files/list

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/list；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/list.ts`；SHA256 `9efd0fca8415c8b18bc40d259772e10a9a0a25403c510d68ed383cd611ec926c`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。

#### 请求参数

位置：`query`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().max(4096).optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 否 | `z.string().max(4096).optional()` |

#### 返回数据与验证

基础样例 `M0-R119-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R119]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R120 POST /api/workspaces/files/mkdir

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/mkdir；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/mkdir.ts`；SHA256 `5a7850243806cdcfb31194d8d4f1c8f445b3af75ab84f60d0b016160b841edc4`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。

#### 请求参数

位置：`body`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 是 | `z.string().max(4096)` |

#### 返回数据与验证

基础样例 `M0-R120-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R120]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R121 GET /api/workspaces/files/read

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/read；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/read.ts`；SHA256 `c6eb4bb374c495ed233f0821cbb4ffe0340447f84018436721de868b22abc630`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。

#### 请求参数

位置：`query`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().max(4096), snapshot: z.enum(["true"]).optional(), maxBytes: z.coerce.number().int().positive().max(20 * 1024 * 1024).optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 是 | `z.string().max(4096)` |
| `snapshot` | 否 | `z.enum(["true"]).optional()` |
| `maxBytes` | 否 | `z.coerce.number().int().positive().max(20 * 1024 * 1024).optional()` |

#### 返回数据与验证

基础样例 `M0-R121-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R121]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R122 DELETE /api/workspaces/files/remove

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/remove；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/remove.ts`；SHA256 `565174d1e632a30836f1bff6ddcd432665ab5db84b02933fa6cf108aca27dd43`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。

#### 请求参数

位置：`body`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().max(4096), recursive: z.boolean().optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 是 | `z.string().max(4096)` |
| `recursive` | 否 | `z.boolean().optional()` |

#### 返回数据与验证

基础样例 `M0-R122-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R122]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R123 POST /api/workspaces/files/rename

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/rename；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/rename.ts`；SHA256 `e1c900578f85ee8164d429722190b0dea551f109575a128745ca3e894eb6c725`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。

#### 请求参数

位置：`body`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().max(4096), target: z.string().min(1).max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 是 | `z.string().max(4096)` |
| `target` | 是 | `z.string().min(1).max(4096)` |

#### 返回数据与验证

基础样例 `M0-R123-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R123]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R124 POST /api/workspaces/files/reveal

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/workspaces/files/reveal.ts`；SHA256 `83fc49383ec36b68389be16831ab61fc7410fb5c0d919e24f697a3dd15e47fe7`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。

#### 请求参数

位置：`body`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().max(4096) }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 是 | `z.string().max(4096)` |

#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R125 PUT /api/workspaces/files/write

#### 基本信息

- 1.0 处置：项目创作适配。
- 目标权限：项目成员；管理员须有相应项目授权。
- 目标入口：/api/v1/projects/{projectId}/creative/workspaces/files/write；服务端注入目录，绑定会话/调用/连接所有者。
- 五名额：无；回调/取消不得重复占位。
- 实现：`apps/server/src/routes/workspaces/files/write.ts`；SHA256 `7f5cbaa4702b2adb1b71a6e0cf5a18f1dcc6953509c5b5eb902faf8f3a9f3871`。
- 原始调用点：apps/web/src/lib/workspaceFiles.ts（baseURL 封装）。
- 动态调用补记：apps/web/src/lib/workspaceFiles.ts：baseURL=/api/workspaces/files + 相对方法路径。
- 注意：Body 为 application/octet-stream；目录、path、revision 等在 query；revision 是原文件字节的 SHA256。

#### 请求参数

位置：`query`；校验入口 `{ directory: z.string().min(1).max(4096), path: z.string().max(4096), exclusive: z.enum(["true", "false"]).optional(), expectedRevision: z.string().regex(/^[a-f0-9]{64}$/).optional(), encoding: z.enum(["utf-8", "utf-8-bom", "utf-16le", "utf-16be"]).optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `directory` | 是 | `z.string().min(1).max(4096)` |
| `path` | 是 | `z.string().max(4096)` |
| `exclusive` | 否 | `z.enum(["true", "false"]).optional()` |
| `expectedRevision` | 否 | `z.string().regex(/^[a-f0-9]{64}$/).optional()` |
| `encoding` | 否 | `z.enum(["utf-8", "utf-8-bom", "utf-16le", "utf-16be"]).optional()` |

#### 返回数据与验证

基础样例 `M0-R125-INPUT`：`missing-required-fields`，HTTP `400`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R125]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R126 GET /api/workspaces/list

#### 基本信息

- 1.0 处置：替换项目入口。
- 目标权限：已登录账号。
- 目标入口：使用 /api/v1/projects；禁止枚举服务器目录。
- 五名额：无。
- 实现：`apps/server/src/routes/workspaces/list.ts`；SHA256 `1966cc57315e6e7418163a767ad09e46e2da29bf442432c3ebff0eb439a81712`。
- 原始调用点：apps/web/src/pages/home/workspacePicker.vue。
- 实际直写调用：`apps/web/src/pages/home/workspacePicker.vue:94`。

#### 请求参数

位置：`query`；校验入口 `{ path: z.string().max(4096).optional() }`。

| 字段 | 必填 | 类型与限制（上游 Zod 原表达式） |
| --- | --- | --- |
| `path` | 否 | `z.string().max(4096).optional()` |

#### 返回数据与验证

基础样例 `M0-R126-INPUT`：`missing-required-fields`，HTTP `200`，Content-Type `application/json; charset=utf-8`。
原始响应在 [探测记录](../../validation/evidence/upstream-route-probes.json) 的 `routes[id=R126]`，密钥和令牌已脱敏。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。

### R127 POST /api/workspaces/selectDirectory

#### 基本信息

- 1.0 处置：排除。
- 目标权限：无。
- 目标入口：无入口；Spring 默认拒绝。
- 五名额：无。
- 实现：`apps/server/src/routes/workspaces/selectDirectory.ts`；SHA256 `082abb6f743339e74715f030bfb8944b201255f1496e0ad6af484e2e34b6816c`。
- 原始调用点：apps/web/src/pages/home/workspacePicker.vue。
- 实际直写调用：`apps/web/src/pages/home/workspacePicker.vue:73`。

#### 请求参数

路由未声明 validateFields 字段；请求仍受正文所列的宿主/来源校验和业务处理约束。
#### 返回数据与验证

按已确认范围排除；本轮未调用桌面/移动或本机系统操作。
返回构造原表达式见 JSON 的 `responseExpressions`；实际业务成功样例按功能复用 M0 报告。迁移状态：未迁移。目标身份/项目/并发/泄漏测试在 M1–M3 执行。
