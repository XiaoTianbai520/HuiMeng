# M0 本地 MiniMax H3 视频供应商验证

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> **历史记录**：此旧 localMiniMaxH3 已删除，未恢复。现用 h3Local3060.ts 2.0.0（5 模型），本次只读核对与历史快照相同；文生、首帧、首尾帧及 Web 成片证据见[补测](M0_CLOSEOUT.md)。现用模型标识和未测范围以[当前清单](../../validation/LOCAL_PROVIDERS.md)为准。

> 2026-10-19；对象为 Toonflow 固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d` 的临时测试实例。此适配器尚未加入绘梦 1.0 正式服务。

## 接入内容

- [供应商源码](../../validation/fixtures/localMiniMaxH3.ts)：供应商 ID `localMiniMaxH3`，模型 ID `minimax-h3-local`，通过 Toonflow 自定义媒体供应商接口安装；不配置远程视频 API Key。
- ComfyUI 地址为同机 `http://127.0.0.1:8188`。供应商使用本机 `minimax_h3_fl2va_pruned_int8_convrot.safetensors`、`qwen3vl_32b_minimax_h3_int8_convrot.safetensors`、视频 VAE、音频 VAE。工作流依据本机 `MiniMaxH3-文生视频.json` 的内部子图展开为 ComfyUI API 图，使用本机实际存在的模型文件名。
- 声明 5 秒、480p，画幅 16:9、9:16、1:1；支持文生视频、单张首帧图生视频、首尾帧。其他参考视频/音频模式不声明也不接收。H3 视频和声音联合生成，声音属于视频文件，不是独立音频供应商。
- `getPrice` 在 Toonflow 中显示“本地算力”，说明使用本机 GPU、无远程视频接口费用；它不声称本地算力没有成本。
- 首尾帧从 Toonflow 项目工作区读取后上传至本机 ComfyUI；生成使用 `/prompt`、`/history/{id}`、`/view`。返回 MP4 字节，交由 Toonflow 原生成链路写入项目 `assets/`。超时或客户端取消时，适配器按 ComfyUI 任务 ID 请求定向取消；正式平台仍需核对上游确已停止，才能释放项目并发名额。

## 已验证

| 层级 | 结果 |
| --- | --- |
| Toonflow 供应商解析 | 原版 `validateMediaProviderSource` 与沙箱加载均通过；`generateVideo` 已注册 |
| 原版接口安装 | `/api/providers/media/validate` 与 `/api/providers/media/add` 均返回 HTTP 200 / `code:200`；模型列表中出现 `localMiniMaxH3` |
| 图生视频契约检查 | 使用伪造的本机 ComfyUI HTTP 响应，确认双参考帧上传、多部分请求、API 图的首尾帧连线和 MP4 返回结构；此项不代表真实出片 |
| 真实视频接口 | Toonflow `POST /api/ai/media/generate` 返回 HTTP 200 / `code:200`，端到端 618.61 秒；项目素材 `assets/m0-h3/videoec3da9f6-acc5-4a7f-8e89-378daca560b6.mp4`，1,014,250 字节，SHA-256 `6552b8a8f291fd3fb08584e25899737bd9afcdf2b1678c028ef8afc7d23fa5ac`；[原始接口记录](../../validation/evidence/upstream-h3-provider-api.json)、[视频](../../validation/evidence/upstream-h3-toonflow.mp4) |
| 文件解码 | MP4 可解码；832×480、约 5.167 秒，含 H.264 视频流和 AAC 音频流；[首帧](../../validation/evidence/upstream-h3-first-frame.png)显示纸船与日出场景 |
| 原版 Web | 视频生成节点可选择 `MiniMax H3 本地视频`，显示 `480p · 5秒` 和“本地算力”，页面无异常；[模型选择截图](../../validation/evidence/upstream-h3-model-selected.png)。本次未从页面再次触发耗时生成，真实生成使用原版 API 完成 |

## 历史测试实例使用（不作为现用安装步骤）

启动本机 ComfyUI 并保持 `127.0.0.1:8188` 可达；在 Toonflow「设置 → 媒体模型 → 添加自定义供应商」导入[适配器源码](../../validation/fixtures/localMiniMaxH3.ts)，然后在视频生成节点选择 `MiniMax H3 本地视频`。M0 取证期间已安装到临时测试数据副本；测试服务在取证后关闭，正式绘梦 1.0 尚未引入此源码。

## 边界

- 此文件是 M0 取证适配器，固定本机地址；正式 1.0 应由 Spring 管理受控服务地址、成员权限、运行归属、取消确认及每项目 5 个任务名额。
- 当前真实出片只验证文生视频。单图首帧与首尾帧通过上传和 API 图契约检查，但尚未各自真实出片，不应标记为已完成生成回归。
- 原版 Web 在这台 Windows/Bun 测试机中加载节点脚本时会遇到中文绝对数据路径 `sendFile` 异常；Web 选择检查使用复制到系统临时 ASCII 目录的同一份测试数据，详见[M0-I07](../../validation/ISSUES.md)。
- 本机 RTX 3060 12 GB 需要对 H3 权重和文本编码器进行内存/显存卸载；单次生成耗时不作为并发容量承诺。五任务名额要在未来的任务准入层验证，不能等同于这台显卡可同时完成五次 H3 采样。
- 独立音频供应商按当前业务决定沿用 Toonflow 默认项，不做本地音频成功流测试。
- H3 模型权重受[MiniMax H3 Community License Agreement](https://huggingface.co/MiniMaxAI/MiniMax-H3/blob/main/LICENSE)约束；正式内部部署前将其原文、适用地域和商业产品界面署名要求纳入第三方许可核对。
