# 当前本地媒体供应商

> 2026-10-22。现用供应商以本页和[加载核对记录](evidence/current-local-providers.json)为准；现用 Qwen 的两项实际生成已补齐，见[M0 最终收尾](M0_FINAL.md)。历史供应商文件与测试记录仅供追溯，不作为重新安装清单。本轮只在独立测试项目生成，未修改日常供应商、配置或用户项目。

## 1. 现用供应商

安装目录：`C:\Users\c1338\Desktop\workplace\toonflow\Toonflow-app\data\providers`。

| 显示名称 | 文件 / 供应商 ID | 版本 | 模型数量 | 本次加载核对 |
| --- | --- | --- | --- | --- |
| 本地 MiniMax H3 · RTX3060 五模式 | `h3Local3060.ts` / `h3Local3060` | 2.0.0 | 5 | 原版 3000 返回 loadError 为空，模型列表含 5 项 |
| 本地 Qwen-Image-2.1 · RTX3060 | `qwenImage21Local.ts` / `qwenImage21Local` | 1.0.0 | 2 | 原版 3000 返回 loadError 为空，模型列表含 2 项 |

| 源码 | SHA-256 |
| --- | --- |
| `h3Local3060.ts` | `199ff8d17b875c4bb54160e7175126b28c1213b1de0ff2051cf13d1676c66b17` |
| `qwenImage21Local.ts` | `cadefd6096b8eb27277e350f5c96f378325f9a46efcf3b5d6cb9d209e47a68a3` |

两个供应商连接本机 ComfyUI `http://127.0.0.1:8188`，无需远程媒体 API Key。其 API 图模板内嵌在供应商源码中，不在生成时读取 ComfyUI 侧栏中的工作流 JSON。迁移部署仍需将 ComfyUI 地址改为执行器可达的受控配置，不能直接假定 Ubuntu 上的 localhost 就是当前 Windows 机器。

## 2. 模型标识与声明能力

| providerId | modelId | 用途 | 声明模式 |
| --- | --- | --- | --- |
| `h3Local3060` | `h3T2v` | 文生视频 | `text` |
| `h3Local3060` | `h3I2v` | 首帧图生视频 | `singleImage` |
| `h3Local3060` | `h3Fl2v` | 首尾帧生视频 | `startEndRequired` |
| `h3Local3060` | `h3L2v` | 尾帧生视频 | `startFrameOptional`，沿用供应商原声明 |
| `h3Local3060` | `h3R2v` | 参考素材生视频 | `imageReference:9`、`videoReference:3`、`audioReference:3` |
| `qwenImage21Local` | `qwenImage21Text` | 文生图（TEXT_TO_IMAGE） | `text` |
| `qwenImage21Local` | `qwenImage21Edit` | 单图指令编辑（IMAGE_TO_IMAGE） | `singleImage` |

H3 声明 5/8/10/15 秒、352p/480p/544p，包含视频音轨；声明规格不等于每个组合已实测。Qwen 声明 `1K`、`0.5MP`、`2K` 和 8 种画幅；默认 1K，2K 约为 4MP，供应商说明其在 3060 上尚未实测。不支持蒙版，一次生成一张 PNG。Qwen 模板为 `textToImage` 和 `imageToImage`；实际尺寸会按像素预算和画幅做 32 对齐。

## 3. 验证状态

- **H3**：现用文件与之前冻结的 2.0.0 快照逐字节相同，既有文生、首帧、首尾帧和 Web 播放结果仍可对应此源码，见[M0 收尾 §3](../archive/m0/M0_CLOSEOUT.md#3-新-h3-实际出片)。尾帧、参考、多时长/规格等未全面测试；本次没有再次生成。
- **Qwen**：`qwenImage21Local` 1.0.0 的两个模型已各由真实 Web 发起生成并成功落盘，1K、1:1、1024×1024；文生图 56.56 秒，单图编辑 100.88 秒，画布预览与刷新恢复通过，页面异常为空。见[本版本独立证据](evidence/qwen-final-closeout.json)，没有沿用旧供应商成功记录。
- M0 现用供应商补测完成。端到端取消、其他规格和新平台权限/五名额仍按 A08、M3/M4 验收；首次 2K 尝试由测试主动中止，不能算作 2K 成功或 Web 取消链路通过。原版节点保留 2K 的行为见 M0-I15。

## 4. 已停用供应商与旧预设

`localMiniMaxH3.ts`（`localMiniMaxH3/minimax-h3-local`）和 `localQwenImage21.ts`（`localQwenImage21/qwen-image-2.1`）已不在当前供应商目录及列表中。`docs/validation/fixtures/` 下保留的同名源码、0.1.1/0.1.2 快照、PNG/MP4 和 ComfyUI 图均是历史证据，不应重新安装以覆盖用户的新供应商。

旧 `localMiniMaxH3Node`、`localQwenImage21Node` 预设源码仍默认选择旧供应商，本次未改源码、重建插件或修改既有画布。新建时使用通用“视频生成”或“图片生成”节点，选择本页对应模型；已有节点若仍保存旧 providerId/modelId，须重新选择模型后再生成。
