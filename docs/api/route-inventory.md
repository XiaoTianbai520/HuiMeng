# Toonflow 固定提交路由核对台账

> 状态：M0-04 静态核对与 102 条基础探测已归档（2026-10-22）；逐条权限/入参/样例见 [扩展台账](upstream/route-details.md)。来源为本地参考克隆 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d` 的 `apps/server/src/router.ts`、各 route 模块及 `apps/web/src`。此表不表示接口已迁移或动态运行通过。

已逐一比较表中 127 个 route 模块的工作文件 Git blob 与 `HEAD:<path>`，结果 127/127 相同；另有 14 个关键 Web/Server/内置包文件抽查 14/14 相同。参考克隆 `git status` 标记的 2 个文件也与 HEAD blob 完全相同。运行取证使用 `git archive HEAD` 导出的干净副本。

## 1. 统计与判定规则

- 自动挂载的 `/api` 路由 **127 条**；明确排除 **25 条**，其余 **102 条**为候选。候选不等于一律开放，管理/使用权限与前端实际调用仍须逐项确认。
- “方法”从各 route 模块对根路径 `/` 的 Express 注册语句提取；“Web 源码引用”先查找 `apps/web/src` 的直写路径，另补记工作区文件的 `baseURL` 封装。内置节点与工具包的调用点见下文补记；“未发现直写路径”不等于未使用，动态拼接仍需核对。
- `/mcp` 与 `/a2a` 在 `apps/server/src/app.ts` 单独挂载，不包含在 127 条内；两者仍属于 1.0 局域网授权入口。桌面/移动应用还各自挂载连接与移动导出端点，见第 3 节。
- M0 已归档逐路由源事实、目标角色/项目范围与入口处置、102 条基础请求及主要成功流；全部业务成功流、目标 DTO/OpenAPI 和 Spring 授权测试由 M1–M3 完成，完成后才可逐条加入公开白名单。

## 2. 逐路由清单

| ID | 方法 | 上游路径 | 1.0 入口处置 | Web 源码引用 | 实现源文件 |
| --- | --- | --- | --- | --- | --- |
| R001 | `POST` | `/api/agent` | 项目创作适配 | `apps/web/src/components/agent/conversation.vue` | `apps/server/src/routes/agent.ts` |
| R002 | `POST` | `/api/agent/answer` | 项目创作适配 | `apps/web/src/components/agent/conversation.vue` | `apps/server/src/routes/agent/answer.ts` |
| R003 | `POST` | `/api/agent/canvasResult` | 项目创作适配 | `apps/web/src/components/agent/conversation.vue` | `apps/server/src/routes/agent/canvasResult.ts` |
| R004 | `POST` | `/api/agent/create` | 项目创作适配 | `apps/web/src/components/agent/index.vue` | `apps/server/src/routes/agent/create.ts` |
| R005 | `GET` | `/api/agent/get` | 项目创作适配 | `apps/web/src/components/agent/index.vue` | `apps/server/src/routes/agent/get.ts` |
| R006 | `GET` | `/api/agent/list` | 项目创作适配 | `apps/web/src/components/agent/index.vue` | `apps/server/src/routes/agent/list.ts` |
| R007 | `GET` | `/api/agent/mentionSource` | 项目创作适配 | `apps/web/src/components/agent/mentionSources.ts` | `apps/server/src/routes/agent/mentionSource.ts` |
| R008 | `DELETE` | `/api/agent/message` | 项目创作适配 | `apps/web/src/components/agent/conversation.vue` | `apps/server/src/routes/agent/message.ts` |
| R009 | `PATCH` | `/api/agent/rename` | 项目创作适配 | `apps/web/src/components/agent/index.vue` | `apps/server/src/routes/agent/rename.ts` |
| R010 | `GET` | `/api/agent/skills` | 项目创作适配 | `apps/web/src/components/agent/skillMenu.vue` | `apps/server/src/routes/agent/skills.ts` |
| R011 | `GET` | `/api/agents/a2a/get` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/a2aSettingsDialog.vue` | `apps/server/src/routes/agents/a2a/get.ts` |
| R012 | `PUT` | `/api/agents/a2a/save` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/a2aSettingsDialog.vue` | `apps/server/src/routes/agents/a2a/save.ts` |
| R013 | `POST` | `/api/agents/connect` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/agentConnectDialog.vue` | `apps/server/src/routes/agents/connect.ts` |
| R014 | `GET` | `/api/agents/get` | 成员安全视图 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/agents/get.ts` |
| R015 | `POST` | `/api/agents/install` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/agents/install.ts` |
| R016 | `GET` | `/api/agents/read` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/agentEditorDialog.vue` | `apps/server/src/routes/agents/read.ts` |
| R017 | `PUT` | `/api/agents/save` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/agentEditorDialog.vue` | `apps/server/src/routes/agents/save.ts` |
| R018 | `PUT` | `/api/agents/setEnabled` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/agents/setEnabled.ts` |
| R019 | `DELETE` | `/api/agents/uninstall` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/agents/uninstall.ts` |
| R020 | `POST` | `/api/ai/generate` | 项目创作适配 | `packages/nodeScaffold/src/nodeAi.ts`（内置节点） | `apps/server/src/routes/ai/generate.ts` |
| R021 | `POST` | `/api/ai/media/balance` | 管理员适配 | `apps/web/src/lib/providerBilling.ts` | `apps/server/src/routes/ai/media/balance.ts` |
| R022 | `GET` | `/api/ai/media/balanceEvents` | 管理员适配 | `apps/web/src/lib/providerBilling.ts` | `apps/server/src/routes/ai/media/balanceEvents.ts` |
| R023 | `POST` | `/api/ai/media/generate` | 项目创作适配 | `packages/nodeScaffold/src/nodeAi.ts`（内置节点） | `apps/server/src/routes/ai/media/generate.ts` |
| R024 | `GET` | `/api/ai/media/models` | 项目创作适配 | `packages/nodeScaffold/src/nodeAi.ts`（内置节点） | `apps/server/src/routes/ai/media/models.ts` |
| R025 | `POST` | `/api/ai/media/price` | 项目创作适配 | `packages/nodeScaffold/src/nodeAi.ts`（内置节点） | `apps/server/src/routes/ai/media/price.ts` |
| R026 | `GET` | `/api/ai/models` | 项目创作适配 | `packages/nodeScaffold/src/nodeAi.ts`（内置节点） | `apps/server/src/routes/ai/models.ts` |
| R027 | `GET` | `/api/assets/list` | 项目创作适配 | `apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue` | `apps/server/src/routes/assets/list.ts` |
| R028 | `POST` | `/api/assets/mkdir` | 项目创作适配 | `apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue`<br>`apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue` | `apps/server/src/routes/assets/mkdir.ts` |
| R029 | `GET` | `/api/assets/read` | 项目创作适配 | `apps/web/src/components/agent/mentionContent.vue`<br>`apps/web/src/components/agent/mentionThumbnail.vue` 等 5 处 | `apps/server/src/routes/assets/read.ts` |
| R030 | `DELETE` | `/api/assets/remove` | 项目创作适配 | `apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue` | `apps/server/src/routes/assets/remove.ts` |
| R031 | `POST` | `/api/assets/rename` | 项目创作适配 | `apps/web/src/pages/workspace/panels/canvas/components/assetMenu.vue` | `apps/server/src/routes/assets/rename.ts` |
| R032 | `PUT` | `/api/assets/save` | 项目创作适配 | `apps/web/src/pages/workspace/panels/canvas/components/assetLibrary.vue` | `apps/server/src/routes/assets/save.ts` |
| R033 | `POST` | `/api/browser/input` | 排除 | `packages/tools/browser/src/browserPanel.vue`（须同步移除内置浏览器入口） | `apps/server/src/routes/browser/input.ts` |
| R034 | `GET` | `/api/browser/stream` | 排除 | `packages/tools/browser/src/browserPanel.vue`（须同步移除内置浏览器入口） | `apps/server/src/routes/browser/stream.ts` |
| R035 | `POST` | `/api/desktop/clipboard/read` | 排除 | `apps/web/src/lib/clipboard.ts` | `apps/server/src/routes/desktop/clipboard/read.ts` |
| R036 | `POST` | `/api/desktop/clipboard/write` | 排除 | `apps/web/src/lib/clipboard.ts` | `apps/server/src/routes/desktop/clipboard/write.ts` |
| R037 | `POST` | `/api/desktop/devtools` | 排除 | `apps/web/src/components/settings/panels/developer/index.vue` | `apps/server/src/routes/desktop/devtools.ts` |
| R038 | `POST` | `/api/desktop/openUrl` | 排除 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/desktop/openUrl.ts` |
| R039 | `POST` | `/api/desktop/plugins/install` | 排除 | `apps/web/src/lib/desktopProtocol.ts` | `apps/server/src/routes/desktop/plugins/install.ts` |
| R040 | `POST` | `/api/desktop/providerFile/read` | 排除 | `apps/web/src/components/settings/panels/developer/providerDebugDialog.vue` | `apps/server/src/routes/desktop/providerFile/read.ts` |
| R041 | `POST` | `/api/desktop/providerFile/select` | 排除 | `apps/web/src/components/settings/panels/developer/providerDebugDialog.vue` | `apps/server/src/routes/desktop/providerFile/select.ts` |
| R042 | `POST` | `/api/desktop/ready` | 排除 | `apps/web/src/main.ts` | `apps/server/src/routes/desktop/ready.ts` |
| R043 | `POST` | `/api/desktop/saveFile` | 排除 | `apps/web/src/lib/saveFile.ts` | `apps/server/src/routes/desktop/saveFile.ts` |
| R044 | `POST` | `/api/desktop/selectDirectory` | 排除 | `apps/web/src/pages/home/workspacePicker.vue` | `apps/server/src/routes/desktop/selectDirectory.ts` |
| R045 | `POST` | `/api/desktop/selectSaveFile` | 排除 | `apps/web/src/lib/saveFile.ts` | `apps/server/src/routes/desktop/selectSaveFile.ts` |
| R046 | `POST` | `/api/desktop/update/apply` | 排除 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/desktop/update/apply.ts` |
| R047 | `POST` | `/api/desktop/update/check` | 排除 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/desktop/update/check.ts` |
| R048 | `POST` | `/api/desktop/update/download` | 排除 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/desktop/update/download.ts` |
| R049 | `GET` | `/api/desktop/update` | 排除 | `apps/web/src/components/settings/panels/about.vue`<br>`apps/web/src/components/settings/panels/developer/index.vue` 等 3 处 | `apps/server/src/routes/desktop/update/index.ts` |
| R050 | `POST` | `/api/ext/install` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/ext/install.ts` |
| R051 | `GET` | `/api/ext/list` | 成员安全视图 | `apps/web/src/pages/workspace/panels/document/extensions.ts` | `apps/server/src/routes/ext/list.ts` |
| R052 | `GET` | `/api/ext/read` | 成员安全视图 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/ext/read.ts` |
| R053 | `PUT` | `/api/ext/save` | 管理员适配 | `apps/web/src/pages/workspace/panels/document/extensions.ts` | `apps/server/src/routes/ext/save.ts` |
| R054 | `PUT` | `/api/ext/setEnabled` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/ext/setEnabled.ts` |
| R055 | `DELETE` | `/api/ext/uninstall` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/ext/uninstall.ts` |
| R056 | `POST` | `/api/ffmpeg/cancel` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/ffmpeg/cancel.ts` |
| R057 | `POST` | `/api/ffmpeg/download` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/ffmpeg/download.ts` |
| R058 | `GET` | `/api/ffmpeg/events` | 成员安全视图 | `apps/web/src/components/settings/ffmpegRequired.vue` | `apps/server/src/routes/ffmpeg/events.ts` |
| R059 | `POST` | `/api/ffmpeg/execute` | 项目创作适配 | `packages/ffmpeg/src/browser.ts`（内置 FFmpeg） | `apps/server/src/routes/ffmpeg/execute.ts` |
| R060 | `GET` | `/api/ffmpeg/progress` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue` | `apps/server/src/routes/ffmpeg/progress.ts` |
| R061 | `GET` | `/api/ffmpeg/status` | 成员安全视图 | `apps/web/src/components/settings/panels/pluginMarket/ffmpeg.vue` | `apps/server/src/routes/ffmpeg/status.ts` |
| R062 | `GET` | `/api/mcp/control/events` | 项目创作适配 | `apps/web/src/lib/mcpControl.ts` | `apps/server/src/routes/mcp/control/events.ts` |
| R063 | `POST` | `/api/mcp/control/result` | 项目创作适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/mcp/control/result.ts` |
| R064 | `POST` | `/api/mcp/control/state` | 项目创作适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/mcp/control/state.ts` |
| R065 | `GET` | `/api/mcp/skill` | 成员安全视图 | `apps/web/src/components/settings/panels/mcp/index.vue` | `apps/server/src/routes/mcp/skill.ts` |
| R066 | `GET` | `/api/mcp/status` | 成员安全视图 | `apps/web/src/components/settings/panels/mcp/index.vue` | `apps/server/src/routes/mcp/status.ts` |
| R067 | `PUT` | `/api/mobileLink/config` | 排除 | `apps/web/src/components/settings/panels/deviceLink.vue` | `apps/server/src/routes/mobileLink/config.ts` |
| R068 | `GET` | `/api/mobileLink/heartbeat` | 排除 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/mobileLink/heartbeat.ts` |
| R069 | `POST` | `/api/mobileLink/pair` | 排除 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/mobileLink/pair.ts` |
| R070 | `POST` | `/api/mobileLink/pairing` | 排除 | `apps/web/src/components/settings/panels/deviceLink.vue` | `apps/server/src/routes/mobileLink/pairing.ts` |
| R071 | `POST` | `/api/mobileLink/revoke` | 排除 | `apps/web/src/components/settings/panels/deviceLink.vue` | `apps/server/src/routes/mobileLink/revoke.ts` |
| R072 | `GET` | `/api/mobileLink/status` | 排除 | `apps/web/src/components/settings/panels/deviceLink.vue`<br>`apps/web/src/lib/mobile.ts` | `apps/server/src/routes/mobileLink/status.ts` |
| R073 | `GET` | `/api/nodes/files` | 成员安全视图 | `apps/web/src/pages/workspace/panels/canvas/index.vue` | `apps/server/src/routes/nodes/files.ts` |
| R074 | `GET` | `/api/nodes/get` | 成员安全视图 | `apps/web/src/components/settings/panels/general/nodeMenuSettings.vue`<br>`apps/web/src/pages/workspace/panels/canvas/index.vue` | `apps/server/src/routes/nodes/get.ts` |
| R075 | `POST` | `/api/nodes/install` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/nodes/install.ts` |
| R076 | `PUT` | `/api/nodes/save` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/nodes/save.ts` |
| R077 | `PUT` | `/api/nodes/setEnabled` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/nodes/setEnabled.ts` |
| R078 | `DELETE` | `/api/nodes/uninstall` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/nodes/uninstall.ts` |
| R079 | `GET` | `/api/plugins/export` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/index.vue` | `apps/server/src/routes/plugins/export.ts` |
| R080 | `POST` | `/api/providers/debug/inspect` | 仅内部诊断 | `apps/web/src/components/settings/panels/developer/providerDebugDialog.vue` | `apps/server/src/routes/providers/debug/inspect.ts` |
| R081 | `POST` | `/api/providers/debug/run` | 仅内部诊断 | `apps/web/src/components/settings/panels/developer/providerDebugDialog.vue` | `apps/server/src/routes/providers/debug/run.ts` |
| R082 | `POST` | `/api/providers/media/add` | 管理员适配 | `apps/web/src/components/settings/panels/developer/providerDebugDialog.vue`<br>`apps/web/src/components/settings/panels/mediaModel/addCustomProviderDialog.vue` 等 3 处 | `apps/server/src/routes/providers/media/add.ts` |
| R083 | `DELETE` | `/api/providers/media/delete` | 管理员适配 | `apps/web/src/components/settings/panels/mediaModel/index.vue` | `apps/server/src/routes/providers/media/delete.ts` |
| R084 | `GET` | `/api/providers/media/list` | 管理员适配 | `apps/web/src/components/settings/panels/mediaModel/editProviderDialog.vue`<br>`apps/web/src/components/settings/panels/mediaModel/index.vue` 等 4 处 | `apps/server/src/routes/providers/media/list.ts` |
| R085 | `POST` | `/api/providers/media/models` | 管理员适配 | `apps/web/src/components/settings/panels/mediaModel/index.vue` | `apps/server/src/routes/providers/media/models.ts` |
| R086 | `PUT` | `/api/providers/media/save` | 管理员适配 | `apps/web/src/components/settings/panels/mediaModel/editProviderDialog.vue` | `apps/server/src/routes/providers/media/save.ts` |
| R087 | `POST` | `/api/providers/media/validate` | 管理员适配 | `apps/web/src/components/settings/panels/mediaModel/providerAgentDialog.vue` | `apps/server/src/routes/providers/media/validate.ts` |
| R088 | `POST` | `/api/providers/models` | 管理员适配 | `apps/web/src/components/settings/panels/languageModel/addCustomProviderDialog.vue`<br>`apps/web/src/components/settings/panels/languageModel/addProviderDialog.vue` 等 3 处 | `apps/server/src/routes/providers/models.ts` |
| R089 | `GET` | `/api/settings/get` | 拆分配置 DTO | `apps/web/src/stores/settings.ts` | `apps/server/src/routes/settings/get.ts` |
| R090 | `GET` | `/api/settings/personalization/get` | 个人配置适配 | `apps/web/src/components/settings/panels/personalization.vue` | `apps/server/src/routes/settings/personalization/get.ts` |
| R091 | `PUT` | `/api/settings/personalization/save` | 个人配置适配 | `apps/web/src/components/settings/panels/personalization.vue` | `apps/server/src/routes/settings/personalization/save.ts` |
| R092 | `PUT` | `/api/settings/save` | 拆分配置 DTO | `apps/web/src/stores/settings.ts` | `apps/server/src/routes/settings/save.ts` |
| R093 | `GET` | `/api/settings/systemPrompt` | 管理员适配 | `apps/web/src/components/settings/panels/developer/systemPromptDialog.vue` | `apps/server/src/routes/settings/systemPrompt.ts` |
| R094 | `POST` | `/api/singleAgent/answer` | 项目创作适配 | `packages/tools/askUser/src/questionCard.vue`（内置提问器） | `apps/server/src/routes/singleAgent/answer.ts` |
| R095 | `POST` | `/api/singleAgent/control` | 项目创作适配 | `apps/web/src/components/singleAgentDialog.vue` | `apps/server/src/routes/singleAgent/control.ts` |
| R096 | `POST` | `/api/singleAgent/create` | 项目创作适配 | `apps/web/src/components/singleAgentDialog.vue` | `apps/server/src/routes/singleAgent/create.ts` |
| R097 | `POST` | `/api/singleAgent/run` | 项目创作适配 | `apps/web/src/components/singleAgentDialog.vue` | `apps/server/src/routes/singleAgent/run.ts` |
| R098 | `POST` | `/api/singleAgent/toolResult` | 项目创作适配 | `apps/web/src/components/singleAgentDialog.vue` | `apps/server/src/routes/singleAgent/toolResult.ts` |
| R099 | `POST` | `/api/skills/create` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue` | `apps/server/src/routes/skills/create.ts` |
| R100 | `GET` | `/api/skills/get` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/skills/get.ts` |
| R101 | `POST` | `/api/skills/install` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/skills/install.ts` |
| R102 | `GET` | `/api/skills/list` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue` | `apps/server/src/routes/skills/list.ts` |
| R103 | `PUT` | `/api/skills/move` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue` | `apps/server/src/routes/skills/move.ts` |
| R104 | `PUT` | `/api/skills/order` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue` | `apps/server/src/routes/skills/order.ts` |
| R105 | `GET` | `/api/skills/read` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue` | `apps/server/src/routes/skills/read.ts` |
| R106 | `PUT` | `/api/skills/save` | 管理员适配 | `apps/web/src/components/settings/panels/pluginMarket/skillEditorDialog.vue` | `apps/server/src/routes/skills/save.ts` |
| R107 | `PUT` | `/api/skills/setEnabled` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/skills/setEnabled.ts` |
| R108 | `DELETE` | `/api/skills/uninstall` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/skills/uninstall.ts` |
| R109 | `GET` | `/api/tools/client` | 成员安全视图 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/tools/client.ts` |
| R110 | `GET` | `/api/tools/get` | 成员安全视图 | `apps/web/src/components/agent/menu.vue`<br>`apps/web/src/components/settings/panels/mediaModel/addCustomProviderDialog.vue` | `apps/server/src/routes/tools/get.ts` |
| R111 | `POST` | `/api/tools/install` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/tools/install.ts` |
| R112 | `GET` | `/api/tools/renderers` | 成员安全视图 | `packages/toolScaffold/src/client.ts`（工具渲染器） | `apps/server/src/routes/tools/renderers.ts` |
| R113 | `PUT` | `/api/tools/save` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/tools/save.ts` |
| R114 | `PUT` | `/api/tools/setEnabled` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/tools/setEnabled.ts` |
| R115 | `DELETE` | `/api/tools/uninstall` | 管理员适配 | 未发现直写路径；需查封装/内置插件 | `apps/server/src/routes/tools/uninstall.ts` |
| R116 | `GET` | `/api/workspaces/check` | 项目创作适配 | `apps/web/src/stores/workspace.ts` | `apps/server/src/routes/workspaces/check.ts` |
| R117 | `POST` | `/api/workspaces/files/copy` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/copy.ts` |
| R118 | `POST` | `/api/workspaces/files/importImage` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/importImage.ts` |
| R119 | `GET` | `/api/workspaces/files/list` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/list.ts` |
| R120 | `POST` | `/api/workspaces/files/mkdir` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/mkdir.ts` |
| R121 | `GET` | `/api/workspaces/files/read` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/read.ts` |
| R122 | `DELETE` | `/api/workspaces/files/remove` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/remove.ts` |
| R123 | `POST` | `/api/workspaces/files/rename` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/rename.ts` |
| R124 | `POST` | `/api/workspaces/files/reveal` | 排除 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/reveal.ts` |
| R125 | `PUT` | `/api/workspaces/files/write` | 项目创作适配 | `apps/web/src/lib/workspaceFiles.ts（baseURL 封装）` | `apps/server/src/routes/workspaces/files/write.ts` |
| R126 | `GET` | `/api/workspaces/list` | 替换项目入口 | `apps/web/src/pages/home/workspacePicker.vue` | `apps/server/src/routes/workspaces/list.ts` |
| R127 | `POST` | `/api/workspaces/selectDirectory` | 排除 | `apps/web/src/pages/home/workspacePicker.vue` | `apps/server/src/routes/workspaces/selectDirectory.ts` |

## 3. 自动路由以外的入口

| 入口 | 证据 | 1.0 初判 |
| --- | --- | --- |
| `/mcp` | `apps/server/src/app.ts` 调用 `createMcpRouter` | 公司局域网开放；Spring 校验独立凭证、项目和工具权限 |
| `/a2a` | `apps/server/src/app.ts` 调用 `createA2aRouter` | 公司局域网开放；Spring 校验独立凭证、项目、团队与任务归属 |

桌面/移动应用还分别使用 `apps/desktop/src/connection.ts`、`apps/mobile/src/server.ts` 挂载 `/api/connection`，含 `GET /connection`、`POST /connect`、`POST /disconnect`。移动应用另有 `POST /api/mobile/exports` 和 `GET /api/mobile/exports/:id`。这些不在服务器自动路由的 127 条内，1.0 Web 平台排除。`apps/web/src/main.ts`、`lib/mobile.ts`、`lib/saveFile.ts` 的兼容分支仍会引用这些端点；迁移时要核对分支条件，并以纯浏览器下载路径完成导出回归。桌面 API（包括 `/api/desktop/ready`、`/selectSaveFile`、`/saveFile`）已在 127 条自动路由中，不重复计数。

## 4. 内置能力调用补记

- `packages/nodeScaffold/src/nodeAi.ts` 直接调用文本生成、媒体生成、媒体模型/报价及语言模型接口；不能仅凭 `apps/web/src` 的直写扫描判断这些路由未使用。
- `packages/ffmpeg/src/browser.ts` 直接调用 `/api/ffmpeg/execute`，浏览器内置节点需要此入口。
- `apps/web/src/lib/workspaceFiles.ts` 使用 `baseURL: /api/workspaces/files`，将文件路由拆成相对路径调用；其 `readTextSnapshot`、`writeTextSnapshot` 已用于文档编辑。

## 5. 已补齐的 M0 字段与下一阶段

2026-10-22 的[扩展正文](upstream/route-details.md)与[JSON](upstream/route-details.json)已逐条记录源参数、调用链、响应构造、目标角色/项目、名额与基础样例。102 条探测为 20 条 HTTP 200、82 条 HTTP 400，不是全部成功流。M1/M3 继续将源契约冻结为目标 DTO，并验证身份、业务成功流和安全拒绝。对每条最终纳入 1.0 的路由继续落实：前端/内置能力调用链、请求参数和原始响应样例、HTTP/流式错误、项目目录来源、管理员或成员权限、是否占 5 名额、新路径、自动测试编号及迁移状态。排除路由还应验证 UI 入口和网络请求均已移除。

## 5. 自动路由以外的插件市场

在线插件市场属于现行范围。`apps/web/src/lib/tf.ts` 中 `POST https://api.toonflow.net/v1/plugin/getPlugin` 和 `POST .../v1/plugin/toggleCollection` 是外部调用，不包含在127条本地自动路由里；新增 Spring 代理见[CONTRACTS §2.4](CONTRACTS.md#24-插件市场与管理)。原25条桌面/移动等排除数量不变；节点/工具/技能/扩展安装等管理员候选改为支持受控在线及本地文件安装。远端成功流已于 M0-06 取证，版本/摘要及扩展兼容问题见[市场报告](../archive/m0/M0_PLUGIN_MARKET.md)，所有 publicEnabled 仍为 false。
