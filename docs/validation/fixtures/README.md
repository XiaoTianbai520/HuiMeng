# M0 验收样例

- [sample-project](sample-project/) 为固定提交原版 Web 创建的样例：`画布1.json` 含一个文本节点，`M0-剧本.md` 经页面编辑，`assets/M0-sample.png` 经原版文件接口写入并读回。
- [M0-safe-tool-probe.txt](sample-project/M0-safe-tool-probe.txt) 仅含公开合成标记，专用于 DeepSeek Agent 的 `read` 工具测试；测试未将样例剧本发送给外部模型。
- [comfyui-connectivity.json](comfyui-connectivity.json) 是本地 ComfyUI 的 `EmptyImage → SaveImage` 连通工作流；不使用生成模型，不代表 Toonflow 媒体生成已通过。
- [qwen-image-2.1-api-original.json](qwen-image-2.1-api-original.json) 是本机已有的 Qwen-Image-2.1 API 工作流原始副本；[localQwenImage21.ts](localQwenImage21.ts) 为 Toonflow 固定提交用的文生图供应商适配器。结果见[验证记录](../../archive/m0/QWEN_IMAGE_21_PROVIDER.md)。
- [localMiniMaxH3.ts](localMiniMaxH3.ts) 为本机 MiniMax H3 视频工作流的 Toonflow 固定提交测试适配器，结果见[验证记录](../../archive/m0/MINIMAX_H3_PROVIDER.md)。
- [providers/](providers/) 保存 M0 取证的供应商历史快照；现用清单见[LOCAL_PROVIDERS](../LOCAL_PROVIDERS.md)。旧 localMiniMaxH3.ts/localQwenImage21.ts 不能据此重新安装；新 Qwen 的生成结论不能沿用旧夹具。
- [comfyui-closeout/](comfyui-closeout/) 保存新 H3 三模式的实际 API 图与 GUI 查阅副本，见[执行索引](../evidence/upstream-h3-executed-workflows.json)。
- [comfyui-qwen-current/](comfyui-qwen-current/) 保存现用 Qwen 1.0.0 两项成功生成的实际 API 图、GUI 查阅副本及编辑输入图。
- [qwen-current-project/](qwen-current-project/) 保存现用 Qwen 两个输出及参考连线的合成项目，见[最终收尾](../M0_FINAL.md)。
- 所有样例只供迁移前后格式与行为对比，不含供应商 Key 或 MCP 测试凭证。
