# M3-01 项目文件验收（A04/A05/A06/A17）

> 2026-10-26。范围：原格式读写与重开、双编辑者修订冲突、复制/重命名/删除/大文件分段读取、项目与文件列表不泄漏服务器路径。使用真实 Spring 进程（夹具 H2 + 随机工作区）与合成管理员账号；媒体上传因夹具未启用对象存储而跳过，沿用 M2-A01 的真实 MinIO 证据。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| A04 原格式读写 | 通过：新建目录与画布，快照回读内容逐字一致；默认编码 `utf-8`；`utf-8-bom` 写入后磁盘保留 BOM 并回显编码；`utf-16le` 写入按 UTF-16LE+BOM 落盘，回读解码一致 |
| A04 保存与重开 | 通过：带修订号覆盖后返回新 64 位修订号，重开得到更新后的内容 |
| A05 修订冲突 | 通过：用旧修订号再次提交返回 409 `REVISION_CONFLICT`，`details.currentRevision` 是当前值，本次内容未写入 |
| A06 复制/重命名/删除 | 通过：复制得到可读副本，重命名后列表出现新名，删除后列表不可见 |
| A06 大文件分段读取 | 通过：写入 8 MiB 后 `Range: bytes=0-1023` 返回 206、`Content-Range: bytes 0-1023/8388608`、前 1 KiB 摘要一致 |
| A06 媒体上传/下载 | 跳过（夹具 `huimeng.storage.enabled=false` 返回 503）；真实 MinIO 上传/预览/下载见[M2 验收](M2_ACCEPTANCE.md) |
| A17 路径不泄漏 | 通过：项目列表不含 `/data/`、`/tmp/`、`/repo/`、`C:\`、`workspace-root`、`absolutePath`、`directory`；项目字段只有 `id/name/status`；文件列表 `path` 恒等于 `name`（相对路径） |
| A03 跨项目（附带） | 通过：新建非成员账号后读取项目文件返回 403，项目列表为空 |

共 57 项检查通过、1 项跳过。

## 2. 实现要点

- **写入契约**：`PUT .../write?path=&expectedRevision=&exclusive=&encoding=`，正文是 UTF-8 文本，`encoding` 决定磁盘编码（`utf-8`/`utf-8-bom`/`utf-16le`/`utf-16be`）；非文本或编码不支持返回 415。
- **修订号**：`SecureFileWriter` 在原子替换时生成，快照与写入结果始终返回 64 位十六进制；冲突以 409 终止且不改动文件。
- **分段读取**：`GET .../read` 支持 `Range`，返回 206 与 `Content-Range`，避免大文件整块进内存。
- **列表收敛**：`list` 只返回 `name/path/type`，`path` 是项目内相对路径；项目列表只返回 `id/name/status`，不含工作区目录或服务器绝对路径。

## 3. 验证证据

| 层次 | 内容 | 证据 |
| --- | --- | --- |
| HTTP 验收 | 57 项：A04 原格式/重开、A05 冲突、A06 复制/重命名/删除/8 MiB 分段、A17 路径、A03 非成员 | [m3-m01-files.json](evidence/m3-m01-files.json) |
| 既有批次 | M3-W01 安全写入 77 项默认后端 + 46 项 Linux HTTP；M3-W02 目录/文件操作 39 项；M3-W03 原文件树/文档面板/画布修订链 | [M3 记录](M3_FILE_WRITE.md)、[工作区记录](M3_WORKSPACE.md)、[W03 页面](M3_W03_PAGES.md) |
| 媒体 | 真实 MinIO 上传、预览、下载与跨项目拒绝 | [M2 验收](M2_ACCEPTANCE.md) |

复跑命令：

```bash
M301_PORT=18086 M301_READY=backend/target/external-protocol.ready \
  M301_EVIDENCE=docs/validation/evidence/m3-m01-files.json \
  python docs/validation/scripts/m3M01Acceptance.py
```

夹具为 `ExternalProtocolHarness`（容器内 `127.0.0.1:18099`，隧道到 `127.0.0.1:18086`）。操作限速为每账号每分钟 60 次写，连续跑多轮需等到下一分钟窗口。

## 4. 未覆盖

- **媒体上传/下载**：本轮夹具关闭对象存储，未重跑；由 M2-A01 的真实 MinIO 证据承担。
- **导出物**：项目/插件导出下载未在本脚本覆盖，导出接口见[M3-08 记录](M3_PLUGIN_MARKET.md)。
- **并发写压力**：只验证两个写者的一次冲突，未做高并发或长时间压测。
- **画布前端交互**：A04/A05 的页面级操作由 M3-W03 浏览器记录覆盖，本脚本走 HTTP。
