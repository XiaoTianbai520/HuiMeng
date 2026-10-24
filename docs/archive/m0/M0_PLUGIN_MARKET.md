# M0-06 插件市场验证报告

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> 最终状态（2026-10-22）：M0 已完成收尾，现用 h3Local3060 2.0.0 和 qwenImage21Local 1.0.0，见[当前清单](../../validation/LOCAL_PROVIDERS.md)与[M0 最终收尾](../../validation/M0_FINAL.md)。现用 Qwen 两模型已独立完成 1K Web 生成、落盘、预览及刷新恢复；本页旧 Qwen 记录仅对应当时源码。

> 2026-10-22。**原版行为取证完成，发现的兼容与安全问题转 M1/M3 处理。** 本报告不表示绘梦 1.0 插件市场已实现，也不表示原版可直接作为多人服务发布。

## 1. 环境与边界

- 固定提交：`d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`；本地 Bun 1.4.2，Windows，Chrome 无头浏览器和 Python Playwright。
- 新建 `huimeng-m0-market-*` 临时数据目录，复用固定提交的构建产物；HTTP 只监听本机随机端口。项目、生命周期测试和官方技能安装均在此目录内完成。
- 使用当前已配置的公司 TF-Router 凭据查询市场；使用 DeepSeek 验证工具调用；没有运行媒体生成或远程模型生成。测试请求和报告均不保存真实 Key 或制品下载签名。
- 日常实例 `3000` 起初只安装 `deleteTool`、`videoComposer`。本轮仅补装缺失的五个原版基础工具；两个既有插件的文件摘要和 settings 原字节均未改变。未修改用户现有画布。

## 2. 已验证结果

| 范围 | 实际结果 | 证据 |
| --- | --- | --- |
| 在线目录 | 真实鉴权成功；当时共 13 项，分类为 2 个节点、3 个工具、8 个技能；ext/agent/my 分类为空仍正常返回 | [目录与请求](../../validation/evidence/upstream-market-catalog.json) |
| 搜索、分页 | Toonflow 搜索、无结果搜索成功；API 按每页 5 项查询第 1/2 页，ID 不重叠。页面默认每页 20 项，当时不足两页，因此不宣称点击过页面第 2 页 | 同上；[页面](../../validation/evidence/upstream-market-web.json) |
| 收藏 | 实际切换选定官方技能的收藏、查询收藏列表、读取现态后恢复初始状态；未盲重试切换接口 | [目录记录](../../validation/evidence/upstream-market-catalog.json) |
| 在线安装 | 页面实际点击安装官方 `v118workflow` 文档技能，后端通过真实制品 URL 下载并安装成功；随后查看已安装技能源码 | [页面记录](../../validation/evidence/upstream-market-web.json)、[技能说明截图](../../validation/evidence/upstream-market-skill-details.png) |
| 工具生命周期 | 合成回声工具安装、同版冲突、升级、降级拒绝、坏元数据/坏脚本拒绝、保存配置、无效配置拒绝、启停、导出、下载失败保留现版均取证 | [29 项后端记录](../../validation/evidence/upstream-market-plugins.json) |
| 节点、扩展、技能 | 合成节点及技能安装/升级/配置或编辑/移除；扩展的原构建产物安装失败已记录，兼容导出语法的副本生命周期通过，详见 §4 | [26 项分类记录](../../validation/evidence/upstream-market-categories.json) |
| 页面管理 | 本地文件上传升级、显示新版本、配置保存、真实开关点击、浏览器导出字节匹配、页面卸载合成工具成功 | [页面](../../validation/evidence/upstream-market-web.json)、[卸载及凭据补测](../../validation/evidence/upstream-market-web-finish.json) |
| 缺少/错误凭据 | 原页面显示 Key 配置提示；错误 Key 返回 401、出现错误提示且未被保存；隔离 Key 已恢复 | [记录](../../validation/evidence/upstream-market-web-finish.json)、[截图](../../validation/evidence/upstream-market-invalid-key.png) |
| 市场断网 | 浏览器仅中断市场目录请求，显示可重试错误；已安装列表、本地文件安装、后续本地工具仍可用。这不是整机断网或 DeepSeek 断网测试 | [页面](../../validation/evidence/upstream-market-web.json)、[截图](../../validation/evidence/upstream-market-offline.png) |
| 画布与模型工具 | DeepSeek 实际调用 `m0MarketEcho`、`listMediaModels`、`getCanvas`、`addNode`；更新后的回声工具读取页面保存的配置；新增节点落盘并刷新重开成功，页面无 JS 异常 | [完整事件](../../validation/evidence/upstream-market-web.json)、[截图](../../validation/evidence/upstream-market-agent-tools.png) |
| 日常实例 | 补装五个基础工具后工具数为 7，原两个插件保留；3000 上 DeepSeek 实际调用 `listMediaModels/getCanvas` 并完成回调 | [安装记录](../../validation/evidence/upstream-market-daily-tools.json)、[真实调用](../../validation/evidence/upstream-market-daily-agent.json) |

日常实例的只读画布回调由测试客户端依据真实文件快照返回，不是用户正在打开的浏览器画布；完整 Web 查询、创建和重开由隔离页面验证。两类证据分别保留，没有把协议夹具冒充用户页面。

## 3. 原版页面的准确功能边界

发现页展示名称、描述、作者、版本和安装按钮；固定提交没有为在线条目提供额外详情接口或独立详情弹窗。已安装插件有 README 时可查看说明，已安装技能进入源码查看/编辑窗口。迁移中的“详情”按这些原有行为验收。

`agentMarketEnabled=false` 仍然生效，Agent 市场按钮保持禁用。已存在的内部团队/A2A 范围不因此取消。原版禁止从页面卸载的官方节点/工具未被作为普通页面卸载成功流测试。

## 4. 发现的问题与迁移处理

### M0-I11：日常实例缺少基础工具（已处理）

原来只有两个第三方工具，缺少画布和媒体生成工具，符合此前 Agent 提示的真实限制。本轮通过原安装接口新增 `canvas`、`mediaGeneration`、`workspace`、`skillOperator`、`askUser`，均来自固定提交的构建产物；没有覆盖现有插件。下一次 Agent 运行会重新加载工具，通常不需要重启。迁移部署必须包含基础插件清单和实际工具调用检查。

### M0-I12：市场目录与下载包版本不一致，摘要语义待确认

测试的官方市场条目 ID 60 标为 `1.0.0`，实际 ZIP 内 `SKILL.md` 标为 `1.1.0`。目录额外返回的 `fileHash` 为 `4157b9da127ef31448865fae2d1700b156249f36dca7a1d5de64cd6bcaace791`，下载字节 SHA-256 为 `6674e568124c9de1b33d2c289d3039e9ff1386aa8000b335fedb920d6db2fa12`。**固定提交未定义 fileHash 算法，也未在安装链中使用它，不能直接将其认定为可验证的 SHA-256。** [原始检查](../../validation/evidence/upstream-market-artifact.json)

本轮仅在隔离目录安装纯文档技能以确认原版行为，没有执行技能内容或安装到日常实例。新平台必须核对制品类型/标识/版本，并建立算法明确的摘要清单；不一致时拒绝安装，保留旧版，提示管理员更新来源信息。原版页面只传 `url/fileName`，需要由 Spring 补齐检查。

### M0-I13：原节点启停缺少本地守卫，远程下载缺少公司来源授权

不带本地工作区授权头、声明非本地客户端的合成节点启停仍返回 200；对照工具/扩展等管理操作返回 403。此负例只针对自建测试节点执行并恢复，不能把“本机限制”当作公司 RBAC。隔离安装器也接受本机测试制品源及跳转，说明来源白名单/SSRF 策略仍须新平台实现；没有访问云元数据或真实内网敏感服务。

### M0-I14：文件扩展构建产物与安装器不兼容

本次本地构建的原 `ext-txt.umd.js` 使用反引号导出形式 `toonflowExts[\`ext-txt\`]`，原安装器的正则只接受单双引号，原字节安装返回 400。为继续区分生命周期能力，测试副本只调整标识、版本和导出引号后，安装/升级/配置/启停/导出/卸载通过。**这不表示原构建产物问题已经修复。**

M1/M3 须统一构建产物和安装校验，并用真实发布构建产物回归；不能以测试副本成功替代修复验证。本轮没有修改上游源码或用户现有扩展。

## 5. 交付与后续

- M0-06 完成的是源行为、真实事件和故障取证；M0-I12～I14 仍为迁移条件，纳入 M1/M3-08 与 A12。
- 合成工具源码见[夹具说明](../../validation/fixtures/market/README.md)，后端复验入口见[脚本说明](../../validation/scripts/README.md)。下载的第三方制品未作为业务插件引入绘梦源码。
- 最终校验和清理状态见[收尾记录](../../validation/evidence/m0-market-summary.json)及[环境清理](../../validation/evidence/m0-market-cleanup.json)。
