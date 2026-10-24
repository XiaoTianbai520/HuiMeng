# 原版 Toonflow 本地模型节点预设

这两个节点用于 M0 本机验证，分别复用原版 `imageGenerationNode` 和 `videoGenerationNode` 的界面及生成逻辑。新建节点时预选本地模型；用户仍可在模型下拉框中切换，因此节点名称中的“预设”表示初始配置，不表示模型锁定。

2026-10-22 用户已删除旧 localMiniMaxH3 和 localQwenImage21 供应商，现用 h3Local3060.ts 2.0.0（5 模型）和 qwenImage21Local.ts 1.0.0（2 模型），见[当前供应商清单](../../docs/validation/LOCAL_PROVIDERS.md)。这里两个预设的源码和构建脚本仍保留旧默认模型，仅作 M0 历史资料；不能直接重建后当作新供应商预设。本次只同步文档，未改节点源码、安装插件或用户画布。新建使用通用“视频生成/图片生成”节点选择现用模型；既有节点若存有旧模型，重新选择后再生成。

源码针对固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`。从本目录运行 `./build.ps1 -ToonflowRoot <固定提交 Toonflow 目录>`，脚本临时借用原版图片/视频生成节点包及其依赖，构建到 `build/nodes/` 后清理临时源码；不会覆盖原版节点包。部署时只复制两个生成的 `.umd.js` 到 Toonflow 的 `data/nodes/`。供应商源码仍需分别安装在 `data/providers/`。

新节点只设置默认模型，不把供应商 API Key 或 ComfyUI 地址写入画布。现有本地供应商连接 `127.0.0.1:8188`，仅适用于同机验证。
