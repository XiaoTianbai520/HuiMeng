# M0 本地 Qwen-Image-2.1 供应商验证

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> **历史验证记录**：本页针对已删除的 `localQwenImage21`，包含早期版本与后续 0.1.2 修复记录。现用为用户另行配置的 `qwenImage21Local.ts` 1.0.0（`qwenImage21Text` / `qwenImage21Edit`）；现用版本已独立完成文生图和单图编辑的 1K Web 生成、落盘及刷新预览，见[M0 最终收尾](../../validation/M0_FINAL.md)。本页旧测试不作为新版本的通过证据。

> 2026-10-18；仅用于 Toonflow 固定提交的图片生成基线。此适配器未加入绘梦 1.0 正式运行服务。

## 适配内容

- 来源：本机 ComfyUI 的 `user/default/api_workflows/Qwen-Image-2.1生图.json`，原始 API 图已复制为[夹具](../../validation/fixtures/qwen-image-2.1-api-original.json)。
- Toonflow 2.0 自定义媒体供应商源码：[localQwenImage21.ts](../../validation/fixtures/localQwenImage21.ts)。使用原版 `/api/providers/media/validate` 与 `/api/providers/media/add` 安装在临时测试数据中。
- 供应商 ID `localQwenImage21`，模型 ID `qwen-image-2.1`，仅声明**文生图**。适配器固定连接本机 `http://127.0.0.1:8188`，把提示词、比例、`0.3MP`/`1MP` 转换为 ComfyUI 图参数，读取 `/history/{promptId}` 和 `/view`，以 PNG 字节交还 Toonflow。Toonflow 原生成链路负责保存到项目 `assets/`。
- 模型文件按本机工作流使用：`qwen_image_2.1_int8_convrot.safetensors`、`qwen3vl_8b_w4a8.safetensors`、`qwen_image_2.1_vae_bf16.safetensors`。所需节点已通过本机 ComfyUI 0.37.0 的 `/object_info` 确认。

## 运行证据

| 层级 | 结果 |
| --- | --- |
| ComfyUI 直接运行 | 以原 API 图、0.3MP、6 步单独出图成功，约 18.14 秒；[输出](../../validation/evidence/Huimeng-Qwen21-smoke_00001_.png) |
| Toonflow 供应商检查 | `validate` 200、`add` 200；`/api/ai/media/models` 可列出新增模型 |
| Toonflow 生成接口 | `POST /api/ai/media/generate`，0.3MP、1:1、原图 25 步；HTTP 200 且正文 `code:200`，约 10.59 秒；返回项目相对路径 `assets/m0-qwen21/image…png` |
| 落盘与图片检查 | 原版服务保存 PNG 614,816 字节，SHA-256 `a5ec5af0869c6d77c99d40778ebc5c3b83f6fdff92fe01f4ca8d4c7ee5907eeb`；[原始接口记录](../../validation/evidence/upstream-qwen21-provider-api.json)、[生成结果](../../validation/evidence/upstream-qwen21-toonflow.png) |
| 原版 Web 页面 | 图片生成节点的模型下拉列表可选该供应商，选择后显示 `0.3MP`；页面输入提示词并点击“生成图片”，网络响应 HTTP 200 / `code:200`，图片在画布中显示，页面异常为空；[模型选择](../../validation/evidence/upstream-qwen21-model-selected.png)、[生成后画布](../../validation/evidence/upstream-qwen21-ui-generated.png)、[画布输出 PNG](../../validation/evidence/upstream-qwen21-ui-output.png) |

约 10.59 秒只是一轮已热启动模型的本机测试记录，不能作为性能承诺。首次加载、并发和 1MP 情况尚未测量。

## 历史使用与边界（现用安装以当前清单为准）

运行 ComfyUI 后，在固定提交 Toonflow 测试副本中通过“添加自定义媒体供应商”导入[适配器源码](../../validation/fixtures/localQwenImage21.ts)，在图片生成节点选择 `Qwen-Image-2.1 本地文生图`。该文件没有公司供应商 Key；供应商端口只适用于与 Toonflow 同机的测试环境。

适配器支持文生图与单图图生图（图片编辑）；图生图时参考图经本机 ComfyUI `/upload/image` 上传后由 `LoadImage` 接入 `TextEncodeQwenImage21`（参考潜变量注入条件，按参考图尺寸出图）。暂不支持蒙版编辑、视频或音频；取消时 Toonflow 停止等待，但已提交的 ComfyUI 任务可能继续执行。正式 1.0 需要由 Spring 统一鉴权、任务归属、并发名额、取消策略和受控 ComfyUI 地址。后续已分别完成 [H3 文生视频](MINIMAX_H3_PROVIDER.md)和 [DeepSeek 文本与 Agent 基本会话](DEEPSEEK_BASELINE.md)验证；剩余模式与取消等行为见[问题清单](../../validation/ISSUES.md)。
