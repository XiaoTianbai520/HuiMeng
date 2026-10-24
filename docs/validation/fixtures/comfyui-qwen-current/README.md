# 现用 Qwen 1.0.0 实测工作流

供应商 `qwenImage21Local.ts` 的 `qwenImage21Text` 与 `qwenImage21Edit`，2026-10-22 完成实际生成，见[M0 最终收尾](../../M0_FINAL.md)。

`text-api.json`、`edit-api.json` 与成功历史中的提交图一致，保留种子、25 步、CFG 1、Euler/simple、1K/1:1。编辑图的 `LoadImage` 引用 `inputs/` 下同名输入；重放时将其放入 ComfyUI input，或正常上传后更新这一文件名。两次输出均为 1024×1024 PNG。

`*-gui.json` 是查阅副本，节点布局无重叠。动态控件 GUI 往返执行未实测，重放以 API 图为准，不用旧 localQwenImage21 工作流替代本目录。
