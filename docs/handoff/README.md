# 绘梦 1.0 交接入口

> 文档基线：2026-10-25。面向没有本次聊天上下文的开发人员和 AI。当前阶段为 **M0、M1 完成，M2 基座已实现、部分验收待完成，M3-01 仅读取依赖部分实施，其余 M3–M4 未开始**；账号/会话、管理界面与部署基础已验证，M2-03/04 仍待跨 M3 业务验收，见 [M2 报告](../validation/M2_REPORT.md)。10-25 本地完成[Java 分层及调试整理](../validation/M2_JAVA_STRUCTURE.md)，服务器仍是 10-24 部署版本。M1 交付及验证边界见[M1 报告](../validation/M1_REPORT.md)。这里的端口、版本和服务状态是最后一次验证记录，接手时应只读核实。

## 1. 先读哪些文档

| 顺序 | 文档 | 负责回答的问题 |
| --- | --- | --- |
| 1 | 本页 | 当前状态、目录、环境、下一步及交接限制 |
| 2 | [PRD](../product/PRD.md) | 1.0 做什么、哪些不做、业务验收边界 |
| 3 | [DESIGN](../architecture/DESIGN.md) | Spring、Vue、Bun、MySQL 如何分工及安全边界 |
| 4 | [CONTRACTS](../api/CONTRACTS.md) | 已冻结 OpenAPI、现有上游协议与仍需细化的兼容 DTO |
| 5 | [TASKS](../plan/TASKS.md) | 任务依赖、完成标准与当前实施状态 |
| 6 | [ISSUES](../validation/ISSUES.md)、[现用供应商](../validation/LOCAL_PROVIDERS.md) | 必须解决的问题与可复用测试模型 |

业务范围以 PRD 为准，技术方案以 DESIGN 为准，接口以 CONTRACTS、[OpenAPI](../api/openapi.yaml)及[冻结记录](../api/M1_CONTRACT_REVIEW.md)为准，实施状态以 TASKS 为准。逐路由源事实以[路由台账](../api/route-inventory.md)和[机器可读明细](../api/upstream/route-details.json)为准。发现冲突时先对照用户已确认范围及实际源码修正文档，不自行扩展需求。

[归档区](../archive/README.md)只保存历史取证，不重新定义当前需求；原始证据中的路径、端口、PID 和“尚未完成”描述保留采集时含义。

## 2. 已确认的产品边界

- **使用背景（用户于 2026-10-24 澄清）**：绘梦是个人团队使用的项目，不是公司项目。不假定有企业 IT、公司审批或企业证书分发流程；早期文档/生成契约中的“公司”“员工”等表述按团队及团队成员理解。统一凭据管理、管理员/成员权限、局域网范围与部署隔离继续沿用。
- **证书选择（2026-10-24）**：设备安装内部 CA 信任为可选，不是 M2 强制前置。保留 HTTPS；未信任的证书提示和真实浏览器验收状态分别如实记录。
- 个人团队内部局域网 Web 平台；独立内部账号，角色只有管理员和项目成员。
- 同项目 5 人同时使用是容量目标；每项目最多 5 个图片、视频、音频或 Agent 活跃任务，跨 Web/MCP/A2A 共用名额，Agent 内部生成不重复占位，第 6 个直接拒绝。不是全平台总共 5 个，也不等于 RTX 3060 可同时进行 5 次采样。
- 供应商 Key 与 TF-Router 市场凭据由团队统一管理；保留原插件市场和已安装管理，管理员操作，成员使用获批能力。原关闭的 Agent 市场安装入口继续关闭。
- MCP/A2A 向局域网获授权客户端开放。公网、桌面、移动、设备互联、实时共同编辑、只读角色、旧数据批量导入、独立排队/任务中心不属于 1.0。
- 保存需要修订冲突保护，不能因为不做实时共同编辑就允许静默覆盖。
- 文本/Agent 用 DeepSeek API；媒体测试用本地 ComfyUI。独立音频保留默认配置、路由、权限与并发语义，不做独立音频成功流测试。插件市场联网不等于允许远程媒体生成。
- 不设置固定负责人、上线日期、专门试用团队或预设性能数值门槛；按验收结果发布。

## 3. 仓库现在有什么

| 位置 | 当前内容 / 使用方式 |
| --- | --- |
| `backend/` | M2 Spring 安全入口、Flyway 迁移、私有 Bun 健康边界与隔离部署配置；见[后端 README](../../backend/README.md) |
| `frontend/` | 独立 Vue 应用、API/契约包、锁文件、构建/类型/测试配置与产物许可；M0 local-nodes 单独保留且不参与构建，见[模块 README](../../frontend/README.md) |
| `docs/product/` | 当前 PRD、市场范围、迁移及验收矩阵 |
| `docs/architecture/`、`docs/api/` | 当前设计、PlantUML 源图、冻结 OpenAPI、127 条目标映射及源路由记录 |
| `docs/plan/`、`docs/validation/` | 任务、问题、M0/M1 报告、当前供应商、证据、夹具及协议脚本 |
| `docs/archive/m0/` | 早期报告、旧供应商安装/修复说明和过程记录 |
| `.m0-work/` | Git 忽略的本机验证缓存；不是正式源码或交接依赖，里面的临时清单/PID 不得直接复用 |
| `.m1-work/` | Git 忽略的源码导出、安装验证与合成 UI 桩缓存；不是安装/构建或后续业务运行依赖 |
| `.m2-work/` | Git 忽略的 M2 检查日志及公用 CA 副本；凭据/私钥不在这里，交接和构建不依赖此缓存 |

M0 已完成上游固定提交、初始许可台账、127 条路由取证、102 条候选基础请求、Web/DeepSeek/Agent、现用 Qwen 两模型、H3 三模式、FFmpeg、MCP/A2A、插件市场以及 Ubuntu 构建/产物运行。入口是[M0 最终收尾](../validation/M0_FINAL.md)。

M1 已完成独立安装/构建、统一请求与项目上下文、非 Web 入口清理、插件市场前端适配、离线协议回归和当前浏览器产物许可核对。M2 已接通 Spring 登录、项目元数据/成员、统一密钥、外部凭证、审计和隔离 HTTPS；**尚未实现/验收**：完整创作业务、文件写入/上传及完整页面、五任务名额、正式创作执行器、模型/协议业务及 1.0 全量验收。102 条基础请求含 20 个 HTTP 200、82 个 HTTP 400，不代表 102 条业务成功流或新平台权限已通过；台账 `publicEnabled` 均为 false。

## 4. 源码、模型与环境

| 项目 | 已记录位置 / 基线 |
| --- | --- |
| 绘梦工作区 | `C:\Users\c1338\Desktop\绘梦` |
| 原版参考克隆 | `C:\Users\c1338\Desktop\workplace\toonflow\Toonflow-app` |
| 固定上游提交 | `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`，根许可 MIT，要求 Bun 1.3.14 |
| 本机验证服务 | 原版 API 3000、开发 Web 5173、ComfyUI 8188；均是原版验证环境，不是绘梦已部署的端口 |
| ComfyUI | `M:\ComfyUI_windows_portable_nvidia\ComfyUI_windows_portable\ComfyUI`；上次为 0.37.0、RTX 3060 12GB、约 32GB RAM |
| Qwen | `qwenImage21Local.ts` 1.0.0；`qwenImage21Text`、`qwenImage21Edit`，1K/1:1 两项 Web 成功流已验证 |
| H3 | `h3Local3060.ts` 2.0.0；5 模型声明，文生/首帧/首尾帧及 Web 播放已验证，尾帧/参考等未全面测试 |
| Ubuntu | 本次已核实 `192.168.1.102`；绘梦 HTTPS `18443`，独立库 `huimeng`，MySQL 8.4.11；服务私网及验证边界见 [M2 报告](../validation/M2_REPORT.md) |

当前供应商哈希和具体模型 ID 只在[供应商清单](../validation/LOCAL_PROVIDERS.md)维护，源码快照见[夹具目录](../validation/fixtures/providers/README.md)。已删除的 `localQwenImage21`、`localMiniMaxH3` 及旧节点预设不能作为现用默认值恢复。

文档和 API 图可以支持继续开发；完整媒体重跑另需对应 ComfyUI、自定义节点、模型权重、运行配置及受控凭据。权重和完整本机安装未随文档打包，不承诺新机仅导入工作流就能生成。按[现用图](../validation/fixtures/comfyui-qwen-current/README.md)与[H3 图说明](../validation/fixtures/comfyui-closeout/README.md)核对依赖，不能在交接检查时自动下载大模型或复用他人密钥。

上游引入按[引入记录](../upstream/UPSTREAM_INTAKE.md)从**指定提交**导出并选择性迁移；先只读检查参考克隆状态，不把当前工作目录的改动、`data/`、密钥、`node_modules/` 或 `.git` 复制进绘梦。不从 `.m0-work/` 未提交缓存反推正式源码。首次引入时记录原路径、提交、MIT 归属、实际依赖及可执行命令。

独立前端安装、检查、构建与启动命令见[frontend/README](../../frontend/README.md)，工作目录为 `frontend/`。本机验证使用 Node 24.11.1、Bun 1.4.2、Python 3.11.9；执行器仍以 Bun 1.3.14 为上游基线。后端构建/测试/启动及机密注入见[后端 README](../../backend/README.md)。M1 合成 UI 检查只在回环地址临时运行，没有部署或连接真实供应商。

## 5. 下一步具体做什么

最新进展见 [M2 剩余项推进记录](../validation/M2_REMAINING_PROGRESS.md)：已补精确文件读取/下载、安全配置导出和逐次模型授权服务；47 项后端测试、21 项前端测试/构建通过，Linux 实际 I/O 36 项检查与 100 次竞态通过，本机 Qwen 实际调用约 50 秒成功。**M2-03/04 仍待完整页面/公开创作链验收**；本轮没有更新正式部署，源路由 `publicEnabled` 仍为 false。M3-01 只记录本轮读取依赖，不能将整批工作标完成。Windows 文件系统缺少安全目录句柄时返回 503；正常本地 MySQL 调试入口仍可用于账号、配置、项目等业务。

2026-10-25 已按用户要求把当前 M2 基座以中文说明提交并推送至 master：[5f0fdeb](https://github.com/XiaoTianbai520/HuiMeng/commit/5f0fdeb1d0965d16ea2a36ae61eb5fe8343799c8)。远端 SHA 与本地一致。现在从该基线继续 M2-03/04，先补文件读取/下载的实际授权链及安全配置导出；必要的 M3 依赖按最小批次单独记录，不将尚未通过的业务项标完成。下文早期“未提交/未部署”的证据保持采集时含义。

**M2 的账号、团队管理和部署基础收尾已完成；M2-03/04 的完整业务验收仍随 M3 补齐**，不要重做 M2-01，也不要跳过待验收项宣称 M2 或 1.0 完成。[M2 报告](../validation/M2_REPORT.md)与[界面收尾](../validation/M2_UI_REPORT.md)记录 30 项后端测试、21 项前端测试、15 项本机真实 Spring 浏览器检查和 49 项部署 HTTPS 检查。本轮补读取与安全导出后，28/40 个平台 OpenAPI 操作有实现；127 条源路由全部保持 `publicEnabled=false`，其中 92 条 proposed 兼容细项仍留 M3。

2026-10-25 后端按 java-standards 适配为 Controller → Service 接口/实现 → JDBC Mapper，独立 ReqDTO/RespDTO/DO，统一异常包；[36 项本地测试和 11 项调试入口 HTTP 检查](../validation/M2_JAVA_STRUCTURE.md)通过。调试从[backend/DEBUGGING](../../backend/DEBUGGING.md)开始，直接 Debug 测试源码中的 LocalDebugApplication，参数 `--local-debug`、工作目录 backend。该工具使用独立内存库及合成账号。此轮未部署，不能将 10-24 的服务器记录当作新结构的部署验证。

同日经用户授权，已[连接服务器并建立独立 MySQL 开发库](../validation/M2_MYSQL_DEV.md) `huimeng_dev`、迁移至 V3，正常 HuiMengApplication 的 14 项 HTTP 检查通过。本机可在 backend 执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-MySqlDev.ps1`，或选择 VS Code“绘梦：服务器 MySQL 开发调试”；受限配置在忽略的 backend/.runtime/mysql-dev，不随 Git 交付。原 `huimeng` 运行库及服务未变，勿将两个库的账号/密钥混用。具体验证与首次开发账号领取方式见调试指南，不粘贴凭据。

用户随后反馈 JDBC URL 格式启动错误，已补显式 local profile、IDEA“绘梦 MySQL 本地”共享配置。之后确认 IDE 单独运行依赖的隧道未监听，**现已改为直接 MySQL TLS，IDEA 无需 ConnectOnly**；在 backend 工作目录添加 `--spring.profiles.active=local` 或选择已保存配置即可。当前 41 项测试、TLS 正负例与 14 项 HTTP 检查通过，见[最新连接报告](../validation/M2_MYSQL_TLS.md)。应用专用 CA/凭据仅在受限忽略目录，不安装系统 CA；旧 SSH 记录保留为历史，服务器部署与待验收项不变。

1. 当前入口为 `https://192.168.1.102:18443/`。独立内部 CA 已严格验证；浏览器尚未信任，报 `ERR_CERT_AUTHORITY_INVALID`。公用 CA 证书在服务器部署目录，工作机副本 `.m2-work/ca.crt` 仅是本地便利缓存，不是构建输入。用户已确认设备安装内部 CA 信任不是刚需，不再将安装作为里程碑前置。继续使用 HTTPS；未安装时浏览器仍提示不受信任，本机真实 Spring/H2 的登录、项目过滤和管理页面已验证；局域网浏览器证书提示仍如实保留，不将本机证据充作生产证书信任证明。
2. M2-01/05/06/07 的基础完成证据见报告。M2-02/08 的账号/会话和部署基础已验；M2-03/04 的授权、路径、密钥 API 与团队管理交互通过，已补实际 read/下载、安全导出及测试用真实 Qwen 调用；A03/A17 完整文件页面和 A11 公开执行链仍随 M3 接通后验收。保留 TASKS 原标准，不把未开放接口的拒绝当作完整业务成功流。
3. 部署在 `/opt/apps/huimeng`、`/data/huimeng/workspaces`、`/var/www/huimeng`，MySQL 仅新库 `huimeng`。迁移任务与运行账号分开，正式进程无迁移凭据。先读[部署 README](../../backend/deploy/README.md)并实时核实；不输出展开的 Compose、机密文件或完整容器环境。
4. Bun 当前仅短期签名的健康接收器，M3 才选择性迁入固定上游业务；禁止挂创作通配代理。MCP/A2A 凭证和能力拒绝已实现，合法请求返回 503 明确表示业务未迁移，不是假握手成功。
5. 当前测试账号已停用、合成项目已归档、凭证已撤销；没有模型调用或团队供应商 Key 输入。首次管理员通过服务器受控初始化文件交付，不能贴到聊天、文档或 Git。接管并更改密码后移除 bootstrap 配置和挂载，保留加密主密钥的受控备份。
6. 本次范围只到 M2。M3 对应批次细化兼容 DTO、文件修订、五名额、取消、FFmpeg、市场和真实协议业务；M4 继续容量、泄漏、备份恢复及发布许可/安全核对。不得提前宣称 M3 或 A01–A18 已验收。

目前无待用户补充的业务问题。会话超时、文件大小、HTTPS 端口、审计保留期等属于实现配置，可依据 DESIGN 做合理选择并记录；改变已确认范围时再与用户核对。

必须保留的迁移条件见 ISSUES：绝对路径泄漏、画布修订保护、HTTP 200 内的业务错误、FFmpeg 间接 I/O、市场版本/摘要语义、节点授权、扩展安装兼容、Qwen 节点保留 2K。未取得新证据不能关闭这些问题。禁止先开放 Bun 或创作通配代理再补权限。

## 6. 如何验证本次交接资料

在绘梦仓库根目录运行，只读检查、不联网、不启动模型：

```powershell
python docs/scripts/check_docs.py
git status --short
```

前者检查文档相对链接/章节锚点、JSON 和验证脚本语法；它不等于业务测试。契约检查及生成一致性见[工具说明](../scripts/README.md)，应用检查见前端 README。后者确认是否还有未提交资料。现行源码、文档、夹具、许可及证据纳入本次 Git 提交，远程为 [XiaoTianbai520/HuiMeng](https://github.com/XiaoTianbai520/HuiMeng)，本地远程名沿用 `orgin`，主分支为 `master`。接手时通过 Git 日志及远程状态确认取得最新提交，并按 README 重新安装依赖；团队密钥、个人设置、node_modules、dist、`.workbuddy/` 和 `.m0-work/`/`.m1-work/`/`.m2-work/` 不进入版本管理。

需要重跑原版测试时，重新准备独立实例/合成项目，核实当前模型、队列和资源；历史临时实例已清理，不复用端口、PID 或失效 manifest。协议脚本用法见[验证脚本](../validation/scripts/README.md)。接口、DeepSeek 和市场测试可能消耗外部服务额度，文档整理本身不需要运行它们。

## 7. 可直接发给接手 AI 的说明

```text
请接手“绘梦 1.0 Toonflow 安全迁移版”。先读仓库 AGENTS.md、
docs/handoff/README.md，再按顺序读 PRD、DESIGN、CONTRACTS、TASKS。
M0、M1 已完成；M2 安全基座与隔离部署已实现，内部 CA 安装可选；本机真实 Spring 浏览器和部署 HTTPS 分别验证通过。
M2-03/04 的跨阶段文件/模型业务按 TASKS 保留待验收；本次若仍限定 M2，不提前实现 M3。
以这些现行文档和固定提交源码为依据，不把 archive、原始证据或 .m0-work
当成新需求，不重新恢复旧供应商，不将 M0 通过当成 1.0 已实现。
先检查实际文件和 Git 状态；按任务依赖完成可评审成果、验证并更新文档。
如本次给你的是其他具体任务，以用户本次授权范围为准。
```
