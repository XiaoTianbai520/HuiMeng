# 第三方工具执行：阶段一现状与工作区沙箱设计

> 2026-10-27。对应 M3-08 的工具运行期能力。阶段一（内置 zod 模块）已实现并验证；阶段二（工作区沙箱）与
> `worker_threads` 仍为设计，未实现，不能当作已交付能力。

## 1. 当前执行模型（已实现）

| 项 | 取值 |
| --- | --- |
| 引擎 | JVM 内 GraalJS 24.1.2（`js-community`），不引入 Node/Bun sidecar |
| 上下文 | 每次调用新建，调用结束销毁；不跨调用共享 |
| 宿主访问 | `HostAccess.NONE`、禁止类查找、禁止创建线程、禁止原生访问、禁止环境变量 |
| IO | `IOAccess.NONE`：没有文件、网络、进程 |
| 资源上限 | 语句数 2000 万；墙钟 20 秒（超时强制 `close(true)`）；结果 10 万字符；错误信息 300 字符 |
| 模块 | 只放行内置虚拟模块 `toonflow:tool-zod`；`fs`/`path`/`worker_threads` 等在加载期拒绝 |
| 未接通 | 依赖宿主文件桥（`toonflowToolHost`）的工具在登记期说明原因 |

拒绝与失败都以可诊断错误码返回：`TOOL_RUNTIME_IMPORT_UNSUPPORTED`、`TOOL_RUNTIME_HOST_BRIDGE_UNSUPPORTED`、
`TOOL_TIMEOUT`、`TOOL_EXECUTION_FAILED`；`loadError` 按「插件 + 摘要」缓存，管理端能看到「已安装为何不可用」。

## 2. 阶段一：内置 zod 模块（已实现）

真实市场的 4 个工具全部以 `import {z} from "toonflow:tool-zod"` 起步，其中纯计算类只需要 zod。做法：

1. 后端随包分发 `capabilities/runtime/tool-zod.mjs`：zod **4.6.7**（MIT，许可原文同目录 `LICENSE-zod.txt`）的
   单文件 ESM 产物（约 391 KiB），同时导出具名成员、`z` 与 `default`。
2. 工具源码进入执行器前，把 `toonflow:tool-zod` 的各种 import 改写成从 `globalThis.__huimengToolZod` 取值的顶层常量：
   - `import { z } from "..."` → `const { z } = globalThis.__huimengToolZod;`
   - `import { z as e } from "..."` → 解构里改写成 `z: e`
   - `import * as z from "..."` / `import z from "..."` / `import "..."` / `require("...")` 同样支持
3. 需要时先把 zod 产物求值成命名空间挂到全局，再求值工具模块。
4. 改写后仍存在其他 `import`/`require` 就拒绝；`toonflowToolHost` 引用单独拒绝。

这样做的取舍：不引入通用模块解析器，也不放开文件系统；只把「一个已知库」接进来。代价是依赖新虚拟模块时要显式再实现一次，而不是自动 npm 解析。

## 3. 阶段二：工作区沙箱（设计，未实现）

目标：让第三方工具在**授权目录**内受控读写，而不是打开 `IOAccess.ALL`。默认零文件权限。

### 3.1 路径模型

- 每个工具调用绑定一个项目；根目录由服务端按项目 ID 解析，工具只能看到虚拟路径（如 `/canvas/main.json`）。
- 拒绝绝对路径、盘符、`..`、反斜线、ADS、符号链接，以及任何解析后落在项目根之外的目标；沿用现有
  `SecureDirectoryStream`/JNA 逐层无链接打开的语义，解析与使用是同一个句柄，避免检查后被替换。
- 跨项目、`.agent/sessions`、`.runtime`、密钥与部署目录一律不可见；媒体只能通过媒体 ID 访问，不给物理路径。

### 3.2 暴露给工具的接口（建议）

| 方法 | 约束 |
| --- | --- |
| `list(path)` | 只返回名字、类型、大小、修改时间；单次最多 1000 项 |
| `read(path, maxBytes)` | 默认上限 1 MiB，硬上限 8 MiB；按 UTF-8 文本返回，二进制返回引用 ID |
| `write(path, content, revision)` | 必须带读取时的修订号，冲突返回 `FILE_CONFLICT`；单次 1 MiB；原子替换 |
| `mkdir/rename/remove` | 仅项目根内；`remove` 移到项目回收站而不是物理删除 |
| `stat(path)` | 元数据，用于幂等判断 |

配额建议：单项目 2 GiB 与 20 万个文件；单次调用写入总量 16 MiB；每分钟每个项目 200 次文件操作。超限返回明确错误码，不静默截断。

### 3.3 权限声明与批准

- 工具包在 `toonflowTool` 包头声明 `permissions.fs`：`read:/`、`write:/output` 这类「动作 + 虚拟路径前缀」。
- 安装时解析并写入插件登记；默认不授予任何文件权限，管理员在安装/配置页显式勾选，变更写审计。
- 执行时按「声明 ∩ 批准 ∩ 项目根」三者取交集；任何一方缺失都拒绝。
- 未声明的工具即使调用文件 API 也只会得到 `TOOL_PERMISSION_DENIED`。

### 3.4 审计与可观测

每次文件操作记录：工具名、插件摘要、项目、虚拟路径（不进日志的绝对路径）、字节数、结果、耗时、requestId。
拒绝与配额命中单独计数，便于判断是否需要调整上限。

### 3.5 与阶段一的关系

阶段一不改变隔离边界，只是补齐模块。阶段二上线前，依赖文件读写的市场工具（如 `deleteFile`、`videoComposer`、
`novelToTxt`）继续不可用，且必须在管理端显示原因而不是静默失败。

## 4. `worker_threads` 评估（结论：不开放）

`novelToTxt` 需要 `worker_threads` 做解析提速。直接放开的问题不是「能不能跑」，而是失去可预测的资源边界：

1. **线程数不可控**：GraalJS 的 `allowCreateThread(false)` 是当前唯一硬约束；放开后工具可以按输入规模创建任意线程，
   在同一个 JVM 里与 Web 请求、生图任务抢 CPU，容器内存上限会被瞬间打满。
2. **内存不可回收**：每个 worker 都有独立堆/上下文，JVM 侧只能靠容器 OOM 兜底；无法按调用精确计费。
3. **超时不可中断**：现有 20 秒墙钟靠关闭上下文实现，worker 内的长任务不一定响应中断，容易出现「调用已超时、线程还在跑」。
4. **共享内存与逃逸**：`SharedArrayBuffer` 与 `postMessage` 扩大攻击面；一旦某个 worker 拿到宿主对象引用，隔离假设就失效。
5. **收益有限**：市场里只有这一个工具依赖它，且主要成本在文本解析，不在并发。

若未来确有必须的用例，按以下顺序评估，而不是直接开权限：

- **优先**：要求工具提供纯函数版本（分片由平台调度），在现有上下文里按片执行并合并结果；
- **其次**：放到独立 Bun sidecar，用 cgroup 限制 CPU/内存、禁网、只挂载授权目录，主进程通过受控 IPC 调用；
- **最后**：在 Graal 内实现固定大小（如 2）的 worker 池 + 每 worker 堆上限 + 硬超时强杀，并先做压测与逃逸测试。

重开条件：出现真实业务工具、给出可测的资源上限方案、通过逃逸与压力测试；在此之前 `worker_threads` 保持拒绝，
并在 `loadError` 中说明原因。

## 5. 落地顺序

1. 阶段一（已完成）：zod 模块 + 纯计算工具可执行 + 其余模块拒绝原因可读。
2. 阶段二：文件 API 与权限声明 → 路径/配额/审计测试 → 管理员批准界面 → 真实工具（`deleteFile`）端到端验证。
3. 阶段三（可选）：按第 4 节结论处理 `worker_threads`，或明确长期不支持。
4. 上述任一步都要先补拒绝用例，再开放能力；不能用「已安装」代替「已授权且可执行」。
