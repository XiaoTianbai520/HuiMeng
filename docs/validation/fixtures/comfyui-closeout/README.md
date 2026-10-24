# 本轮实际执行的 ComfyUI 图

> 供应商已替换：这里 qwenText/qwenEdit 是旧 localQwenImage21 的实测图，不是 qwenImage21Local 1.0.0 的验证记录。当前 H3 源码仍与当时快照一致；当前型号和验证边界见[供应商清单](../../LOCAL_PROVIDERS.md)。

`*-api.json` 从本轮成功任务的 ComfyUI history 提取，包含实际种子、节点、模型文件名及参数，是复现依据。`*-gui.json` 为自动布局的查阅副本；动态控件的 GUI 往返执行未另测，不用于宣称图形界面重放已经验证。

| 图 | 场景 |
| --- | --- |
| h3T2v | 文生视频 |
| h3I2v | 首帧图生视频 |
| h3Fl2v | 首尾帧生视频 |
| qwenText | 文生图 |
| qwenEdit | 修正后的单图编辑 |

`inputs/` 保存这些图实际引用的合成图片，来源和哈希见 [input-index.json](input-index.json)。重放前上传所需图片；若 ComfyUI 改了文件名，更新 LoadImage 的 image 字段。模型权重和自定义节点来自现有本机安装，不包含在本目录。

ComfyUI 工作流侧栏中另有 `2026-10-22-M0-<图名>.json` 查阅副本。完整结果及限制见[M0 报告](../../../archive/m0/M0_CLOSEOUT.md)。
