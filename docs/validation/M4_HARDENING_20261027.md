# 试用期加固：运行类型、失败原因、文本能力、执行器串行与契约工具链

2026-10-27。承接 [M4 签收报告](M4_SIGNOFF_20261027.md)，处理签收时留下的两项代码问题、两项环境缺口，并补齐 MCP/A2A 真实部署验收与排队测试。当日晚间又补做执行器串行派发、时限口径与契约工具链修复。所有代码改动已本地测试 + 真实部署验证，测试数据已清理。

## 1. 结论摘要

| 项 | 结果 |
| --- | --- |
| `kind` 硬编码 | 已修，视频运行写 `video`；历史数据按产物文件名回填 |
| 运行失败原因 | 已加 `reason`（受控词表），前端按三类展示；服务端不下发异常文本 |
| Agent/A2A 文本能力 | 已恢复，真实 A2A 任务 7.6 秒完成并返回正文 |
| H3 视频 | `h3T2v` 已常开，真实出片 343 秒成功；其余四个模式保持停用 |
| MCP 真实验收 | initialize / tools/list / tools/call / 越权拒绝 / 无效凭证 全部通过 |
| 排队测试 | 6 路同时提交：5 个进入执行并全部成功，第 6 个 20 ms 返回 429 |
| 执行器串行 | 已实现 FIFO 闸门；2 个并发请求均受理，ComfyUI 执行窗口零重叠，第二个排队 45.1 秒 |
| 生成时限 | 未加 60 秒总超时；视频总时限默认 90 分钟且可配置，排队与流式连接另有独立上限 |
| 契约工具链 | 生成器改为非破坏性；128 条映射含 1 条平台新增；OpenAPI 66 操作/59 路径，61 项有实现 |
| 数据清理 | 项目 8→8、素材 7→0、运行 7→0、外部凭证 5→4、工作区目录删除 |

## 2. 第一阶段：代码修复

### 2.1 运行类型（`kind`）

`GenerationRunMapper.insert` 原来把 `kind` 写成字面量 `'image'`，视频运行也被记成图片，按类型统计失真。

- `insert` 增加 `kind` 参数，`MediaGenerationServiceImpl.admit` 按 `mediaType` 传 `image`/`video`；
- `GenerationRunDO` 增加 `kind` 与 `failureReason`，`RunRespDTO.kind` 改为直接取库内值（原先是硬编码常量）；
- 新增 `V12__generation_run_kind_and_failure_reason.sql`：加 `failure_reason` 列，并按 `project_media.filename = CONCAT('generated-', run.id, '.mp4')` 回填历史视频运行。

**历史数据边界**：只有「成功出片」的旧视频运行能被证明并回填；失败视频没有产物，无法还原，保持 `image`。`kind` 不参与状态机与名额判定，只影响统计口径。

### 2.2 失败原因（`reason`）

新增 `failure_reason VARCHAR(48)`，只存固定词表代码，不存异常文本：

| 前缀 | 取值 | 含义 |
| --- | --- | --- |
| `USER_` | `USER_INVALID_REQUEST`、`USER_NOT_PERMITTED`、`USER_CAPACITY_EXCEEDED` | 参数/参考图、权限或模型未启用、名额已满 |
| `MODEL_` | `MODEL_UNAVAILABLE`、`MODEL_TIMEOUT` | 执行器不可达或上游报错、轮询超过 30 分钟 |
| `EXECUTION_` | `EXECUTION_STORAGE_UNAVAILABLE`、`EXECUTION_BUSY`、`EXECUTION_LOST`、`EXECUTION_INTERRUPTED`、`EXECUTION_FAILED`、`EXECUTION_INTERNAL` | 产物入库失败、线程池拒绝、待核查、重启中断、其他执行异常 |

- 执行器探测拆成「模型探测」与「存储探测」两步，分别给 `MODEL_UNAVAILABLE` 与 `EXECUTION_STORAGE_UNAVAILABLE`，不再把存储问题误报成模型问题；
- 超时改为显式 `MODEL_TIMEOUT`（504），不再与「执行器不可用」混用；
- `finish(..., reason)` 用 `COALESCE(?, failure_reason)` 写入，收敛过程不会把首次判定的原因覆盖成空；
- 重启时的 `markLostAfterRestart` 直接写 `EXECUTION_INTERRUPTED`。

前端 `files/index.vue` 按前缀分三类展示，并给出下一步动作（等名额 / 开通模型 / 联系管理员查存储），提交阶段的 429/403/503 也补了上下文说明。

### 2.3 测试

| 项 | 结果 |
| --- | --- |
| 后端全量 | **166 项通过、0 失败**（M4 基线 163，新增 3） |
| 新增用例 | `imageAndVideoRunsRecordTheirOwnKindSoStatisticsStayCorrect`（按 kind 查库统计各 1）、`failedRunCarriesControlledReasonAndNoInternals`（reason=MODEL_UNAVAILABLE 且响应不含 Exception/http/comfy）、`legacyVideoRunsAreRepairedOnlyWhenAnOutputFileProvesIt`（只回填有产物证明的） |
| 前端 | 26 项通过、`vue-tsc` 通过、生产构建 exit=0 |
| OpenAPI | 规范校验通过；`Run.reason` 进 required，11 个枚举值 |

## 3. 第二阶段：模型配置与真实部署

### 3.1 部署

| 步骤 | 结果 |
| --- | --- |
| 迁移前备份 | `/data/backup/huimeng/pre-v12-20261027-174016/db.sql`，4,835,214 字节，SHA-256 `7720a34c…5cfa7bd`；恢复到演练库后 11 条迁移、20 张表、关键表行数逐表一致，演练库已删 |
| 镜像 | `huimeng-api:m4h` = `eb6331ccca0e`；旧镜像打 `prev-m4h` = `cea8a73f6ef0` 留回滚点 |
| JAR | SHA-256 `52f189a0c6caa1826a65f4ecbf3fac6dcbb1bda12118dcf6376bfa70e3cf6552`，本地与服务器一致 |
| V12 迁移 | `Successfully applied 1 migration … now at version v12`，耗时 0.096 s |
| 启动 | 首次 5.161 s，加环境变量重启后 3.145 s；启动日志 0 ERROR；`web=200 / csrf=200` |
| 迁移后核对 | `flyway=12`、`failure_reason` 为 `varchar(48) NULL`、用户 6 / 项目 8 / 审计 333 与迁移前一致 |

`finalize_database.py` 未重跑：V12 只加列、不新建表，运行账号的业务表 DML 授权不需要变化。

### 3.2 文本能力

原先服务器 `provider_secrets` 为空、`deepseek`/`qwen` 全部 `enabled=0`，A2A 一律 `TASK_STATE_REJECTED`。

- 在 `api.env` 追加 `HUIMENG_LLM_ENABLED=true`（原文件已备份为 `api.env.bak-20261027-174208`），重启后容器内可见；
- 通过 `PUT /api/v1/admin/providers/deepseek/secret` 写入密钥（服务端加密存储，返回 204），密钥经临时文件传入并在用后删除，未落仓库、未进日志；
- 通过 `PUT /api/v1/admin/models/approval` 启用 `deepseek/deepseek-chat`；
- 状态核对：`provider_configured=[('deepseek', True)]`，启用模型 = `deepseek-chat`、`h3T2v`、`qwenImage21Text`、`qwenImage21Edit`。

**真实 A2A 文本任务**：新建客户端（A2A 团队 `m5team`）→ `SendMessage` → **7.6 秒** 返回 `TASK_STATE_COMPLETED`，正文「1 加 1 等于 2。」。这是该部署上第一次真实完成的 A2A 文本任务。

### 3.3 视频模型

按「只开已验证模式」：`h3Local3060/h3T2v` 常开（5 s / 352p / 16:9 已在 M4 验证），`h3I2v`、`h3Fl2v`、`h3L2v`、`h3R2v` 保持停用。单卡仍为串行执行，不追求更高并发。

## 4. 第三阶段：跨入口验收与排队

### 4.1 MCP（真实部署）

| 检查 | 结果 |
| --- | --- |
| `initialize` | 200，返回 `Mcp-Session-Id`，协议版本 2025-06-18 |
| `tools/list` | 只列出被授权的 `listMedia`、`listWorkspaceFiles` |
| `ping` | 200 |
| `tools/call listWorkspaceFiles` | 200，0.05 秒，返回项目目录条目 |
| 未授权工具 `readWorkspaceFile` | 403，`-32099 该客户端无权调用此能力` |
| 无效凭证 | 401，`WWW-Authenticate: Bearer` |

### 4.2 排队与执行

6 个请求同时提交到同一项目：

| 指标 | 结果 |
| --- | --- |
| 进入执行 | 5 个，均返回 200 |
| 第 6 个 | 429，20 ms 返回，未触达执行器 |
| 最终状态 | 5 个全部 `SUCCEEDED`，无 FAILED/LOST |
| 素材 | 队列后 +5，总计 7（1 单图 + 1 视频 + 5 排队） |
| 串行耗时 | 173 / 241 / 654 / 731 / 831 秒，严格递增 |

**观察**：客户端在排队阶段断开（本地会话超时），服务端仍把 5 个任务全部跑完并落库，复验了「断线不影响服务端执行」。

## 5. 本轮发现的问题

### 5.1 执行器变慢（已修：串行派发）

同一台 ComfyUI（192.168.1.100:8188，RTX 3060 12 GB）单张图片：
- 会话开始时 66 秒（M4 记录 36–48 秒）；
- 跑完一次 H3 视频后，5 张同时提交的图片变为 173 → 831 秒，严格递增；
- 期间 `/queue` 正常返回，`/system_stats` 偶发无响应。

判断为多个任务同时提交导致显存压力下的模型反复换入换出（H3 与 Qwen Image 争用 12 GB）。平台侧已增加 FIFO 串行闸门：请求仍各自受理并保持流式等待，但同一时刻只向执行器提交一个任务；排队期间每秒心跳，用户看到的是持续转圈而不是 429。设计与时限见[媒体执行队列与时限](../architecture/MEDIA_EXECUTION_QUEUE.md)。执行器侧的模型卸载策略仍需单独优化，本次不修改 ComfyUI。

### 5.2 执行器 HTTP 读超时（已修，口径更正）

原文写「只设了 8 秒连接超时，没有设置读超时」不准确：两个客户端原本就对每次 HTTP 调用设置了超时（图片 JSON 20 秒、上传/下载 60 秒；视频提交/查询 120 秒、下载 180 秒），连接超时分别为 5/8 秒。

真正的问题是 `ComfyImageClient` 使用 `BodyHandlers.ofInputStream()`：`HttpRequest.timeout` 只保证响应头到达，之后的正文读取不受它约束。若执行器发完响应头就停止吐字节，线程会永久卡在 `readNBytes`，既不进入轮询，也不会触发总时限。

修复：改用 `BodyHandlers.ofByteArray()`，让单次超时覆盖整个响应体，并保留 JSON 1 MiB / 图片 16 MiB 的大小上限。**没有**添加 60 秒总超时；总生成时限按媒体类型可配置，视频默认 90 分钟（`huimeng.comfy.video-deadline-seconds`），排队与流式连接另有独立上限。详见[媒体执行队列与时限](../architecture/MEDIA_EXECUTION_QUEUE.md)。

### 5.3 契约工具链漂移（已修）

- `build_openapi.py` 是 M1/M2 基线生成器，无法重现 M3 之后手工补充的平台路径（基线 42 条，提交版 59 条）。现在默认只写 `docs/api/generated/`（已忽略），必须显式 `--write` 才覆盖提交版；提交版 `openapi.yaml` 与 `route-map.json` 是唯一事实源。
- `route-map.json` 的 128 条 = 127 条固定上游路由 + 1 条平台新增 `R128`（`/api/v1/projects/{projectId}/creative/agent/cancel`，`platformAddition: true`）。校验改为「源路由全覆盖 + 新增必须显式标记」，不再用固定 127 条做等值断言。
- 顺带补齐契约缺口：4 个已实现但未写入 OpenAPI 的能力路径（`nodes/files`、`tools/client`、`mcp/status`、`mcp/skill`）；实现清单从 41 项补到 61 项；前端契约重新生成后 `MarketItem` 以 `reason` 为准（`manifest` 是旧字段，前端未使用），并移除未实现的 `ext/read` 元数据。
- 校验结果：`check_contracts.py` 通过（66 操作 / 59 路径 / 128 映射 / 1 条平台新增），`sync_frontend_contracts.py --check` 通过。

以上三项原本都不是本轮引入；本轮已把 5.1、5.2、5.3 全部处理完。

## 6. 清理与回滚点

| 项 | 结果 |
| --- | --- |
| 素材 | 7 条逐个经 `DELETE /media/{id}` 删除，返回 204；该路径先删桶对象再删行，因此对象同步清除 |
| 外部客户端 | 撤销后删除该项目的授权与客户端记录，凭证总数 5→4 |
| 项目 | 测试项目 `m5-verify` 及相关运行、成员、审计删除；项目数回到 8 |
| 工作区 | `/data/huimeng/workspaces/e38508ba-…` 删除 |
| 临时凭据 | 服务器上的 `/root/.llm_key`、`/root/m5-state.json`、验收脚本已删除；临时 SSH 部署公钥在收尾时移除 |

**回滚点**：`huimeng-api:prev-m4h`（`cea8a73f6ef0`，V11）。注意 V12 已应用，旧镜像不能直接对新库启动，回滚必须按 [回滚演练方案](ROLLBACK_DRILL_PLAN.md) 恢复 `pre-v12-20261027-174016` 备份。当日晚间部署 `m4q` 后，当前回滚点为 `prev-m4q`（`eb6331ccca0e`，V12），见第 8 节。

## 7. 签收判定

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 阶段一代码修复 | **可签收** | 单元/集成测试覆盖，真实部署验证 `kind` 与 `reason` 正确 |
| 阶段二文本能力 | **可签收** | 真实 A2A 文本任务完成 |
| 阶段二视频能力 | **可签收（有条件）** | 仅 `h3T2v` 常开；其余模式未开，需产品确认后再逐个验证 |
| 阶段三 MCP 验收 | **可签收** | 协议、授权、越权、无效凭证均实测 |
| 阶段三排队测试 | **可签收** | 名额与 429 正确；执行器已改为 FIFO 串行，见第 8 节 |

**遗留**（不阻塞本轮签收）：

1. ~~执行器 HTTP 读超时~~：已在第 8 节修复。原描述不准确——客户端一直有单次请求超时（图片 20/60 秒、视频 120/180 秒），缺的是 `ofInputStream` 之后响应体读取的超时约束。
2. 执行器图片耗时从 66 秒涨到 831 秒：平台侧已用串行闸门消除并发争用，真实复测单张 45–60 秒；执行器侧模型卸载策略仍需单独优化。
3. ~~契约生成器/路由清单漂移~~：已在第 8 节修复。
4. `generateInternal`（MCP/A2A 工具调用）仍返回固定文案，不携带 `reason`，客户端只能从运行接口拿原因。
5. 跨 Flyway 版本回滚演练仍是方案，未执行。

## 8. 执行器串行、时限与契约工具链（2026-10-27 晚）

### 8.1 部署

| 项 | 结果 |
| --- | --- |
| 镜像 | `huimeng-api:m4q` = `68ae7766bf70`；回滚点 `huimeng-api:prev-m4q` = `eb6331ccca0e`（原 `m4h`） |
| 数据库 | 无新迁移，Flyway 保持 V12；未改生产 schema |
| 启动 | 4.251 秒，0 ERROR，`web=200 / csrf=200` |
| 配置日志 | `imageDeadline=1800s videoDeadline=5400s queueTimeout=3600s streamTimeout=10800000ms` |

### 8.2 串行派发

两个图片请求同时提交，证据见 [`evidence/m6-serial-timeout-20261027.json`](evidence/m6-serial-timeout-20261027.json)：

| 指标 | 请求 A | 请求 B |
| --- | --- | --- |
| HTTP / code | 200 / 200 | 200 / 200 |
| 首字节（TTFB） | 0.17 秒 | 0.12 秒 |
| 总耗时 | 45.0 秒 | 105.8 秒（含排队 45.135 秒） |
| 3 秒时状态 | `RUNNING` | `RESERVED` |
| 最终状态 | `SUCCEEDED`，`kind=image`，`reason=null` | 同左 |
| ComfyUI 执行窗口（CST） | 19:31:31.597–19:32:15.857 | 19:32:16.744–19:33:16.273 |

两个执行窗口间隔 **887 毫秒**，零重叠。第二个请求在排队期间已经拿到响应头（TTFB 0.12 秒），并持续收到流式心跳，因此前端一直转圈而不是被阻塞或 429。API 日志明确记录 `waitedMs=45135` 后才“提交执行器”。

### 8.3 视频与时限

- 真实 H3 5 秒 352p：**195.4 秒**成功，`kind=video`、`reason=null`、实际规格 `5s 352p 16:9 608×352`，TTFB 0.07 秒。
- 总时限按媒体类型独立：图片 30 分钟、视频 **90 分钟**（可配置 `HUIMENG_COMFY_VIDEO_DEADLINE_SECONDS`），15 秒视频跑 30 分钟以上不会被 30 分钟阈值掐断。
- 未引入 60 秒总超时。单次 HTTP 调用超时只约束一次请求/响应交换；图片客户端改用 `BodyHandlers.ofByteArray()`，让超时覆盖响应体读取并保留 1 MiB / 16 MiB 大小上限。
- 轮询遇到单次读失败会重试 3 次（图片间隔 1 秒、视频 2 秒）才判定执行器不可用，避免网络抖动误杀已提交任务。
- 视频提交现在把本地运行 ID 作为 `prompt_id` 传给 ComfyUI：POST 已被接受但响应丢失时，核对任务仍能按运行 ID 查到。

### 8.4 契约工具链

- `build_openapi.py` 默认只写 `docs/api/generated/`（已忽略），必须 `--write` 才覆盖提交版；提交版 `openapi.yaml` / `route-map.json` 是唯一事实源。
- `route-map.json` 128 条 = 127 条固定上游路由 + 1 条平台新增 `R128`（`platformAddition: true`）；校验改为源路由全覆盖 + 新增显式标记。
- 补齐 4 个已实现但缺失的能力路径（`nodes/files`、`tools/client`、`mcp/status`、`mcp/skill`）；实现清单 41 → 61 项。
- 前端契约重新生成：`MarketItem` 以 `reason` 为准（`manifest` 是旧字段，前端未使用），移除未实现的 `ext/read` 元数据；`vue-tsc` 与生产构建通过。
- 校验结果：`check_contracts.py` 通过（66 操作 / 59 路径 / 128 映射 / 1 条平台新增），`sync_frontend_contracts.py --check` 通过。

### 8.5 清理与残留

清理后：项目 9→8、素材 3→0、运行归零、外部客户端保持 4；测试项目工作区已删；服务器验收脚本与临时 SSH 公钥已移除，密钥登录重新返回 `Permission denied`，密码登录正常。

仍存在的边界（已写入[媒体执行队列与时限](../architecture/MEDIA_EXECUTION_QUEUE.md)）：

1. API 重启时 ComfyUI 可能仍在跑重启前的任务，新任务会进入 ComfyUI 自己的队列；生成高峰期应避免滚动重启。
2. 串行闸门是进程内状态，1.0 单实例假设；水平扩容需改为数据库或分布式锁。
3. 客户端断开不释放名额，任务继续到终态并入库（既有约定）。
4. 排队超过 1 小时返回 `EXECUTION_BUSY`，这是保护而不是故障。
5. `generateInternal` 的失败原因透出与跨 Flyway 回滚演练仍未做。
