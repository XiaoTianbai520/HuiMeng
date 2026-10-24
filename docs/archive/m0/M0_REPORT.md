# M0 固定提交运行核对报告

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> 最终状态（2026-10-22）：M0 已完成收尾，现用 h3Local3060 2.0.0 和 qwenImage21Local 1.0.0，见[当前清单](../../validation/LOCAL_PROVIDERS.md)与[M0 最终收尾](../../validation/M0_FINAL.md)。现用 Qwen 两模型已独立完成 1K Web 生成、落盘、预览及刷新恢复；本页旧 Qwen 记录仅对应当时源码。

> 范围变更（2026-10-22）：本报告记录原范围 M0 结果；随后新增插件市场，已另行完成[M0-06 取证](M0_PLUGIN_MARKET.md)；本报告不替代新增市场结果及其缺陷修复复验。

> 首次取证 2026-10-18～19；补测 2026-10-22。核对对象：Toonflow `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`。这是原版行为基线，不表示绘梦 1.0 已实现。

## 当前结果（2026-10-22）

Web 文本和 Agent 画布工具、新 H3 文生/首帧/首尾帧出片、FFmpeg、MCP 实际工具调用、A2A 提交/查询/取消，以及 Ubuntu 上指定 Bun 版本的完整构建和产物启动均已补测。127 条入口处置与 102 条基础请求证据已归档。详细结果及限制见 [M0 补测与收尾记录](M0_CLOSEOUT.md)。下文保留首次取证历史，旧的“尚缺”状态以补测结果为准。

## 复现环境

由固定提交 `git archive HEAD` 导出临时副本，离线安装依赖，在 `127.0.0.1:3000` 启动上游 `createApp`。浏览器使用本机 Chrome；样例数据置于本机临时 ASCII 路径，避免当前 Windows/Bun 组合对中文绝对路径的 `sendFile` 异常。临时副本与运行数据均不纳入正式源码。

首次环境中上游声明 Bun `1.3.14`，本机为 `1.4.2`。`bun run build:nodes` 成功；完整 `bun run build` 的 Server 步骤在 Windows 报 `Failed to create output directory ENOENT "../../build/server"`，因此先以源码 Server 启动取证。2026-10-22 已在 Ubuntu 宿主机的官方 Bun 1.3.14 容器通过完整 `build:server` 并启动编译产物，见补测报告。

## 已验证

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| Web 页面 | 首次引导、首页、选工作目录、创建项目、进入工作区，浏览器无页面异常 | [页面截图](../../validation/evidence/upstream-first-page.png)、[项目截图](../../validation/evidence/upstream-created-project.png) |
| 画布 | 双击打开节点菜单，创建文本节点；两次自动保存返回 HTTP 200；原 JSON 有 `toonflowCanvas`、`nodes`、`edges` | [菜单](../../validation/evidence/upstream-canvas-node-menu.png)、[节点](../../validation/evidence/upstream-canvas-text-node.png)、[画布夹具](../../validation/fixtures/sample-project/画布1.json) |
| 文档 | 页面打开 Markdown，编辑后 `PUT /api/workspaces/files/write` 返回 200，重新读取为新内容 | [编辑截图](../../validation/evidence/upstream-edited-document.png)、[文档夹具](../../validation/fixtures/sample-project/M0-剧本.md) |
| 文件修订 | 快照读取返回 UTF-8 和 SHA-256 revision；旧 revision 再保存得 HTTP 409 / `ESTALE`，原内容未被覆盖 | [原始接口记录](../../validation/evidence/upstream-file-api.json) |
| 素材 | PNG 二进制写入、原字节读取、复制和列出均成功；重复建 `assets` 目录返回 409 / `EEXIST` | [原始接口记录](../../validation/evidence/upstream-asset-api.json)、[素材夹具](../../validation/fixtures/sample-project/assets/M0-sample.png) |
| 浏览器导出 | 素材库页面下载 `M0-export.png`，391 字节，SHA-256 `1aa4adcae516bea3ced2326c9f5ae4bd8dbe8be9c73f9747cc2dd43e5e8654e9`，与上传图片一致 | [素材库页面](../../validation/evidence/upstream-asset-library.png)、[实际下载文件](../../validation/evidence/upstream-browser-export.png) |
| MCP | 未授权初始化 401；临时启用并授权后初始化 200、`tools/list` 返回 46 个工具；测试后设置恢复 | [原始协议记录](../../validation/evidence/upstream-mcp-api.json) |
| 本地 ComfyUI | `127.0.0.1:8188` 可达，0.37.0，RTX 3060 12GB，队列空；`EmptyImage → SaveImage` 64×64 工作流成功 | [工作流](../../validation/fixtures/comfyui-connectivity.json)、[输出](../../validation/evidence/Huimeng-M0-connectivity_00001_.png) |
| Qwen-Image-2.1 图片生成 | 本地自定义供应商通过 Toonflow 原生成接口提交 ComfyUI，返回 `code:200`，PNG 落到项目 `assets/`；原版 Web 图片节点选择该模型、点击生成并在画布预览成功 | [适配与实测](QWEN_IMAGE_21_PROVIDER.md)、[原始接口记录](../../validation/evidence/upstream-qwen21-provider-api.json)、[页面结果](../../validation/evidence/upstream-qwen21-ui-generated.png) |
| MiniMax H3 文生视频 | 本地自定义供应商经 Toonflow 原生成接口提交 ComfyUI，返回 `code:200`，MP4 落到项目 `assets/`；832×480、约 5.167 秒，含 H.264 视频和 AAC 音频；原版 Web 视频节点可选择该模型 | [适配与实测](MINIMAX_H3_PROVIDER.md)、[原始接口记录](../../validation/evidence/upstream-h3-provider-api.json)、[视频](../../validation/evidence/upstream-h3-toonflow.mp4)、[页面选择](../../validation/evidence/upstream-h3-model-selected.png) |
| DeepSeek 文本与 Agent | 正常文本 SSE 与 Agent NDJSON 已通过；补测文本错误/客户端停止、Agent `read` 工具、取消后同会话继续 | [基线记录](DEEPSEEK_BASELINE.md)、[补测记录](../../validation/evidence/upstream-deepseek-extended.json) |

最初的 64×64 工作流只证明服务可达；随后已分别用 Qwen-Image-2.1 和 MiniMax H3 实际生成图片和视频。视频本机端到端用时约 619 秒，只是一轮 5 秒、480p 文生视频实测，不能代表并发容量。DeepSeek 文本和 Agent 基本会话已接通；独立音频按业务决定暂不做生成成功流测试。

## 首次取证时未完成的样例（当前状态见补测报告）

| 范围 | 实测结果 | 解除条件 |
| --- | --- | --- |
| 文本 SSE 上游取消效果 | 已录制正常输出、无效 Key 的 `error`、首个正文增量后客户端断开；未直接量测上游停止计算 | 1.0 实现时验证供应商取消与计费边界 |
| 视频其他模式 | H3 文生视频已成功；单图首帧与首尾帧尚无真实出片样例 | 需要各自真实出片后才能认定这些模式回归完成 |
| 独立音频 | 默认供应商保留；原先无可用供应商时请求 HTTP 200 / JSON `code:404` | 本轮按业务确认不配置本地独立音频供应商，也不做独立音频成功流测试 |
| Agent NDJSON 其他语义 | 已录制基本会话、`read` 工具、连接中断后 `aborted` 持久化、同会话继续；画布工具未录制 | 补充画布工具与复杂任务场景 |
| FFmpeg | 状态接口可用，但 ffmpeg/ffprobe 均不存在；准备请求 HTTP 424 / `FFMPEG_REQUIRED` | 在复现环境提供 FFmpeg，录制转码、进度、取消、下载 |
| A2A | 未启用团队；访问 HTTP 404 | 配置可用团队和模型，录制 Agent Card、提交/查询/取消 |

早期无模型失败响应原文见[协议限制记录](../../validation/evidence/upstream-protocol-limits.json)；后续成功流见 [DeepSeek 基线](DEEPSEEK_BASELINE.md)。单次成功不能推断吞吐。

## 迁移含义

1. 原版 Web 和 MCP 的响应含本机绝对路径；新平台对外必须只返回项目 ID 与相对路径。测试记录保留原响应，以便做泄漏回归。
2. 文档保存已有修订冲突保护；画布保存没有跨客户端的旧版本检查，迁移时需补齐。
3. 生成类不能按 HTTP 200 单独判成功；Agent 看 NDJSON 事件，媒体还须看 JSON `code`。
4. [用例索引](../../validation/TEST_CASES.md)、[问题清单](../../validation/ISSUES.md)和[任务状态](../../plan/TASKS.md)分别记录已取证范围、缺口与后续依赖。
