# M3-02 模型列表、设置与文本生成验收

日期：2026-10-26。接续 M3-W03。本批完成 M3-02：获批模型列表、团队供应商密钥与 Agent 文本生成（流式 NDJSON、停止、错误语义）。M3-04 的工具回调、画布控制与委派未实现；M3-01 完整 A04/A05/A06/A17 与 M3-03 其他模型/规格仍待验。未更新正式部署或正式库。

## 实现

- **文本供应商**：`LlmClient` 支持 OpenAI 兼容 `deepseek`、`qwen`，端点来自服务端配置；`POST {endpoint}/chat/completions`（`stream:true`），不跟随重定向，密钥仅从 `provider_secrets` 解密后在内存使用，不进入日志/响应/导出。`V5__text_generation.sql` 新增 qwen-plus/qwen-turbo/deepseek-reasoner（均默认禁用），不预置端点或批准。
- **Agent 会话与生成**：`POST /creative/agent` 输出 `session/userMessage/text/done/error` 的 NDJSON；会话保存在项目 `.agent/sessions/<file>`，复用工作区安全文件 API 与修订号。`create/get/list/rename/message` 提供会话 CRUD；同会话生成互斥（409 `AGENT_RUNNING`）。`POST /creative/agent/cancel` 与前端停止按钮联动，取消在下一增量前生效并关闭上游流，不只停止显示。
- **授权**：`/creative/agent*` 精确路径加入 Spring Security 允许列表；模型执行前重新校验项目成员与数据库批准状态。`R128` 注册取消兼容路由；OpenAPI 的 `agentGenerate` 与新增会话/取消子路径标记实现范围。

## 验证

| 范围 | 结果 |
| --- | --- |
| 后端默认回归 | Linux Docker `verify`、Spotless、JAR：**92 项全过**（新增 LlmClientTests 4 项、AgentServiceImplTests 4 项；模型目录与迁移期望同步更新） |
| 前端 | 26 项测试/102 断言、类型/边界、生产构建通过 |
| API 流式 | 登录→获批模型列表→NDJSON `session/userMessage/text/done` 正确；错误映射 503 安全消息 |
| 真实浏览器 | **8/8**：模型选择、流式回复、历史会话与重开、停止生成（含后端取消与锁释放）、供应商错误提示。见[结果](evidence/m3-m02-agent.json)与[回复](evidence/m3-m02-agent-reply.png)、[错误](evidence/m3-m02-agent-error.png)截图 |

A07 的模型列表/提示词输入/流式输出/停止/错误提示通过；A11 的密钥配置与成员仅选获批模型通过（模型目录 API 与选择器一致；导出仍只含安全元数据）。**模型服务为本地合成 OpenAI 兼容 SSE**（Linux 回环 18100），不是真实 DeepSeek/Qwen 计费调用；真实供应商兼容性未测。

## 边界与后续

- M3-04 的工具回调、画布控制、子 Agent、配额与画布格式 `.jsonl` 兼容未实现；当前新建会话为 `.json`，上游 `.jsonl` 的原格式回归在 M3-04 处理。
- M3-02 文本问答不占用项目 5 名额；Agent 内部生成与媒体名额在 M3-03/04 处理。
- M3-01 的 A04 需要可用文本节点显示，A05/A06/A17 另行补齐；M3-03 其他模型/规格/真实取消/跨入口容量仍待验。
- 验收用随机 H2、合成账号与合成密钥；未访问真实 MySQL/MinIO/供应商，未部署。`huimeng.llm.enabled` 默认 false，生产启用需显式配置端点与团队密钥。

重跑：按[后端 README](../../backend/README.md)启动带 `hm.llm.enabled=true` 与 `hm.llm.deepseek-endpoint` 的夹具，运行[验收脚本](scripts/m3M02AgentCheck.mjs)。
