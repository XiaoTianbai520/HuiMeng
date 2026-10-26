# M3-05 能力目录与 FFmpeg 转码验收

> 2026-10-26。范围：成员可见的能力目录（节点/工具/工具界面/技能/转码状态）与服务端 FFmpeg 受控转码（transcode/concat/extractAudio、进度、取消、下载）。H3 视频与内置节点包随本任务后续批次。仅使用合成账号、夹具项目与容器内 ffmpeg；未连接真实模型或用户存储。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| 能力目录 | 通过：`nodes/get`、`tools/get`、`tools/renderers`、`agent/skills` 返回成员可读的诚实空目录，页面不再把缺项当成 403 失败 |
| 工具界面 | 通过：`tools/renderers` 返回空列表，工具卡片改用内置样式；真实浏览器已无「工具界面加载失败」与 `Request failed with status code 403/404` 提示 |
| 转码状态 | 通过：`capabilities/ffmpeg/status` 返回可执行性、版本、运行中任务数与 4 个固定预设 |
| 转码执行 | 通过：6 秒 640×360 样例 → mp4-720p，SSE 收到 `progress`（含百分比）与 `done`，产物 1,105,777 字节，`ftyp isom`，经媒体接口可下载 |
| 取消 | 通过：90 秒 720p → webm-720p 提交后 2 秒取消，流以 `error: 转码已取消` 结束，进程退出、临时目录清理 |
| 边界 | 通过：外部路径 `/etc/passwd` 与未知预设 `4k-ultra` 均在开流前 400，不启动 ffmpeg |

## 2. 实现要点

- **能力目录下沉为服务端真相**：内置工具只发布 `canvasOperations` 一项，`canManage` 固定 false；节点包与工具界面没有随镜像分发时返回空列表而不是 404，页面据此降级展示。
- **转码参数固定**：`transcode`/`concat`/`extractAudio` 只接受 `.huimeng-media/<uuid>.(mp4|mov|webm|mkv|mp3|wav|m4a|png|jpg|jpeg)` 与 4 个预设（mp4-720p、mp4-1080p、webm-720p、mp3-192k）。命令由服务端拼装，浏览器不能给出命令、滤镜或任意参数；素材先落临时目录，完成后上传项目媒体并删除临时目录。
- **进度与取消**：`-progress pipe:1` 解析 `out_time_ms`，用 ffprobe 取时长得百分比（缺失时只报时间）；进度事件节流 500 ms。取消按 `requestId` 定向结束该进程，流以错误事件收尾而不是让客户端读到半截分块。
- **资源限制**：单素材与总素材 2 GiB、最多 8 个素材、`-threads 2`、15 分钟超时（超时后强制终止）。
- **架构约束**：SSE 帧序列化下沉到服务层，控制器只声明 `ResponseEntity<StreamingResponseBody>`，满足「控制器只依赖服务接口」的可执行约束。

## 3. 验证证据

| 层次 | 内容 | 证据 |
| --- | --- | --- |
| 后端 | 108 项默认测试（新增 4 项 FFmpeg 参数/预设/边界） | Linux `mvnw verify` + Spotless + JAR |
| 前端 | 26 项测试 102 断言、类型、边界与生产构建 | `bun run check` / `bun run build` |
| HTTP + 真实转码 | 29 项：目录为空、状态可用、上传、SSE 进度/完成、下载校验、取消、边界拒绝 | [m3-m05-ffmpeg.json](evidence/m3-m05-ffmpeg.json) |
| 浏览器回归 | 11 项（含新增两项：无「工具界面加载失败」、无 403/404 提示），工具卡片仍正常渲染 | [m3-m04-browser.json](evidence/m3-m04-browser.json) |

真实转码在 `MediaBrowserAcceptanceHarness` 容器内进行（内存对象存储 + 容器 `apt` 安装 ffmpeg 6.1.1），样例视频由 `ffmpeg -f lavfi -i testsrc2` 生成，脚本 `m3M05FfmpegCheck.py`。

## 4. 未覆盖

- **H3 五种视频模式**：仍需把 `h3Local3060` 的图模板与时长/分辨率参数迁入服务端，属本任务下一批次（M3-I05 保持未完成）。
- **内置节点包分发**：`nodes/get` 目前返回空列表；把通用节点包随镜像分发并让画布实际加载仍需打包与许可核对（M3-I08）。
- **技能包**：`agent/skills` 返回空列表，技能安装属于插件市场（M3-08）。
- **prepare 语义**：仅返回可用性，不复刻上游浏览器侧 ffmpeg 编译/下载流程（服务端已有二进制）。

## 5. 环境清理

两个夹具容器、合成文本供应商、静态代理与回环转发均已停止；容器内临时视频与转码目录已删除，未写入用户存储。
