# DeepSeek 文本与 Agent 原版基线

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> 2026-10-22 已补 Web 文本生成、Agent getCanvas/addNode、回调及保存，见[补测](M0_CLOSEOUT.md)和[原始事件](../../validation/evidence/upstream-ui-text-agent-closeout.json)。下文是早期取证记录。

> 2026-10-19，固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`。这是 Toonflow 原版验证，不表示绘梦 1.0 已实现。

用户已在原版 Toonflow 配置 DeepSeek API Key。验证时只将 `deepSeek` 供应商配置复制到被 Git 忽略的 `.m0-work/data/settings.json`，没有复制其他供应商密钥，也没有将 Key 写入文档。原版服务占用 3000 端口；隔离副本使用 3001 端口和 M0 样例项目，避免修改原版项目。

去敏后的接口结果见[首次结构化记录](../../validation/evidence/upstream-deepseek-api.json)与[补测结构化记录](../../validation/evidence/upstream-deepseek-extended.json)。

| 项目 | 实测结果 |
| --- | --- |
| 模型发现 | 隔离副本 `GET /api/ai/models` 返回 HTTP 200，包含 `deepSeek/deepseek-flash` 和 `deepSeek/deepseek-v4-pro`。 |
| 文本流 | 原版 `POST /api/ai/generate` 使用 `deepSeek/deepseek-flash` 返回 HTTP 200、`text/event-stream`；收到 `reasoning`、`text`、`done`，正文 5 字符，`stopReason=stop`，无 `error`。该次请求落在用户已启动的 3000 端口服务，未写项目数据。 |
| Agent 会话 | 隔离副本 `POST /api/agent` 使用同一模型与 M0 样例项目返回 HTTP 200、`application/x-ndjson`；共 13 个事件：`session`、`userMessage`、9 个 `text`、`stats`、`done`，无 `error`。测试提示明确要求不调用工具。 |

## 停止、错误、工具和恢复补测

| 场景 | 实测结果 |
| --- | --- |
| 文本错误 | 隔离副本仅增加一个无效 Key 的测试供应商；`POST /api/ai/generate` 返回 HTTP 200 与 SSE `error`，错误属于鉴权失败，没有 `done`。这说明不能以 HTTP 200 判断模型调用成功。 |
| 文本停止 | 发起较长文本请求，在收到第一个非空 `text` 增量后关闭客户端连接；关闭前未收到 `done` 或 `error`。原版代码在连接关闭时调用 `AbortController.abort()`，但本次没有直接量测 DeepSeek 上游是否停止计算或计费，不能将其算作上游取消保证。 |
| Agent 工具 | 只让 Agent 用 `read` 读取新建的无业务内容[合成夹具](../../validation/fixtures/sample-project/M0-safe-tool-probe.txt)；NDJSON 中工具状态最终为 `success`，随后收到 `done`，无 `error`。会话文件确认 `read` 的参数只指向该夹具。 |
| Agent 取消与恢复 | 首次收到 `thinking` 内容后关闭 NDJSON 连接；同一会话随后显示 `running=false`，首条助手消息持久化为 `stopReason=aborted`。用相同 `sessionFile` 追加新提示后，响应以 `done` 结束，第二条助手消息为 `stopReason=stop`，会话仍为 `running=false`。 |

最初尝试让 Agent 读取样例剧本的测试被自动审批拒绝，原因是该内容可能发往外部 DeepSeek。随后改用上述公开合成夹具，未发送原剧本内容。

本次已确认文本模型接通，文本客户端停止及错误事件、Agent 基本会话、只读工具调用、取消和继续可观察。画布工具、A2A 团队事件、文本上游取消的实际效果仍需分别取证。供应商 Key 由原版设置保存的行为，不能代替绘梦 1.0 的公司统一密钥管理和成员权限验收。
