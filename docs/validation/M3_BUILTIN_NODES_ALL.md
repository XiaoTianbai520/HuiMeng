# M3-05 内置节点全量入库与画布渲染

2026-10-26。把上游 8 个内置节点全部迁入并验证「在画布内放实例并渲染」，关闭 M3-05 的最后一项。

## 1. 范围

| 节点 | 包名 | 显示名 | 产物字节 | 画布内渲染 |
| --- | --- | --- | --- | --- |
| textNode | `@toonflow/node-text` | 文本 | 413564 | 通过（首批） |
| imageNode | `@toonflow/node-image` | 图片 | 520996 | 通过（首批） |
| imageGenerationNode | `@toonflow/node-image-generation` | 图片生成 | 604478 | 通过 |
| videoNode | `@toonflow/node-video` | 视频 | 267905 | 通过 |
| videoGenerationNode | `@toonflow/node-video-generation` | 视频生成 | 472978 | 通过 |
| audioNode | `@toonflow/node-audio` | 音频 | 263531 | 通过 |
| audioGenerationNode | `@toonflow/node-audio-generation` | 音频生成 | 454663 | 通过 |
| director3dNode | `@toonflow/node-director3d` | 3D导演台 | 1703791 | 通过（含 WebGL 网格） |

来源仍是固定提交 `d2e94ac`，用 `git archive` 导出；路径映射与摘要见[引入记录](../upstream/NODE_PACKAGES_INTAKE.md)。

## 2. 构建与登记

```bash
cd frontend && bun install && bun run build:nodes   # 8 个产物写入后端 classpath
cd frontend && bun run check:nodes                  # 9 个包（含 nodeShared）类型检查
cd backend  && ./mvnw.cmd -s .mvn/settings.xml -B -ntp -Dtest=BuiltinNodeTests test
```

- `build:nodes` 连续两次产物摘要一致（无时间戳），`textNode`/`imageNode` 与上一提交逐字节相同，说明新增 6 个包没有改变既有产物。
- `BuiltinNodeTests` 7 项通过：8 个 `builtin` 登记、目录顺序、摘要 ETag、管理员停用/配置跨重启保留、坏包跳过、卸载 403、下线清理。
- 后端登记不写死数量，测试按 `BUILTIN_NODES` 列表断言，新增节点只需扩这一处。

## 3. 画布内渲染（Linux 后端 + 本机浏览器）

Windows 上 `workspaces/files/*` 恒 503（JDK 未实现 `SecureDirectoryStream`），所以后端跑在 Linux，前端由本机 Vite 代理过去。

```bash
HM_NODE_LABEL=3D导演台 HM_EVIDENCE=m3-m09-canvas-director3d.json \
HM_EVIDENCE_SHOT=m3-m09-canvas-director3d.png \
HM_NODE_ORIGIN=http://localhost:5187 \
PLAYWRIGHT_CORE_PATH=... PLAYWRIGHT_CHROMIUM_PATH=... \
node docs/validation/scripts/m3M09CanvasRender.mjs
```

每个节点 10 项断言：新增画布写盘成功（无 503）、VueFlow 挂载、节点实例放入、卡片 DOM 非空、卡片内容可见、画布文件回读且节点类型为 `remote-<包名>`。

| 节点 | 结果 | 卡片可见内容（节选） |
| --- | --- | --- |
| 图片生成 | 10/10 | 添加到素材库、全景图、局部重绘、打光、多角度、宫格切分、历史记录、替换图片 |
| 视频生成 | 10/10 | 生成入口与时长/分辨率联动控件 |
| 音频生成 | 10/10 | 音色/语速等生成参数 |
| 视频 | 10/10 | 播放器与处理动作 |
| 音频 | 10/10 | 波形与播放器 |
| 3D导演台 | 10/10 | 透视网格场景（WebGL 渲染成功） |

证据：`docs/validation/evidence/m3-m09-canvas-{imagegen,videogen,audiogen,video,audio,director3d}.json` 与同名 `.png`；首批两个节点的证据仍是 `m3-m09-canvas-render*.json/.png`。

## 4. 复跑中发现并修掉的问题

1. **渲染脚本写死两个节点**：断言只认 `remote-textNode`/`remote-imageNode`，新增节点会被判失败；另有一处等待「选择画布」，夹具预置画布时该文案不存在。已改为按标签映射断言、并兼容「已有画布」的入口状态。
2. **`imageNode` 缺少导出表**：图片生成节点引用 `@toonflow/node-image/imageEditor` 等 7 个入口，原包只导出内部实现，构建直接失败。已按上游 `exports` 补齐。
3. **`audioNode`/`videoNode` 需要被同级节点引用**：`audioGenerationNode`/`videoGenerationNode` 引用 `@toonflow/node-audio/audioActions`、`@toonflow/node-video/videoActions`，已补 `exports`。

## 5. 边界

- 节点里的生成链路（`/api/ai/*`、媒体后端）仍不存在，点生成会报能力缺失；本批口径仍是「加载并渲染可用」。
- `director3dNode` 的产物 1.7 MB，是八个节点里最大的；8 个产物合计约 4.6 MB，随 JAR 分发。
- 画布内渲染依赖 Linux 后端，Windows 单机仍只能验证到「注册进窗口」。
