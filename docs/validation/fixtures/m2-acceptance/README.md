# M2 本机模型验收工作流

2026-10-25，复用已确认的 qwenImage21Local 1.0.0 当前文生图图；未替换模型、节点或默认能力。只改提示词、随机 seed 与输出前缀，保留 1024×1024、25 步、CFG 1、euler/simple。模型目录仍保留 2K 能力，本次仅测 1K。

- [API 图](qwen-text-api.json)：本次实际提交图，复制到 backend/target/m2-comfy-api.json 后才可运行显式测试工具。
- [GUI 图](qwen-text-gui.json)：相同参数，保留原布局；已保存到本机 ComfyUI Workflows 的 `HuiMeng-M2-acceptance.json`。
- 依赖 `qwen_image_2.1_int8_convrot.safetensors`、`qwen3vl_8b_w4a8.safetensors`、`qwen_image_vae.safetensors`，以及 TextEncodeQwenImage21、QwenImage21Cache 等当前节点。运行前重新查询 object_info 并确认队列空闲，不自动安装/下载依赖。
- [结果元数据](../../evidence/m2-comfy-result.json)与[已查看的 PNG](../../evidence/m2-comfy-output.png)。图片是合成静物，不含团队素材或远程 Key。

测试执行入口与明确限制见[推进记录](../../M2_REMAINING_PROGRESS.md)及[backend README](../../../../backend/README.md#m2-读取导出与模型验收)。这不是新平台公共生成接口的工作流配置或部署成果。
