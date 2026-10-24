# M0 补测与收尾记录

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> 最终状态（2026-10-22）：M0 已完成收尾，现用 h3Local3060 2.0.0 和 qwenImage21Local 1.0.0，见[当前清单](../../validation/LOCAL_PROVIDERS.md)与[M0 最终收尾](../../validation/M0_FINAL.md)。现用 Qwen 两模型已独立完成 1K Web 生成、落盘、预览及刷新恢复；本页旧 Qwen 记录仅对应当时源码。

> 范围变更（2026-10-22）：本报告记录原范围 M0 结果；随后新增插件市场，已另行完成[M0-06 取证](M0_PLUGIN_MARKET.md)；本报告不替代新增市场结果及其缺陷修复复验。

> 2026-10-22。固定上游提交：`d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`。本报告验证 Toonflow 原版基线；绘梦 1.0 的账号、Spring 安全入口与五任务名额尚未实现。

## 1. 环境与隔离

原版 3000、开发页面 5173、ComfyUI 8188 均可访问。本轮在 3002 启动固定提交的独立实例，使用临时 ASCII 数据目录及合成项目。只读取已有 DeepSeek 配置供验证，证据不包含 Key 或 MCP/A2A Bearer 令牌。验证后为当前 Qwen 安装了第 9 节的兼容修正并保留备份；现用项目、密钥设置和 H3 供应商保持原配置。

本地媒体使用 ComfyUI 0.37.0、RTX 3060 12GB、约 32GB 系统内存。供应商源码从当前安装复制到隔离实例并冻结哈希：

| 文件 | 版本 | SHA256 |
| --- | --- | --- |
| [h3Local3060.ts](../../validation/fixtures/providers/h3Local3060.ts) | 2.0.0 | `199ff8d17b875c4bb54160e7175126b28c1213b1de0ff2051cf13d1676c66b17` |
| [Qwen 修正前](../../validation/fixtures/providers/localQwenImage21-before-base64-fix.ts) | 0.1.1 | `e6e98133d25fe759e385a35922f0517e3e4a9e00455dd3603030ca7e5626ffbc` |
| [Qwen 修正后](../../validation/fixtures/providers/localQwenImage21.ts) | 0.1.2 | `001c71301e5df486d2683fa2dc8412dd8ddf0525730bd69e0f32e965f853b307` |

本轮 H3 是另一个 AI 配置的现有 `h3Local3060`；已删除的旧 `localMiniMaxH3` 没有恢复。

## 2. Web 文本与 Agent

在新合成项目中，从原版菜单创建文本节点、键盘输入提示并点击生成，收到文本 SSE `done`，正文写入节点文件。随后在页面调用 Agent，实际产生 `getCanvas`、`addNode` 的 `canvasCall` 与前端回调；节点“M0 Agent 画布验证”自动保存，刷新可重新打开。最终有效复测的 `pageErrors=[]`。

证据：[SSE/NDJSON 与检查结果](../../validation/evidence/upstream-ui-text-agent-closeout.json)、[文本页面](../../validation/evidence/upstream-closeout-text-generated.png)、[Agent 操作](../../validation/evidence/upstream-closeout-agent-canvas.png)、[刷新后页面](../../validation/evidence/upstream-closeout-reopened.png)。

前期自动化尝试因直接替换富文本 DOM、旧会话状态及控件定位而失败；最终使用新的合成项目、正常键盘输入及明确可见控件通过，未修改上游编辑器。历史文本错误/客户端停止、Agent 只读工具/取消/同会话继续见 [DeepSeek 基线](DEEPSEEK_BASELINE.md)。供应商停止计算和计费边界仍须在 1.0 运行生命周期中验证。

## 3. 新 H3 实际出片

通过原版 `POST /api/ai/media/generate` 实测三种模式。每段输出均为 608×352、124 帧、24fps、约 5.167 秒，含 H.264 视频和 AAC 音频。

| 模式 | 模型 ID | 本次耗时 | 成片 |
| --- | --- | --- | --- |
| 文生视频 | `h3T2v` | 156.80 秒 | [MP4](../../validation/evidence/upstream-h3-new-h3T2v.mp4)、[抽帧](../../validation/evidence/upstream-h3-new-h3T2v.png) |
| 首帧图生视频 | `h3I2v` | 168.59 秒 | [MP4](../../validation/evidence/upstream-h3-new-h3I2v.mp4)、[抽帧](../../validation/evidence/upstream-h3-new-h3I2v.png) |
| 首尾帧生视频 | `h3Fl2v` | 166.66 秒 | [MP4](../../validation/evidence/upstream-h3-new-h3Fl2v.mp4)、[抽帧](../../validation/evidence/upstream-h3-new-h3Fl2v.png) |

原版 Web 视频节点另完成一轮：选择“H3 文生视频”、设置 16:9/352p/5 秒、键盘输入并点击生成。HTTP 与业务 `code` 均为 200，播放器 `readyState=4`、时长 5.167 秒，浏览器无页面异常；该轮耗时 258.19 秒。单次耗时随模型加载和资源状态变化，不代表并发容量。

证据：[三模式接口与 ffprobe](../../validation/evidence/upstream-h3-new-provider-closeout.json)、[Web 请求与播放器](../../validation/evidence/upstream-h3-web-closeout.json)、[Web 成片](../../validation/evidence/upstream-h3-new-web-generated.png)。三段 API 成片抽帧均已检查，纸船、水面、日出画面有效；未将本次冒烟测试作为画质、首尾帧精确还原或对白质量验收。

实际图使用本地 INT8 DiT、INT8 32B 文本编码器、视频/音频 VAE 与 Turbo 8 步 LoRA，未改变现有供应商配置。[实际 API 图](../../validation/fixtures/comfyui-closeout)保存了种子与文件名；ComfyUI 工作流侧栏新增 `2026-10-22-M0-h3T2v.json`、`...-h3I2v.json`、`...-h3Fl2v.json` 查阅副本，节点无重叠。GUI 动态控件往返执行未单独复测，复现以 API 图为准。[索引](../../validation/evidence/upstream-h3-executed-workflows.json)

尾帧 L2V、参考 R2V、其他画幅/时长/档位未全面验证。独立音频按已确认范围不做成功流测试；H3 自带音轨不等于独立音频验证。

## 4. FFmpeg

隔离实例通过原版接口下载 FFmpeg/ffprobe 6.1.1，上游流程按内置 SHA256 校验压缩包。合成视频实际转码为 320 像素宽的 H.264/AAC MP4，收到 `start/progress/end`；ffprobe 查询成功；下载输出与落盘字节一致。

另在长转码实际产生进度后，用同一 `requestId` 的 `operation.method="cancel"` 取消：响应 200，执行流以 `AbortError` 结束，没有误发 `end`。[原始记录](../../validation/evidence/upstream-ffmpeg-closeout.json)、[转码输出](../../validation/evidence/upstream-ffmpeg-transcoded.mp4)

| 行为 | 原版接口 |
| --- | --- |
| 下载/安装 FFmpeg 程序 | `POST /api/ffmpeg/download` |
| 取消程序下载、查询其进度 | `/api/ffmpeg/cancel`、`/api/ffmpeg/progress` |
| 转码和转码进度 | `POST /api/ffmpeg/execute`，SSE |
| 取消转码 | 相同 `execute`、相同 `requestId`、`operation.method="cancel"` |
| 下载生成媒体 | 项目文件读取/下载接口 |

原版允许原始 FFmpeg 参数和滤镜；显式文件校验不能限制所有间接 I/O。1.0 须使用获批操作模板/参数白名单，或具备项目文件系统隔离的执行环境。

## 5. MCP 与 A2A

| 协议 | 实际结果 | 证据 |
| --- | --- | --- |
| MCP | 无凭证 401；初始化和发现成功；实际列文件、读取合成文件成功；`../` 越界被拒；会话删除、临时设置恢复成功 | [原始记录](../../validation/evidence/upstream-mcp-closeout.json) |
| A2A | 合成团队由 DeepSeek 实际执行；Card、401、异步提交、查询到完成、Artifact、运行中取消并查询为 CANCELED、未知任务错误通过 | [原始记录](../../validation/evidence/upstream-a2a-closeout.json) |

A2A 使用协议 1.0，客户端须带 `A2A-Version: 1.0`；缺少时 SDK 视作 0.3 并返回 `VERSION_NOT_SUPPORTED`。测试团队只有一个成员，用于真实 runner 和协议验证；不代表多成员团队、外部身份隔离或跨入口五名额已实现。

## 6. Ubuntu 构建与产物运行

通过现有 SSH，在 Ubuntu 26.04.1 LTS x86_64 的专用 `/tmp/huimeng-m0-build.*` 目录解包固定提交公开源码；使用官方 `oven/bun:1.3.14` 临时容器，限制为 2 CPU、3GB 内存、无额外 swap。没有挂载现有应用或数据库。

首次仅 `marked@18.0.13` 的镜像下载发生 `ConnectionRefused`；保留原锁文件，指定官方 registry 重试成功：

```sh
bun install --frozen-lockfile --registry https://registry.npmjs.org
bun run build:server
```

完整构建完成 8 个节点、内置工具、扩展、Web、MCP 和 Server。编译产物又在仅绑定服务器本机随机端口的临时容器中启动，Web HTML、模型列表及 8 个内置节点均加载成功，运行容器随后移除。

| 对象 | SHA256 |
| --- | --- |
| 源码 tar | `0c2836486430d8af981033eb4b0db3cb5b915e5cba01d4bca20dfb8080f21129` |
| 未改动的 bun.lock | `ea22591813ba9625f0c11a4f9e710a950dc50df0714d07baf145873c919b4659` |
| build/server/index.js | `7a71ff55e42057d6e92c1fed937ddf8c173abb53bf1906d6f5f29b598a7092b7` |

镜像 digest：`sha256:e10577f0db68676a7024391c6e5cb4b879ebd17188ab750cf10024a6d700e5c4`。证据：[首次日志](../../validation/evidence/upstream-ubuntu-build.log)、[成功构建](../../validation/evidence/upstream-ubuntu-build-retry.log)、[元数据](../../validation/evidence/upstream-ubuntu-build-retry.json)、[实际启动](../../validation/evidence/upstream-ubuntu-runtime.log)。此项是 Ubuntu 宿主机上的容器验证，不是裸机 Bun 或绘梦部署验收。

## 7. 逐路由核对

127/127 个 route 文件与固定提交 archive 原字节相同。已补齐方法、调用链、Zod 请求定义、原版来源校验、响应构造、目标角色/项目约束、名额及测试编号，见 [逐路由正文](../../api/upstream/route-details.md)和 [JSON 台账](../../api/upstream/route-details.json)。

102 条候选逐条完成基础探测：20 条无参请求返回 200（含 1 条幂等取消 FFmpeg 下载），82 条缺字段/缺有效来源请求返回 400，没有 5xx。[逐条记录](../../validation/evidence/upstream-route-probes.json)已脱敏。

1.0 处置为 39 条项目创作适配、44 条管理员适配、12 条成员安全视图、2 条配置 DTO 拆分、2 条个人配置适配、1 条项目入口替换、2 条仅内部诊断，另排除原定 25 条。管理员安装也不能接受任意远端 URL。这些数量不是普通成员公开接口数量。

本项完成 M0 的源契约、入口处置和逐路由基础样例。M1 冻结 OpenAPI/DTO，M2/M3 再验证目标身份、项目隔离与业务成功流；全部 `publicEnabled=false`。102 条基础请求不代表新平台功能已验收。

## 8. 后续边界

每项目五人/五任务、Web/MCP/A2A 共用名额、断线重启、备份回滚仍属于 M2–M4。绝对路径泄漏、原画布缺跨客户端修订保护、FFmpeg 间接 I/O、任意安装和调试入口，已登记为迁移必须修复的边界。本轮未实现 Spring 拆分或进行局域网发布。

## 9. 当前 Qwen 图片与兼容修正

本节保留当时 `localQwenImage21` 0.1.x 的历史记录；现用 `qwenImage21Local` 1.0.0 的独立生成验证见[M0 最终收尾](../../validation/M0_FINAL.md)。

当前 0.1.1 适配器文生图成功，用时 25.62 秒。单图编辑在提交 ComfyUI 前失败，正文业务 `code:500`、`atob is not defined`。固定提交的供应商 VM 提供 Buffer，未提供 atob 或 FormData；原新增编辑代码依赖了这两个全局对象。

最小修正仅将 base64 解码改为 Buffer，将图片上传表单改为 Buffer 组装 multipart；没有修改模型、提示词、采样工作流或供应商 VM 全局权限。版本升为 0.1.2，先在隔离实例真实重测单图编辑，39.17 秒后返回 PNG；随后校验现用供应商旧哈希、备份并原子安装，3000 服务加载版本 0.1.2、loadError 为空。[失败及复测](../../validation/evidence/upstream-qwen-current-closeout.json)、[安装与备份位置](../../validation/evidence/qwen-sandbox-fix-install.json)

结果：[文生图](../../validation/evidence/upstream-qwen-current-text.png)、[单图编辑](../../validation/evidence/upstream-qwen-current-singleImage.png)。图片均已查看。编辑后主体纸船变红，但背景/构图及输出尺寸也变化，本轮只确认编辑链路可执行，未声称严格保留背景或尺寸。后续若要求这些控制，应按业务需求另做模型工作流验证。

实际 Qwen API 图与输入见 [工作流目录](../../validation/fixtures/comfyui-closeout)和[索引](../../validation/evidence/upstream-qwen-executed-workflows.json)，ComfyUI 中保存为 `2026-10-22-M0-qwenText.json`、`2026-10-22-M0-qwenEdit.json` 查阅副本。

## 10. 收尾核对

[结构化汇总](../../validation/evidence/m0-closeout-summary.json)记录全部基线检查，已核对文档相对链接、JSON 与 Python 语法，并按实际凭证值扫描文档和证据，未发现密钥/令牌原文。临时服务、设置和构建目录在证据归档后清理，现用 3000/5173/8188 保留；清理结果见[环境收尾记录](../../validation/evidence/m0-cleanup.json)。
