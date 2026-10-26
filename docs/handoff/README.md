# 绘梦 1.0 交接入口

> 文档基线：2026-10-26。M0、M1 和 **M2 安全基座及当前接通范围的开发/隔离验收完成**，见[M2 最终验收](../validation/M2_ACCEPTANCE.md)。M3-01 的安全保存、目录操作、独立文档区域及原文件树/文档面板/画布修订链已验证，M3-02 模型列表/设置与文本生成、M3-03 现用 Qwen 两模型图片生成与名额/取消、M3-04 Agent 工具循环与画布控制、M3-05 转码与能力目录已验证，见[M3-W03](../validation/M3_W03_PAGES.md)、[M3-02](../validation/M3_TEXT_GENERATION.md)、[M3-03](../validation/M3_MEDIA_GENERATION.md)、[M3-04](../validation/M3_AGENT_TOOLS.md)与[M3-05](../validation/M3_CAPABILITIES_FFMPEG.md)记录；M3-01 完整 A04/A05/A06/A17、M3-03 视频与跨入口容量未验收，完整 M3、M4 和 1.0 未验收。本批未更新正式 API/Web/Bun 或正式库；设备 CA 安装可选。历史记录中的端口、PID、版本和服务状态接手时须只读核实。

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

M1 已完成独立安装/构建、统一客户端、非 Web 入口清理、市场前端适配、离线协议和浏览器产物许可核对。M2 已接通账号/项目成员/密钥/外部凭证/审计与隔离 HTTPS，并补项目文件页、私有 MinIO 和固定 Qwen 文生图的真实安全验收；最小五名额/幂等在独立 MySQL 通过。原文档保存接口及独立编辑区域已补齐，原文件树/画布修订链、完整创作/市场/协议业务和跨入口容量仍属 M3/M4。102 条历史源请求的 20 个 200、82 个 400 不代表业务全部成功；源台账 publicEnabled 仍全部 false。

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

**M3-07 与 M3-08 管理面已完成。** 25 条排除路由在前端白名单、源码和后端映射三处都不存在，管理端 12 次页面遍历（4 个页面 + 8 个设置面板）0 失败请求、0 排除命中、0 未捕获错误，见[M3-07 记录](../validation/M3_EXCLUDED_ROUTES.md)。插件市场与已安装能力管理接通：登记（`V7`）、文件与市场安装（摘要/版本冲突/归档越界/来源清单）、秘密字段加密与掩码、启停/导出/允许的卸载、技能与 Agent 文档编辑，121 项后端与 26 项前端检查通过，见[M3-08 记录](../validation/M3_PLUGIN_MARKET.md)。剩余：插件运行期加载（工具/节点/市场工具实际执行与 Agent 取用）、自定义媒体供应商、单 Agent 与素材库接口、真实市场凭据复跑，M4 仍未开始。

**M3-06 已完成（HTTP 层）。** `/mcp` 提供 Streamable HTTP 子集（握手、`Mcp-Session-Id`、按凭证裁剪的 26 个工具、工作区文件工具与路径隔离、页面控制桥），`/a2a/{team}` 提供 Agent Card 与 SendMessage/GetTask/CancelTask，任务按客户端+项目落在 `a2a_tasks`，撤销凭证立即 401。54 项 A13 外部协议检查、11 项真实浏览器画布控制、113 项后端与 26 项前端检查通过，见[M3-06 记录](../validation/M3_EXTERNAL_PROTOCOLS.md)。团队 HTTPS 证书与浏览器信任沿用 M2 结论未复验；A2A 流式事件、服务端推送与外部 `generateMedia` 实跑未做。下一步 M3-07（清理排除路由与页面入口、A14），再 M3-08（插件市场，A12）。

**M3-05 已完成（节点包分发除外）。** H3 五模式视频已接入：五个模板随代码分发、规格按 352p/480p/544p×5 比例与 17k+5 帧网格计算、参考图按模式挂载、名额与取消复用图片通道；前端模型下拉按获批项合并并可切换时长/分辨率。81 项视频检查与 113 项后端测试通过，并用真实权重跑通 T2V（5 秒 352p → 209.9 秒、5.17 秒 MP4），见[H3 记录](../validation/M3_H3_VIDEO.md)。I2V/FL2V/L2V/R2V 真实出片、R2V 参考视频/音频、内置节点包仍未完成。

**M3-05 转码与能力目录已完成。** 服务端 FFmpeg 受控转码（transcode/concat/extractAudio、4 个固定预设、SSE 进度百分比、按 requestId 定向取消、结果写入项目媒体、2 GiB/15 分钟/2 线程限制）与成员只读能力目录（nodes/tools/renderers/skills/ffmpeg status）已接通，页面不再出现「工具界面加载失败」与 403/404 提示。108 项后端、29 项 HTTP+真实转码、11 项浏览器回归通过，见[M3-05 记录](../validation/M3_CAPABILITIES_FFMPEG.md)。下一步补 H3 五模式视频与内置节点包分发，再进入 M3-06。

**M3-04 已完成。** 工具循环接通 18 个画布操作（服务端只下发、页面校验并执行，结果经 `/canvasResult` 回填）、`askQuestion` 与 `/answer`、取消唤醒等待项、内部生图单次占位，会话改为 `.jsonl` 并兼容旧 `.json`。104 项默认后端、26 项前端/102 断言、9 项真实浏览器检查通过，见[M3-04 记录](../validation/M3_AGENT_TOOLS.md)。下一步推进 M3-05（FFmpeg 与内置节点/工具/技能，含 H3 视频）或 M3-06（MCP/A2A 与跨入口容量）；单 Agent 委派暂随 M3-06。

**M3-03 图片部分已完成。** M2 提交 `8621501`、M3 首批 `28c87c7`、M3-W02 `fd57510` 已推送；本批实现 Qwen 文生图与单图编辑、3 档像素预算×8 画幅、服务端默认值与 `spec` 回显、执行器返回任务 ID、名额先于执行器探测、真实取消与结果归属。99 项默认后端、26 项前端/102 断言、23 项 HTTP 名额/幂等/取消检查、10 项真实浏览器检查通过，并以工作机 ComfyUI 实跑 1024×1024 与 992×544，见[M3-03 记录](../validation/M3_MEDIA_GENERATION.md)。全部源路由 publicEnabled 仍 false。下一步按依赖推进 M3-04（Agent 工具/画布控制与 `.jsonl` 会话兼容），或补 M3-01 完整素材与 A04/A05/A06/A17；H3 视频随 M3-05、跨入口容量随 M3-06/M4-02。

**M2 当前安全范围验收完成，无待用户操作项。** 用户授权补齐最小 M3 依赖后，普通成员经公开 Spring 精确入口调用获批 Qwen 文生图 1K/1:1，真实 ComfyUI 出图并存入专用 MinIO 开发桶；文件页上传、预览与撤权拒绝通过。用户已亲自确认安全配置导出落盘。详见[M2 最终验收](../validation/M2_ACCEPTANCE.md)、[TASKS](../plan/TASKS.md)及[问题清单](../validation/ISSUES.md)。

最终验证：71 项默认后端测试、21 项前端测试/78 断言及构建、32 项真实接口边界检查；独立 huimeng_dev 的 REPEATABLE-READ 六请求接纳五个、第六个拒绝，取消确认后名额清零。后者使用离线供应商替身，不是五次 GPU 同时生成。M2 收尾时 OpenAPI 为 44 操作/37 路径、35 实现；生成操作明确限制当前规格，92 条 proposed 兼容细项按 M3 逐批细化，127 条源路由 publicEnabled 仍全部 false。

1. M2 源码和证据已在 `8621501` 推送；M3 每批成果与验收记录随独立中文提交归档，接手核对 git log、git status 和远程状态。正式库 huimeng 仍 V3，正式 API/Web/Bun 未更新。开发库 huimeng_dev 已 V4，使用专用低权限账号和独立私有开发桶，不能替代正式运行资源。
2. IDEA 正常 HuiMengApplication：工作目录 backend，参数 `--spring.profiles.active=local`；配置分别在忽略且受限的 `.runtime/mysql-dev/`、`.runtime/object-storage/`、可选 `.runtime/comfy/`，不随 Git 交付。MySQL 直接 TLS 校验，无需预启 SSH。常用断点和命令见[调试指南](../../backend/DEBUGGING.md)。ComfyUI 只连接用户手动启动的实例，不自动启动或下载权重。
3. 临时浏览器 Spring/H2 和 Vite 已主动停止；夹具清理自己在专用桶创建的对象，H2 合成账号/密钥随进程销毁。真实 MySQL 测试恢复模型批准、停用合成账号并归档项目。原 ComfyUI 输出作为用户本地生成结果保留；没有操作用户 ComfyUI 或 IDEA 进程。清理核对见[结果汇总](../validation/evidence/m2-acceptance-summary.json)。
4. M3-01 已有安全保存、目录/文件操作、独立文档区域及原文件树/文档面板/画布修订链，后续完成 M3-01 完整素材与 A 编号回归，再按依赖推进模型/Agent/FFmpeg/协议/市场。M3-03 已有最小行锁名额、幂等和固定 Qwen 图片链，不必重写；仍须补两模型/其他规格、真实取消、跨入口容量/断线重启。原文档 Windows read 缺安全目录句柄仍 503；MinIO 媒体访问可用，不作不安全降级。
5. 本轮没有升级正式部署。后续部署必须先核实主机状态、独立正式桶/身份、V4 迁移及新表最小 DML，并验证服务器到 ComfyUI 的可达性；不能直接覆盖旧 JAR。MinIO 当前局域网 HTTP、成套备份/恢复和许可缺文等见[存储报告](../validation/M2_MINIO.md)，继续 M3/M4。
6. 原 HTTPS 入口记录为 `https://192.168.1.102:18443/`；独立 CA 严格链验证通过，设备安装信任可选。保留浏览器证书警告，不将回环 HTTP 验收或应用 MySQL 信任库等同系统证书信任。Bun 仍只接收短期签名健康请求，没有公开创作通配代理；MCP/A2A 未迁移业务继续 503。

历史证据：[M2 基座部署](../validation/M2_REPORT.md)、[管理页面](../validation/M2_UI_REPORT.md)、[Java 分层](../validation/M2_JAVA_STRUCTURE.md)、[MySQL 开发库](../validation/M2_MYSQL_DEV.md)、[直接 TLS](../validation/M2_MYSQL_TLS.md)、[文件/导出前批](../validation/M2_REMAINING_PROGRESS.md)、[MinIO 接入](../validation/M2_MINIO.md)。各报告的旧数量/版本保持采集时含义；最新结论以上述最终验收为准。不重复 M0，不恢复旧供应商，不提前宣称完整 M3 或 1.0 通过。

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
M0、M1、M2 安全基座和当前接通范围的开发/隔离验收完成，内部 CA 安装可选。
M3-01 的安全保存、目录操作、独立文档区域及原文件树/文档面板/画布修订链已验证；
M3-W01/02/03 完成，M3-01 完整 A04/A05/A06/A17 未验收。M3-03 仍为 M2 最小依赖。
完整 M3/M4 和 1.0 未验收，最新源码未部署。
后续范围以用户授权和 TASKS 为准，不自动扩展工作。
以这些现行文档和固定提交源码为依据，不把 archive、原始证据或 .m0-work
当成新需求，不重新恢复旧供应商，不将 M0 通过当成 1.0 已实现。
先检查实际文件和 Git 状态；按任务依赖完成可评审成果、验证并更新文档。
如本次给你的是其他具体任务，以用户本次授权范围为准。
```
