# 原版 Toonflow 画布节点恢复记录

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> 2026-10-20。本次处理的是用户本机原版 Toonflow 开发环境，不是绘梦 1.0 实现。

## 原因

Toonflow 的 `3000` Server、`5173` Web 和 ComfyUI `8188` 均在运行，但 `GET /api/nodes/get` 返回 HTTP 200、`data: []`。原版克隆目录既没有 `build/nodes`，也没有 `data/nodes`；因此 Web 画布没有可加载的节点插件，双击菜单无法创建节点。这个问题与 QwenImage、MiniMax H3 媒体供应商分开：供应商放在 `data/providers`，画布节点插件放在 `data/nodes`。

## 处理

将固定提交运行副本中构建好的八个 `.umd.js` 内置节点插件复制到原版克隆的 `data/nodes`。复制前逐文件比较原版克隆与固定提交运行副本的 `packages/nodes`、`packages/nodeScaffold`：分别为 84、17 个文件，内容差异均为 0；复制后逐文件 SHA-256 一致。没有修改用户现有项目、画布或供应商配置。

安装的节点：`audioGenerationNode`、`audioNode`、`director3dNode`、`imageGenerationNode`、`imageNode`、`textNode`、`videoGenerationNode`、`videoNode`。

## 验证

- 原版 `GET /api/nodes/get` 现在返回 8 个节点，全部 `enabled=true` 且 `loadError` 为空。
- `textNode`、`imageGenerationNode`、`videoGenerationNode` 的 `/api/nodes/files` 均返回 HTTP 200 和 JavaScript。
- 用无头浏览器打开现有项目的画布，`/api/nodes/get` 和八个节点脚本均返回 200；双击画布后菜单出现“文本”“图片生成”“视频生成”，无页面异常。
- 为避免改动用户项目，本次只验证菜单和脚本加载，没有实际向现有画布新增节点。此前 M0 隔离项目已验证文本节点创建与自动保存，见[M0 报告](M0_REPORT.md)。

已经打开的 Toonflow 页面需要刷新，才能重新请求节点清单。以后重新克隆或清空 `data/nodes` 后，应先构建内置节点并确保它们安装到运行数据目录；仅启动 `dev:web`/`dev:server` 不会凭空生成缺失的节点包。
