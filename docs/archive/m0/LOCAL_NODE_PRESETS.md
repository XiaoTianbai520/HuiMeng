# 原版 Toonflow 本地供应商节点预设

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> 2026-10-20。对象是用户克隆的原版 Toonflow，本记录不代表绘梦 1.0 正式前后端迁移已完成。

> **当前状态（2026-10-22）**：旧 localMiniMaxH3、localQwenImage21 均已删除。这里两个旧预设的源码仍默认选择旧供应商，仅作为历史资料；本次未重建插件或修改用户画布。现用为 h3Local3060 2.0.0 与 qwenImage21Local 1.0.0，见[当前清单](../../validation/LOCAL_PROVIDERS.md)。通用生成节点可选择现用模型；已有节点若仍存旧模型需重新选择。以下为 2026-10-20 历史验证记录。

## 实现

- 源码与构建脚本保存在[前端验证节点](../../../frontend/local-nodes/README.md)，针对原版固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`。
- 新增 `localQwenImage21Node` 和 `localMiniMaxH3Node` 两个画布节点入口。它们分别复用原版图片、视频生成节点，创建时将 `data.model` 预设为 `["localQwenImage21","qwen-image-2.1"]` 和 `["localMiniMaxH3","minimax-h3-local"]`。原版的模型下拉框仍可手动切换。
- 安装到原版 `data/nodes/` 的是两个独立 UMD 文件；原有八个节点文件未覆盖。构建产物也位于原版 `build/nodes/`。构建时临时借用的原版节点源码文件已清理。

| 新节点 | 安装文件 SHA-256 |
| --- | --- |
| QwenImage 2.1（本地预设） | `1cf8bc215511e83e84a1663ef1357d7af4d30785a2ab3c16097c565809ef815f` |
| MiniMax H3（本地预设） | `94ae95778d22d4b97163f35ded9b9d393006a2123ec0d7ec6fb2529c890ef378` |

## 验证

- 原版服务 `GET /api/nodes/get` 返回 10 个启用节点，`loadError` 均为空；两个新增节点的 `/api/nodes/files` 均返回 HTTP 200。
- 原版 `GET /api/ai/media/models` 包含上述两组供应商与模型 ID。
- 在隔离测试项目 `.m0-work/node-presets-ui` 的画布中，新增两节点均成功渲染，页面没有 JavaScript 异常。Qwen 节点显示“Qwen-Image-2.1 本地文生图”和 `0.3MP`；H3 节点显示“MiniMax H3 本地视频”和 `480p · 5秒`。保存的画布 JSON 中两个 `type` 与 `data.model` 都正确。测试没有触发新一轮出图或出片，之前的实际生成记录见[Qwen 验证](QWEN_IMAGE_21_PROVIDER.md)和[H3 验证](MINIMAX_H3_PROVIDER.md)。
- 用户原项目画布只用于只读菜单检查，没有在其中创建节点。隔离浏览器拦截了设置保存，未改动用户项目列表或服务端设置。

## 历史使用（不作为新供应商默认配置）

保持原版 Toonflow Web 与服务启动，刷新网页。打开画布，双击空白处，在“添加节点”选择“QwenImage 2.1（本地预设）”或“MiniMax H3（本地预设）”。生成前还需要本机 ComfyUI 的 `127.0.0.1:8188` 正常运行。Qwen 供应商仅支持文生图；H3 真实完成的是文生视频，首帧/首尾帧模式尚未完成真实出片验证。
