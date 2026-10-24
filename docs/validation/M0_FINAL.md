# M0 最终收尾

> 2026-10-22。结论：M0 完成，可进入 M1。固定 Toonflow 提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`；包含插件市场补验及用户替换后的现用 Qwen 供应商补测。本结论表示原版迁移基线齐备，绘梦 1.0 的 Spring 后端、账号权限和多人任务管理仍按后续阶段实施。

## 1. 本轮补齐的图片流程

实际供应商为 `qwenImage21Local.ts` 1.0.0，SHA-256：`cadefd6096b8eb27277e350f5c96f378325f9a46efcf3b5d6cb9d209e47a68a3`。使用固定提交的隔离 Toonflow 实例、本机 ComfyUI 0.37.0、RTX 3060 12GB；没有使用远程媒体供应商。

| 流程 | 实际模型 | 规格 / 耗时 | 结果 |
| --- | --- | --- | --- |
| 文生图 | `qwenImage21Text` | 1K、1:1、1024×1024；56.56 秒 | 页面创建通用图片生成节点，选择模型、输入提示并点击生成；HTTP 200、正文 code 200，PNG 落盘并显示 |
| 单图编辑 | `qwenImage21Edit` | 1K、1:1、1024×1024；100.88 秒 | 以本轮文生图作为参考，在页面输入编辑指令并生成；HTTP 200、正文 code 200，PNG 落盘并显示 |
| 保存与重新打开 | 上述两个模型 | 同一合成画布 | 两张图片刷新后均正常预览，浏览器 pageErrors 为空 |

图生图的连线画布由隔离测试夹具准备；真实 Web 读取连线、收集参考图并发起生成请求。没有把夹具准备算作手动拖线验证。请求中的参考路径与文生图落盘路径一致。两张输出均已人工视觉检查：蓝色折纸船变为红色，画面有效；单张样例不代表所有编辑指令都能精确保持背景。

证据：[请求、响应、耗时、文件摘要与检查结果](evidence/qwen-final-closeout.json)、[文生图 PNG](evidence/qwen-final-text.png)、[编辑 PNG](evidence/qwen-final-edit.png)、[编辑后画布](evidence/qwen-final-edit-generated.png)、[刷新后画布](evidence/qwen-final-reopened.png)。耗时为本次串行样例，不能作为吞吐或并发承诺。

## 2. 工作流与样例归档

- [现用供应商源码快照](fixtures/providers/qwenImage21Local.ts)：与实际加载文件摘要一致，旧供应商没有恢复。
- [实际工作流](fixtures/comfyui-qwen-current/)：API 图逐项匹配 ComfyUI 成功历史及输出 PNG 内嵌图；比较时排除运行时 `is_changed` 缓存字段。编辑输入图也已归档。
- [合成项目](fixtures/qwen-current-project/)：保存参考连线、提示词、模型选择及实际图片，可用于后续迁移回归。
- ComfyUI 侧栏查阅副本：`2026-10-22-M0-qwenImage21Local-text.json`、`2026-10-22-M0-qwenImage21Local-edit.json`。GUI 节点布局已按坐标检查，无重叠；动态输入控件的 GUI 往返执行未单独验证，重放以 API 图为准。

生成媒体原件保存在 ComfyUI 的 `output/QwenImage21Local/`，本次输出文件名及任务 ID 见结构化证据；独立 Toonflow 临时项目的输出已复制到上述证据和夹具目录后清理。在 ComfyUI `http://127.0.0.1:8188` 的 Workflows 中可打开查阅副本。

## 3. 中止的首次 2K 尝试

原版图片节点切换到 Qwen 后仍保留了 2K、16:9；这不是供应商源码默认的 1K。为避免将约 4MP 档位混入本次基线，测试主动调用该任务专属的 ComfyUI cancel 接口，返回 `cancelled:true`，历史为 `execution_interrupted`，Toonflow 正文 code 500，并未误报成功。随后通过真实页面控件改为 1K、1:1，完成上表两项。

[中止记录](evidence/qwen-final-interrupted-2k.json)仅证明本次指定 ComfyUI 任务被中止及上游错误传播，**不等于验证了 Web“停止生成”到供应商的完整取消链路**，也不能判定 2K 无法生成。默认规格处理登记为 M0-I15，后续迁移时复验。

## 4. M0 完成范围与移交

| 完成项 | 证据 |
| --- | --- |
| 固定提交、引入方式、初始许可台账 | [上游引入记录](../upstream/UPSTREAM_INTAKE.md) |
| Web 文本、DeepSeek、Agent 工具及会话；H3 文生/首帧/首尾帧、页面播放 | [既有补测](../archive/m0/M0_CLOSEOUT.md)、[DeepSeek](../archive/m0/DEEPSEEK_BASELINE.md)；现用 H3 与当时源码同哈希 |
| FFmpeg、MCP、A2A、Ubuntu 构建及编译产物启动 | [既有补测](../archive/m0/M0_CLOSEOUT.md) |
| 127 条路由逐项取证、102 条候选基础请求 | [路由正文](../api/upstream/route-details.md)；完整业务成功流和新权限测试属于后续阶段 |
| 插件市场在线发现及插件生命周期、断网已安装可用、Agent 实际加载工具 | [M0-06 报告](../archive/m0/M0_PLUGIN_MARKET.md) |
| 现用 Qwen 两模型成功流、落盘、预览和刷新恢复 | 本报告第 1 节 |

M0-I12～I14 的市场版本/摘要语义、节点授权、扩展安装兼容问题，以及 M0-I15 的默认规格处理均已移交 [TASKS](../plan/TASKS.md) 和 [ISSUES](ISSUES.md)，没有将其标成已修复。

M1 冻结 OpenAPI/DTO 并建立独立前端构建；M2/M3 实现 Spring 身份、项目隔离、每项目五任务名额、统一密钥与执行生命周期。独立音频按业务确认不做成功流测试；H3 尾帧/参考、其余规格、媒体端到端取消、重启恢复及压力测试仍按后续阶段执行。

## 5. 环境收尾

本轮未修改日常实例的项目、供应商、设置或工具。归档后停止本轮隔离进程并删除其专用临时目录，保留原版 3000、Web 5173 和 ComfyUI 8188。[清理及原文件摘要核对](evidence/qwen-final-cleanup.json)、[M0 最终检查](evidence/m0-final-summary.json)。
