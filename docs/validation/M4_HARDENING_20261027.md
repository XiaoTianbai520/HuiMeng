# 试用期加固：运行类型、失败原因、文本能力与跨入口验收

2026-10-27。承接 [M4 签收报告](M4_SIGNOFF_20261027.md)，处理签收时留下的两项代码问题、两项环境缺口，并补齐 MCP/A2A 真实部署验收与排队测试。所有代码改动已本地测试 + 真实部署验证，测试数据已清理。

## 1. 结论摘要

| 项 | 结果 |
| --- | --- |
| `kind` 硬编码 | 已修，视频运行写 `video`；历史数据按产物文件名回填 |
| 运行失败原因 | 已加 `reason`（受控词表），前端按三类展示；服务端不下发异常文本 |
| Agent/A2A 文本能力 | 已恢复，真实 A2A 任务 7.6 秒完成并返回正文 |
| H3 视频 | `h3T2v` 已常开，真实出片 343 秒成功；其余四个模式保持停用 |
| MCP 真实验收 | initialize / tools/list / tools/call / 越权拒绝 / 无效凭证 全部通过 |
| 排队测试 | 6 路同时提交：5 个进入执行并全部成功，第 6 个 20 ms 返回 429 |
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

### 5.1 执行器变慢（环境侧，未修）

同一台 ComfyUI（192.168.1.100:8188，RTX 3060 12 GB）单张图片：
- 会话开始时 66 秒（M4 记录 36–48 秒）；
- 跑完一次 H3 视频后，5 张排队图片变为 173 → 831 秒，严格递增；
- 期间 `/queue` 正常返回，`/system_stats` 偶发无响应。

判断为显存压力下的模型反复换入换出（H3 与 Qwen Image 争用 12 GB），不是 API 缺陷：名额、状态机、终态收敛都正确。**影响**：图片与视频混排时单张耗时可达十几分钟，30 分钟超时阈值接近触顶。

### 5.2 执行器 HTTP 调用缺少请求级超时（代码侧，未修）

`ComfyImageClient` / `ComfyVideoClient` 只设了 8 秒连接超时，没有设置读超时。若执行器接受连接后不再响应，`submit`/`history` 会无限阻塞：
- 该线程不会进入轮询循环，`DEADLINE_SECONDS` 的 30 分钟兜底**不会触发**；
- 名额被永久占用，只能重启进程回收。

本轮未实际发生（线程栈显示都停在正常的 `Thread.sleep` 轮询），但属于同类「名额泄漏」风险。建议下一轮给这两个客户端的 `HttpRequest` 加 60 秒请求超时，并把 `submit` 也纳入整体截止时间。

### 5.3 契约生成器与提交版不一致（既有，未修）

- `docs/scripts/build_openapi.py` 落后于提交版 `openapi.yaml`：重新生成会删掉 M3 的 471 行路由与手工描述。本轮因此改为精确手改 `Run` schema，没有整份重跑生成器。
- `docs/api/route-map.json` 有 128 条，源路由 `route-details.json` 只有 127 条（多出 `R128`），`check_contracts.py` 的 `assert len(mapped) == 127` 在 HEAD 上即失败。
- `sync_frontend_contracts.py --check` 也因 `route-map.json` 陈旧而失败；`platform.ts` 的 `MarketItem` 与 `openapi.yaml` 字段不一致（前者有 `manifest`，后者有 `reason`），前端未使用 `manifest`，暂无功能影响。

这三项都不是本轮引入，需要单独一轮把生成器、路由清单和前端契约对齐。

## 6. 清理与回滚点

| 项 | 结果 |
| --- | --- |
| 素材 | 7 条逐个经 `DELETE /media/{id}` 删除，返回 204；该路径先删桶对象再删行，因此对象同步清除 |
| 外部客户端 | 撤销后删除该项目的授权与客户端记录，凭证总数 5→4 |
| 项目 | 测试项目 `m5-verify` 及相关运行、成员、审计删除；项目数回到 8 |
| 工作区 | `/data/huimeng/workspaces/e38508ba-…` 删除 |
| 临时凭据 | 服务器上的 `/root/.llm_key`、`/root/m5-state.json`、验收脚本已删除；临时 SSH 部署公钥在收尾时移除 |

**回滚点**：`huimeng-api:prev-m4h`（`cea8a73f6ef0`，V11）。注意 V12 已应用，旧镜像不能直接对新库启动，回滚必须按 [回滚演练方案](ROLLBACK_DRILL_PLAN.md) 恢复 `pre-v12-20261027-174016` 备份。

## 7. 签收判定

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 阶段一代码修复 | **可签收** | 单元/集成测试覆盖，真实部署验证 `kind` 与 `reason` 正确 |
| 阶段二文本能力 | **可签收** | 真实 A2A 文本任务完成 |
| 阶段二视频能力 | **可签收（有条件）** | 仅 `h3T2v` 常开；其余模式未开，需产品确认后再逐个验证 |
| 阶段三 MCP 验收 | **可签收** | 协议、授权、越权、无效凭证均实测 |
| 阶段三排队测试 | **可签收（有条件）** | 名额与 429 正确；执行器混排变慢属环境问题，需在执行器侧排查 |

**遗留**（不阻塞本轮签收）：

1. 执行器 HTTP 无请求级超时（5.2），建议下一轮修；
2. 执行器图片耗时从 66 秒涨到 831 秒（5.1），需在工作站侧查显存与模型卸载策略；
3. 契约生成器/路由清单漂移（5.3）；
4. `generateInternal`（MCP/A2A 工具调用）仍返回固定文案，不携带 `reason`，客户端只能从运行接口拿原因；
5. 跨 Flyway 版本回滚演练仍是方案，未执行。
