# 绘梦 1.0 交接入口

> 文档基线：2026-10-24。面向没有本次聊天上下文的开发人员和 AI。当前阶段为 **M0、M1 完成，M2–M4 未开始**；下一项是 M2-01。M1 交付及验证边界见[M1 报告](../validation/M1_REPORT.md)。这里的端口、版本和服务状态是最后一次验证记录，接手时应只读核实。

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

- 公司内部局域网 Web 平台；独立内部账号，角色只有管理员和项目成员。
- 同项目 5 人同时使用是容量目标；每项目最多 5 个图片、视频、音频或 Agent 活跃任务，跨 Web/MCP/A2A 共用名额，Agent 内部生成不重复占位，第 6 个直接拒绝。不是全平台总共 5 个，也不等于 RTX 3060 可同时进行 5 次采样。
- 供应商 Key 与 TF-Router 市场凭据由公司统一管理；保留原插件市场和已安装管理，管理员操作，成员使用获批能力。原关闭的 Agent 市场安装入口继续关闭。
- MCP/A2A 向局域网获授权客户端开放。公网、桌面、移动、设备互联、实时共同编辑、只读角色、旧数据批量导入、独立排队/任务中心不属于 1.0。
- 保存需要修订冲突保护，不能因为不做实时共同编辑就允许静默覆盖。
- 文本/Agent 用 DeepSeek API；媒体测试用本地 ComfyUI。独立音频保留默认配置、路由、权限与并发语义，不做独立音频成功流测试。插件市场联网不等于允许远程媒体生成。
- 不设置固定负责人、上线日期、专门试用团队或预设性能数值门槛；按验收结果发布。

## 3. 仓库现在有什么

| 位置 | 当前内容 / 使用方式 |
| --- | --- |
| `backend/` | 空目录；尚无 Spring 或正式 Bun 模块，无 Maven/Gradle 配置 |
| `frontend/` | 独立 Vue 应用、API/契约包、锁文件、构建/类型/测试配置与产物许可；M0 local-nodes 单独保留且不参与构建，见[模块 README](../../frontend/README.md) |
| `docs/product/` | 当前 PRD、市场范围、迁移及验收矩阵 |
| `docs/architecture/`、`docs/api/` | 当前设计、PlantUML 源图、冻结 OpenAPI、127 条目标映射及源路由记录 |
| `docs/plan/`、`docs/validation/` | 任务、问题、M0/M1 报告、当前供应商、证据、夹具及协议脚本 |
| `docs/archive/m0/` | 早期报告、旧供应商安装/修复说明和过程记录 |
| `.m0-work/` | Git 忽略的本机验证缓存；不是正式源码或交接依赖，里面的临时清单/PID 不得直接复用 |
| `.m1-work/` | Git 忽略的源码导出、安装验证与合成 UI 桩缓存；不是安装/构建或后续业务运行依赖 |

M0 已完成上游固定提交、初始许可台账、127 条路由取证、102 条候选基础请求、Web/DeepSeek/Agent、现用 Qwen 两模型、H3 三模式、FFmpeg、MCP/A2A、插件市场以及 Ubuntu 构建/产物运行。入口是[M0 最终收尾](../validation/M0_FINAL.md)。

M1 已完成独立安装/构建、统一请求与项目上下文、非 Web 入口清理、插件市场前端适配、离线协议回归和当前浏览器产物许可核对。**尚未实现**：Spring 登录/项目隔离、统一密钥、五任务名额、正式执行器接入、部署及 1.0 全量验收。102 条基础请求含 20 个 HTTP 200、82 个 HTTP 400，不代表 102 条业务成功流或新平台权限已通过；台账 `publicEnabled` 均为 false。

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
| Ubuntu | 规范记录 `192.168.1.102`，部署前复核；目录、数据库与网络规划见 DESIGN §10 |

当前供应商哈希和具体模型 ID 只在[供应商清单](../validation/LOCAL_PROVIDERS.md)维护，源码快照见[夹具目录](../validation/fixtures/providers/README.md)。已删除的 `localQwenImage21`、`localMiniMaxH3` 及旧节点预设不能作为现用默认值恢复。

文档和 API 图可以支持继续开发；完整媒体重跑另需对应 ComfyUI、自定义节点、模型权重、运行配置及受控凭据。权重和完整本机安装未随文档打包，不承诺新机仅导入工作流就能生成。按[现用图](../validation/fixtures/comfyui-qwen-current/README.md)与[H3 图说明](../validation/fixtures/comfyui-closeout/README.md)核对依赖，不能在交接检查时自动下载大模型或复用他人密钥。

上游引入按[引入记录](../upstream/UPSTREAM_INTAKE.md)从**指定提交**导出并选择性迁移；先只读检查参考克隆状态，不把当前工作目录的改动、`data/`、密钥、`node_modules/` 或 `.git` 复制进绘梦。不从 `.m0-work/` 未提交缓存反推正式源码。首次引入时记录原路径、提交、MIT 归属、实际依赖及可执行命令。

独立前端安装、检查、构建与启动命令见[frontend/README](../../frontend/README.md)，工作目录为 `frontend/`。本机验证使用 Node 24.11.1、Bun 1.4.2、Python 3.11.9；执行器仍以 Bun 1.3.14 为上游基线。后端尚无应用命令。M1 合成 UI 检查只在回环地址临时运行，没有部署或连接真实供应商。

## 5. 下一步具体做什么

**从 M2-01 开始建立 Spring Boot 模块与数据库迁移**，不重复 M0 或重建已完成的 M1。M1-01～06 的具体证据见[M1 报告](../validation/M1_REPORT.md)：38 个 OpenAPI 操作、127 条目标映射、独立生产构建、18 项前端测试、2 项录制比对测试及许可归档。映射中 8 frozen、92 proposed、25 excluded、2 internal，全部仍不对外开放。

后续按以下依赖推进：

1. 阅读 DESIGN 的数据与部署章节，创建 `backend/` 的 Spring 模块、构建/测试/格式化配置、README 和版本化迁移；先离线完成模块与迁移测试。实际连接现有 MySQL 前按既定服务器访问约束核实独立库、最小权限账号及现有服务，不能拿生产库试迁移或在文档写凭据。
2. 按 M2-02/03/04/06 实现会话/CSRF、成员授权和物理目录映射、公司密钥与外部客户端凭证；接口以 OpenAPI 为准。前端已有会话/项目/市场边界，完整账号、成员及供应商管理交互仍需随业务接通。
3. M2-05/07/08 补内部身份、网络隔离、审计、拒绝测试与实际 HTTPS 部署。禁止先开放 Bun 或通配创作代理。前端开发代理默认关闭，只在需要时显式设置 Spring Origin。
4. M3 逐批细化 proposed 兼容 DTO、实现保存修订链、媒体/Agent 名额与取消、FFmpeg 隔离和市场安装。MCP/A2A 保留原协议；A2A 仍需 `A2A-Version: 1.0`，Web 重试 202/Run 不套在协议响应上。
5. 每项关联 FR/A 与问题编号，验证后更新 TASKS/交接；M4 完整业务、安全、恢复和发布回归之前不得标记 1.0 完成。

目前无待用户补充的业务问题。会话超时、文件大小、HTTPS 端口、审计保留期等属于实现配置，可依据 DESIGN 做合理选择并记录；改变已确认范围时再与用户核对。

必须保留的迁移条件见 ISSUES：绝对路径泄漏、画布修订保护、HTTP 200 内的业务错误、FFmpeg 间接 I/O、市场版本/摘要语义、节点授权、扩展安装兼容、Qwen 节点保留 2K。未取得新证据不能关闭这些问题。禁止先开放 Bun 或创作通配代理再补权限。

## 6. 如何验证本次交接资料

在绘梦仓库根目录运行，只读检查、不联网、不启动模型：

```powershell
python docs/scripts/check_docs.py
git status --short
```

前者检查文档相对链接/章节锚点、JSON 和验证脚本语法；它不等于业务测试。契约检查及生成一致性见[工具说明](../scripts/README.md)，应用检查见前端 README。后者确认是否还有未提交资料。现行源码、文档、夹具、许可及证据纳入本次 Git 提交，远程为 [XiaoTianbai520/HuiMeng](https://github.com/XiaoTianbai520/HuiMeng)，本地远程名沿用 `orgin`，主分支为 `master`。接手时通过 Git 日志及远程状态确认取得最新提交，并按 README 重新安装依赖；公司密钥、个人设置、node_modules、dist、`.workbuddy/` 和 `.m0-work/`/`.m1-work/` 不进入版本管理。

需要重跑原版测试时，重新准备独立实例/合成项目，核实当前模型、队列和资源；历史临时实例已清理，不复用端口、PID 或失效 manifest。协议脚本用法见[验证脚本](../validation/scripts/README.md)。接口、DeepSeek 和市场测试可能消耗外部服务额度，文档整理本身不需要运行它们。

## 7. 可直接发给接手 AI 的说明

```text
请接手“绘梦 1.0 Toonflow 安全迁移版”。先读仓库 AGENTS.md、
docs/handoff/README.md，再按顺序读 PRD、DESIGN、CONTRACTS、TASKS。
M0、M1 已完成；从 M2-01 Spring 模块与数据库迁移开始，按 TASKS 依赖推进。
以这些现行文档和固定提交源码为依据，不把 archive、原始证据或 .m0-work
当成新需求，不重新恢复旧供应商，不将 M0 通过当成 1.0 已实现。
先检查实际文件和 Git 状态；按任务依赖完成可评审成果、验证并更新文档。
如本次给你的是其他具体任务，以用户本次授权范围为准。
```
