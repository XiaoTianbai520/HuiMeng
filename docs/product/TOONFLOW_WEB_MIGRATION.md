# Toonflow 内部 Web 平台一期迁移清单与验收矩阵

## 1. 决策与范围

本页维护路由分组和 A01–A18 验收定义。业务范围统一见 [PRD](PRD.md)，当前阶段见 [TASKS](../plan/TASKS.md)；M0 已完成，源路由取证不等于目标平台验收。

固定上游提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`，127 条自动路由，排除 25 条，剩余 102 条已逐项记录源事实与目标入口处置。MCP/A2A 与市场外部调用另计。详细参数、调用链和请求样例见[逐路由明细](../api/upstream/route-details.md)。

## 2. 一期架构边界

Vue 独立前端 → Spring Boot 唯一业务入口 → 单实例 Bun 内部执行器；MySQL 保存账号、授权、项目映射及运行记录，工作区沿用原项目文件。完整设计见 [DESIGN](../architecture/DESIGN.md)，接口草案见 [CONTRACTS](../api/CONTRACTS.md)。

## 3. 基于代码路由的迁移清单

下表中的路径均以 `/api` 为前缀。`Spring 入口 → Bun 执行` 表示 Spring 负责身份、权限和必要的操作日志，Bun 暂时保留业务执行。每组要逐条记录 HTTP 方法、请求/响应样例、错误码和流式协议；不能仅凭路径判断行为。

| 路由组与数量 | 上游路径 | 一期处置 | 权限和关键改造 |
| --- | --- | --- | --- |
| 工作区与文件（10） | `workspaces/check`、`workspaces/list`；`workspaces/files/{copy,importImage,list,mkdir,read,remove,rename,write}` | Spring 项目入口 → Bun 文件执行 | `directory` 改为项目 ID 映射；`workspaces/list` 改成有权限的项目选择，不回传 `absolutePath`；每次读写校验权限和路径；画布/剧本写入冲突时提示用户，不静默覆盖 |
| AI 生成（7） | `ai/generate`、`ai/models`、`ai/media/{balance,balanceEvents,generate,models,price}` | Spring 入口 → Bun 模型适配 | 供应商密钥只在服务端；图片/视频/音频生成计入每项目 5 个并发名额，单独文本生成不计入；保留流式事件、取消和错误语义 |
| 资产（6） | `assets/{list,mkdir,read,remove,rename,save}` | Spring 入口 → Bun/受控存储 | 将原全局资产语义改为项目或组织范围；上传、下载、删除均授权；记录引用关系 |
| Agent 会话（10） | `agent`、`agent/{answer,canvasResult,create,get,list,mentionSource,message,rename,skills}` | Spring 入口 → Bun Agent | 一次 Agent 运行占每项目 1 个并发名额，内部生成调用不重复计数；会话绑定项目与发起人；保留 NDJSON 事件和问答回调 |
| 单 Agent（5） | `singleAgent/{answer,control,create,run,toolResult}` | Spring 入口 → Bun Agent | 上游 `run` 不带项目目录；项目外使用按账号单独限流，项目内使用需先绑定项目并占 1 个名额；临时会话与工具结果绑定发起用户 |
| FFmpeg（6） | `ffmpeg/{cancel,download,events,execute,progress,status}` | Spring 入口 → Bun FFmpeg | 限制单用户并发与资源占用；结果文件仅授权项目可访问；保留 SSE/进度/取消行为 |
| 供应商（9） | `providers/models`、`providers/debug/{inspect,run}`、`providers/media/{add,delete,list,models,save,validate}` | Spring 管理入口 → Bun 适配 | 团队管理员配置与调试；返回值脱敏。项目成员通过 `ai/models` 选择获批模型，不能调用供应商接口读取密钥或探测任意地址 |
| 设置（5） | `settings/{get,save,systemPrompt}`、`settings/personalization/{get,save}` | 优先由 Spring 改造 | 分离个人界面偏好与团队统一模型配置；替代全局整包读写；密钥只写不回显 |
| 扩展（6） | `ext/{install,list,read,save,setEnabled,uninstall}` | 保留市场及已安装扩展管理接口 | 保留在线及本地文件安装；更新和启停限管理员，记录来源并隔离执行 |
| 节点（6） | `nodes/{files,get,install,save,setEnabled,uninstall}` | Spring 管理入口 → Bun 插件运行 | 迁移在线及本地文件安装管理；项目成员使用已批准节点 |
| 工具（7） | `tools/{client,get,install,renderers,save,setEnabled,uninstall}` | Spring 管理入口 → Bun 插件运行 | 迁移在线及本地文件安装管理；客户端工具须逐项评估 Web 可用性 |
| 技能（10） | `skills/{create,get,install,list,move,order,read,save,setEnabled,uninstall}` | Spring 入口 → Bun 技能运行 | 保留原有内置技能使用与必要配置；安装、修改和执行受权限约束 |
| Agent 包（9） | `agents/{connect,get,install,read,save,setEnabled,uninstall}`、`agents/a2a/{get,save}` | Spring 管理入口 → Bun 执行 | A2A 对外入口和可用团队由管理员配置；普通用户只运行获批能力，外部调用按项目凭证授权 |
| 插件导出（1） | `plugins/export` | Spring 管理入口 → Bun 导出 | 限管理员；导出内容和下载链接设有效期 |
| MCP 控制（5） | `mcp/status`、`mcp/skill`、`mcp/control/{events,result,state}` | Spring 入口 → Bun 控制连接 | 外部 MCP 调用必须映射到获授权项目和页面连接；控制会话按用户、项目、连接隔离；原单进程状态需处理重启；不可把外部凭证交给浏览器长期保存 |

102 条是已完成源事实和目标处置的候选集合，尚未成为对外白名单：创作主流程应优先迁移；供应商和插件管理接口仅按管理员实际需要开放；插件市场及受控远程安装纳入 1.0。在线目录和收藏是自动路由之外的外部服务调用，单独列入[市场范围与契约](PLUGIN_MARKET.md)。已安装的节点、工具、技能仍须可使用和配置。MCP 控制接口及其所依赖的 Web 页面连接纳入一期。接口是否必做，以 M0 的页面调用点和功能基线台账为准。被排除的路由不能因为网关透传而重新暴露。

**单独挂载的接口**：`/mcp` 与 `/a2a` 不是上述自动生成路由，但一期明确对外开放。Spring 网关须为两者提供 HTTPS、独立的客户端凭证、项目与工具权限、限速、审计和撤销能力；Bun 仅接受 Spring 的内部调用。上游 MCP 使用一份配置 token 与单进程页面连接；A2A 使用一份配置 token 和一个工作目录，任务状态在进程内。迁移时要将外部调用绑定到具体项目，避免跨项目访问；单实例重启后未完成的任务应明确返回失效/重试信息，不得误报完成。[MCP 控制实现](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/utils/mcp/control.ts)、[A2A 入口](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/agent/a2a/index.ts)、[A2A 设置](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/agent/a2a/settings.ts)。

### 明确排除的 25 条路由

| 路由 | 数量 | 处置 |
| --- | ---: | --- |
| `/api/desktop/**` | 15 | 不部署、不代理；移除桌面更新、剪贴板、系统文件选择等 UI 入口 |
| `/api/mobileLink/**` | 6 | 不部署、不代理；移除扫码配对和设备互联入口 |
| `/api/browser/{input,stream}` | 2 | 上游实现仅桌面端或开发模式可用，一期不对内部 Web 开放 |
| `/api/workspaces/selectDirectory`、`/api/workspaces/files/reveal` | 2 | 本机目录选择及文件管理器定位不适合服务器 Web；用项目选择和文件下载替代 |

以上排除项来自[路由生成文件](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/router.ts)及[本机目录选择实现](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/apps/server/src/routes/workspaces/selectDirectory.ts)。剩余 102 条是已核对源事实、尚待目标实现的 Web 候选接口，**不代表 102 条都要原样公开给普通创作者**。

## 4. 必须先解决的跨模块问题

身份/项目隔离、密钥、画布修订、任务名额、流式错误、外部协议及插件执行边界统一见 [DESIGN](../architecture/DESIGN.md) 和 [ISSUES](../validation/ISSUES.md)。开发和验收关联问题编号，不重复创建另一套问题状态。

## 5. 迁移任务与完成标准

任务依赖及状态统一维护在 [TASKS](../plan/TASKS.md)。每批在逐路由明细中记录目标方法/路径、DTO、角色和项目约束、事件样例、测试与验收编号；无需负责人字段。未经授权和契约测试的接口保持关闭。

## 6. 一期验收矩阵

| ID | 场景 | 验收结果 | 验证方式 |
| --- | --- | --- | --- |
| A01 | 内部账号 | 管理员可创建、停用账号；未登录无法访问业务 API；退出后会话失效；登录限速且密码不明文存储 | 浏览器、接口与配置检查 |
| A02 | 项目成员权限 | 成员能访问授权项目；非成员和普通成员调用管理员接口被拒绝 | 跨角色接口测试 |
| A03 | 项目隔离 | 修改项目 ID、文件路径、下载链接不能读取其他项目数据 | 越权与路径用例 |
| A04 | 原格式兼容 | 上游样例或新建测试项目中的画布、剧本、角色、场景及素材可打开、保存并正确显示 | 样例项目回归 |
| A05 | 保存冲突 | 修改后重开数据一致；两个用户基于同一旧版本保存时第二次得到冲突提示，不静默覆盖；用户可以重新读取再保存 | 双浏览器并发测试 |
| A06 | 文件操作 | 上传、复制、重命名、删除、下载及大文件读取与原 Web 流程等价；失败产生的不完整产物可识别、可清理 | 端到端与异常注入 |
| A07 | 文本生成 | 模型列表、提示词输入、流式输出、停止与错误提示可用 | 真实或可控模型服务回归 |
| A08 | 图像/视频/音频生成 | 图片、视频提交、进度、结果预览、失败重试与素材落盘可用；任务归属正确。独立音频接口及默认供应商配置保留，本轮按业务确认不执行独立音频成功流测试 | 图片与视频使用本地 ComfyUI 小样本回归；音频仅检查路由、配置与权限，不声称生成成功已验证 |
| A09 | Agent 与画布 | 会话创建、问答、画布操作、取消、恢复；NDJSON 事件顺序和结束标志正确 | 样例脚本与页面回归 |
| A10 | FFmpeg | 合成/转码、进度、取消和下载可用；超时后资源释放 | 样例媒体回归 |
| A11 | 设置和密钥 | 团队管理员可配置供应商密钥，项目成员只能选获批模型；密钥不出现在日志、响应、导出中，配置后模型可用 | 接口检查与日志抽查 |
| A12 | 插件市场与能力管理 | 在线发现/搜索/分类/分页/卡片描述与版本/收藏、已安装列表可用；管理员在线及本地文件安装/更新、配置、启停、导出和允许的卸载可用；成员不能管理插件或读取凭据；Agent 工具实际加载；市场故障不影响已安装能力；原关闭入口沿用原状态 | 市场补验、页面、网络、角色与执行隔离回归 |
| A13 | MCP/A2A 对外入口 | 局域网内的外部客户端凭专用凭证可通过 Spring HTTPS 访问 `/mcp`、`/a2a`；工具、团队、画布和任务均受项目权限限制；无效/撤销凭证、跨项目调用被拒绝；Bun 原端口无法从客户端访问，平台不发布公网 | MCP 工具、A2A 提交/查询/取消、凭证撤销和跨项目测试 |
| A14 | Web 专用体验 | 无桌面、移动、设备互联、本机文件管理器入口；仅桌面端可用的浏览器工具不出现在 Web 节点菜单；主流程没有 404 调用 | 页面遍历与网络记录 |
| A15 | 备份与回滚 | 新增项目和基线项目均可备份恢复；一次迁移失败后恢复到迁移前状态 | 恢复演练 |
| A16 | 并发与稳定性 | 同一项目 5 人同时使用；图片/视频/音频生成及项目内 Agent、A2A 运行合计最多 5 个，第 6 个提示达到上限且不调用供应商；Agent 内部生成不重复计数；单独文本生成不占名额；项目外单 Agent 按账号限流；不同项目分别计数；完成或确认取消后释放名额 | 首轮用本地 ComfyUI 测试多项目、多类型、Web 与外部客户端并发及断线，记录工作流、设备、实测耗时、错误率和资源占用 |
| A17 | 项目选择 | 普通成员只看到获授权项目；项目列表、文件接口和浏览器网络响应均不暴露服务器绝对路径 | 多角色页面与接口检查 |
| A18 | 断线与重启 | 生成、转码或 A2A 任务中断后不会误报完成；Web 与外部客户端可识别失败或任务失效；单纯断线保留仍在执行任务的名额，执行结束或确认停止后释放资源与名额；重试遵守幂等与容量约束 | Web、MCP/A2A 断线与重启测试 |

**上线门槛**：A01–A18 全部通过；A16 的耗时和错误率先记录本地 ComfyUI 实测，不预设数值发布门槛。任何“功能等价”例外必须写进变更说明，并由业务方确认。桌面端、移动端不属于本矩阵；插件市场属于 A12。

## 7. MCP/A2A 开放规则

- 仅通过 Spring Boot 的局域网 HTTPS 入口开放，不发布公网；Bun 端口不可从客户端访问；保留上游 MCP/A2A 协议语义与必要的流式响应。
- 管理员为每个外部客户端签发独立、可撤销的凭证，并指定可访问项目、MCP 工具及 A2A 团队。凭证不得写入前端页面设置、URL 或日志。
- 外部请求在网关完成认证和项目授权后才进入 Bun；Bun 从受信任的内部上下文取得项目目录，不接受外部客户端传入的绝对路径。
- MCP 页面控制会话绑定项目与连接；A2A 任务的提交、查询和取消绑定相同调用方。服务重启导致内存任务失效时返回明确错误，调用方可以重新发起。
- 外部 Agent/媒体操作与 Web 操作共用每项目 5 个并发名额及必要的请求限速，避免从外部接口绕过容量约束。

## 8. 许可与代码治理

上游根项目采用 [MIT 许可证](https://github.com/HBAI-Ltd/Toonflow-app/blob/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d/LICENSE)。fork 或引入代码时保留其版权及许可文本；同时对第三方依赖、模型服务协议、插件来源和素材授权分别建台账。建立上游同步策略：固定版本、记录本地改动、先跑回归再合并上游更新。
