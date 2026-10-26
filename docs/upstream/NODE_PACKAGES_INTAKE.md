# 内置节点包引入记录（8 个内置节点）

2026-10-26。来源是参考克隆的固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`，通过 `git archive` 导出；迁入前只读核实 HEAD 与工作区，未复制本地修改、`.git`、`data/` 或 `node_modules/`。参考克隆里 `apps/server/src/router.ts`、`apps/web/src/types/components.d.ts` 的未提交改动与本轮无关，没有被带进来。

2026-10-26 第二批补齐其余 6 个节点，现在 classpath 下共有 **8 个内置节点**：文本、图片、图片生成、视频、视频生成、音频、音频生成、3D导演台。范围仍是「全新数据库启动后画布能看到、加载并渲染这些节点」；节点内的生成链路（`/api/ai/*` 与媒体后端）不在本轮范围。

## 1. 实际路径

| 来源（固定提交内） | 绘梦位置 / 处理 |
| --- | --- |
| `packages/nodeShared/src/groupNodeModels.ts`、`showNodeError.ts`、`useNodeReferences.ts` | `frontend/packages/nodeShared/src/`，包名 `@toonflow/node-shared` |
| `packages/nodeShared/src/components/promptInput.vue`、`referenceItem.vue`、`referenceCard.vue`、`nodeError.vue` | `frontend/packages/nodeShared/src/components/`；`referenceItem` 内联 `referenceCard`，`showNodeError` 内联 `nodeError`，都是实际依赖 |
| `packages/nodeShared/src/useNodeGeneration.ts`、`components/mediaPrice.vue`、`components/mediaHistory.vue` | `frontend/packages/nodeShared/src/`；第二批节点的实际依赖，追加到同一包的导出表 |
| `packages/nodes/textNode/**`（8 个文件） | `frontend/packages/nodes/textNode/`，包名 `@toonflow/node-text` |
| `packages/nodes/imageNode/**`（22 个文件） | `frontend/packages/nodes/imageNode/`，包名 `@toonflow/node-image`；导出表补齐 `imageEditor`/`imageGridSplit`/`imageInpaint`/`imageLighting`/`imageMultiAngle`/`imagePanorama`/`imageInpaintPrompt`，供图片生成节点引用 |
| `packages/nodes/imageGenerationNode/**`（2 个源码文件） | `frontend/packages/nodes/imageGenerationNode/`，包名 `@toonflow/node-image-generation` |
| `packages/nodes/videoNode/**`（4 个文件） | `frontend/packages/nodes/videoNode/`，包名 `@toonflow/node-video`；导出 `videoActions`/`videoPlayer` |
| `packages/nodes/videoGenerationNode/**`（2 个文件） | `frontend/packages/nodes/videoGenerationNode/`，包名 `@toonflow/node-video-generation` |
| `packages/nodes/audioNode/**`（6 个文件） | `frontend/packages/nodes/audioNode/`，包名 `@toonflow/node-audio`；导出 `audioActions`/`audioPlayer` |
| `packages/nodes/audioGenerationNode/**`（3 个文件） | `frontend/packages/nodes/audioGenerationNode/`，包名 `@toonflow/node-audio-generation` |
| `packages/nodes/director3dNode/**`（13 个文件） | `frontend/packages/nodes/director3dNode/`，包名 `@toonflow/node-director3d`；依赖 `@tweenjs/tween.js` 25.0.0、`html2canvas-pro` 2.0.2、`threejson` 0.1.0-alpha.9、`vue-draggable-plus` 0.6.1、`three` 0.184.0 |
| 上游根 `LICENSE`（MIT, Copyright (c) 2026 HBAI-Ltd） | 沿用 `frontend/LICENSE.Toonflow`，与前端引入共用同一份上游 MIT 文本 |

[39 条来源映射](node-packages-intake.json)记录原路径、目标路径和**固定提交导出内容的 SHA-256**。其中 36 个源码文件的导出字节与迁入文件逐字节相同（仅按仓库 `.editorconfig` 把行尾从 CRLF 换成 LF）；3 个 `package.json` 经过适配，摘要故意不同，适配内容见第 3 节。

8 个节点全部迁入后，`packages/nodeShared` 的模块已全量引入。仍未迁入的是上游 `teamScaffold`、`teams`、`mcp`、`skills`、`startup`：这些与节点渲染无关，引入只会扩大需要维护和授权的范围。

## 2. 可复现构建

工作目录 `frontend/`，实测 Node 24.11.1、Bun 1.4.2：

```powershell
bun install --frozen-lockfile
bun run build:nodes     # 构建两个节点，产物收口到 backend/src/main/resources/capabilities/nodes/
bun run check           # 边界/格式 + vue-tsc + 三个节点包 vue-tsc + 26 项离线回归
```

`build:nodes`（`frontend/scripts/build-nodes.ts`）逐个执行节点包自己的 `vite build`（`@toonflow/nodes-scaffold` 的 `createNodeConfig`），随后把字节原样复制到后端随部署分发的资源目录，并打印版本、显示名、字节数与 SHA-256。二者共同约束：产物名固定 `<name>.umd.js`，`name` 必须匹配 `^[a-z][a-zA-Z0-9]*$`。脚手架里的 `outDir`（`frontend/build/nodes`）与 `dataDir`（`frontend/data/nodes`）是中间目录，已加入 `.gitignore`，不入库；只有 `backend/src/main/resources/capabilities/nodes/` 下的最终制品入 Git。

| 产物 | 版本 | 显示名 | 字节 | SHA-256 |
| --- | --- | --- | --- | --- |
| `textNode.umd.js` | 2.0.0 | 文本 | 413564 | `1d28cac4b4d162f0c09fa84e7d66533b3c9ee1db39cf5c96eb61def236e827b5` |
| `imageNode.umd.js` | 2.0.0 | 图片 | 520996 | `e4be701b4bd5ac6afaef1c059e0f65eee5362509514e8b64ccdb836f593d93d3` |
| `imageGenerationNode.umd.js` | 2.0.0 | 图片生成 | 604478 | `609f74fcaf070f115e4f780a2a6e8f405d66d1a9df6c1c41cc95c7062a724aec` |
| `videoNode.umd.js` | 2.0.0 | 视频 | 267905 | `5eb3a3f60f2dec30dafff536abedceec6afdf40c7058a2e70c73821e547b4501` |
| `videoGenerationNode.umd.js` | 2.0.0 | 视频生成 | 472978 | `f12505fd2cf4772bb66f7301d22e7c5941b15df3a4008de24ada9f7c1f22986e` |
| `audioNode.umd.js` | 2.0.0 | 音频 | 263531 | `ddfceab52f74ff65d1617e0f2b50de86051385d7dde213296120975e0f657c4c` |
| `audioGenerationNode.umd.js` | 2.0.0 | 音频生成 | 454663 | `a38010ca07c9e6b0f5eb384ab72b6bfd45aeea49563334a275ab4568e49af1b0` |
| `director3dNode.umd.js` | 2.0.0 | 3D导演台 | 1703791 | `0ba587f81d1d87b3297cbc698cd8c2a5c0d1c801deb4f584c0a5443278c7f484` |

`textNode` 的两个数字与首批记录不同，是因为首批之后修了「只认 `EEXIST`」的错误码兼容（见第 4 节），产物变了但可复现性不变：连续两次构建摘要一致。

连续两次构建的摘要一致（产物不含时间戳），所以「重启后摘要不变 → 内置登记不写库」这一前提可复现。产物包头 `toonflowNode` 携带 `displayName`/`author`/`github`/`configRules`/`version`/`readme`：版本取节点包 `package.json`，其余取 `createNodeConfig` 入参，后端登记直接解析该包头，不再维护第二份元数据。

## 3. 行为适配与边界

1. **工作区装配**：`frontend/package.json` 的 `workspaces` 增加 `packages/nodes/*`；`nodeShared` 由既有 `packages/*` 覆盖。
2. **依赖改为工作区引用**：`@toonflow/nodes-scaffold`、`@toonflow/node-shared` 均为 `workspace:*`。`@toonflow/node-shared` 把 `@toonflow/nodes-scaffold` 从上游的 `devDependencies` 提升为 `dependencies`，因为它的源码确实 import 该包的 `values`/`workspaceFiles`/`nodeAi` 子路径。
3. **版本按已安装版本固定**，不沿用上游 `^` 范围：Vue 3.5.42、`@vue-flow/core` 1.48.2、element-plus 2.14.5、tiptap 3.31.3、three 0.184.0、marked 18.0.13、dompurify 3.4.16、vite 8.2.2、vue-tsc 3.3.11、TypeScript 6.0.3。新增的第三方依赖只有 `vue-draggable-plus` 0.6.1（`referenceItem` 的拖拽排序），已随 `bun.lock` 更新。
4. **`nodeShared` 的 `exports` 收窄**到已迁入的 5 个子路径；`@toonflow/node-image` 的 `exports` 字段整体移除：上游用它给 `imageGenerationNode` 等未迁入的包提供子路径，本仓库没有这类引用，保留会形成无人使用、无人验证的公开面。
5. **行尾统一为 LF**，只改行尾，不改代码。
6. **「文件已存在」错误码兼容（对上游的主动偏离）**：上游跑在 Bun 上，`mkdir`/独占写入撞已有文件时抛 `EEXIST`；本平台的 Spring 后端返回 `FILE_ALREADY_EXISTS`（`packages/contracts/platform.ts` 的 `ErrorCode` 里本来就列了这个码）。迁移进来的 `textNode/src/index.vue`（2 处）与 `nodeScaffold/src/workspaceFiles.ts`（1 处）原本只判 `EEXIST`，导致画布重挂载时节点二次创建 `assets/<nodeId>/content.md` 收到 409，卡片内弹出红色「目标已存在，请换一个名称」。已改为同时接受两种码；改动后重建产物（textNode 413564B、imageNode 520996B）。**这是唯一一处对上游逻辑的修改**，其余为依赖/导出面/行尾调整。

边界：节点只做到「加载可用」。文本节点挂载时会读写项目工作区文件，图片节点的生成/编辑入口调用 `/api/ai/*` 与媒体生成，后端仍未实现，点击生成会返回能力缺失而不是产物。画布需要工作区文件 API 才能创建，Windows 上该 API 返回 503；2026-10-26 已在 Linux 后端复跑通过「放实例并渲染」（`m3M09CanvasRender.mjs`，文本与图片两种节点各 10 项），见[M3 内置节点记录](../validation/M3_BUILTIN_NODES.md)第 4.1 节与[插件运行期交接](../handoff/PLUGIN_RUNTIME.md)。

## 4. 许可证

两个节点与 `nodeShared` 源码沿用上游 MIT（`frontend/LICENSE.Toonflow`）。新增的 `vue-draggable-plus` 0.6.1 为 MIT，只在节点产物里打包，不进入 Web 应用的 `dist/`；产物许可归档仍由 `bun run build` 的 `licenseReport` 按应用实际入包依赖生成，本轮没有改变应用依赖闭包。
