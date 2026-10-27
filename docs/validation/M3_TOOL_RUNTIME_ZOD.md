# 工具执行器：内置 zod 模块（阶段一）验证

> 2026-10-27。范围：让依赖 `toonflow:tool-zod` 的**纯计算**市场工具在服务端执行器里真正可用；文件与线程能力不开放。
> 设计见[工作区沙箱与 worker_threads 评估](../architecture/TOOL_RUNTIME_SANDBOX.md)。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| 内置 zod | 已接通：zod 4.6.7 单文件 ESM（391 KiB），具名/默认/命名空间/紧凑 import 全部支持 |
| 纯计算市场工具 | **可用**：`composeCinematicPrompt`（directorLens 制品，91 935 B）真实执行成功，图片版与视频版各一次 |
| 依赖宿主文件桥的工具 | 加载成功但服务端不能执行；`deleteFile` 只做到参数校验与错误提示 |
| `fs`/`worker_threads` | 继续在加载期拒绝，`loadError` 说明原因 |
| 安全边界 | 未放宽：无宿主访问、无 IO、无线程、无环境变量；语句/时间/结果上限不变 |

## 2. 实现要点

1. `backend/src/main/resources/capabilities/runtime/tool-zod.mjs`：由 `zod@4.6.7`（MIT，许可原文同目录）用
   `bun build --format=esm --minify` 打出单文件，导出 `z`、`default` 与全部具名成员。
2. `ToolRuntime` 在求值工具模块前把 `toonflow:tool-zod` 的 import 改写成 `globalThis.__huimengToolZod`：
   `{a as b}` → `{a: b}`，`* as ns`、默认导入、无绑定导入与 `require(...)` 都覆盖。
3. 改写后仍有其他外部模块就拒绝（`TOOL_RUNTIME_IMPORT_UNSUPPORTED`）；引用 `toonflowToolHost` 单独拒绝
   （`TOOL_RUNTIME_HOST_BRIDGE_UNSUPPORTED`）。
4. zod 只在工具真的引用它时求值，自包含工具不受影响。

## 3. 自动化测试

| 用例 | 覆盖 |
| --- | --- |
| `runsToolThatImportsBundledZod` | 具名导入；合法输入返回解析结果，非法输入按 `TOOL_EXECUTION_FAILED` 拒绝 |
| `runsMinifiedToolThatImportsBundledZod` | 真实制品的紧凑写法 `import{z as e}from"toonflow:tool-zod"` |
| `supportsDefaultAndNamespaceZodImports` | `import z from` 与 `import * as z from` |
| `refusesFileSystemAndWorkerThreadsModules` | `node:fs`、`worker_threads` 仍在加载期拒绝 |
| `explainsToolsThatNeedTheHostBridge` | 依赖 `toonflowToolHost` 的工具给出「宿主文件接口」原因 |
| `ToolRegistryTests` 3 项 | 提供方顺序、重名只留第一个、未知工具 `TOOL_UNKNOWN`、画布开关只影响画布工具 |

`ToolRuntimeTests` 8 项、`ToolRegistryTests` 3 项、`AgentServiceImplTests` 11 项全部通过。

## 4. 真实市场制品复验

用团队 TF-Router Key 拉取目录（4 个工具），下载两个纯/准纯计算制品，走文件安装路径登记后由执行器加载：

| 制品 | 字节 | 加载 | 工具 | 执行 |
| --- | --- | --- | --- | --- |
| `directorLens.tool.js`（`composeCinematicPrompt` 1.2.0） | 91 935 | 通过，`loadError=null` | `composeCinematicPrompt` | **成功**（图片版、视频版各一次） |
| `deleteTool.tool.js`（`deleteTool` 1.3.1） | 49 009 | 通过，`loadError=null` | `deleteFile` | 参数校验可用；无工作区文件桥，「文件不存在」 |

`composeCinematicPrompt` 视频版调用（`scene` + `mode=video`, `duration=10`）返回的片段：

```
【完整提示词 · EN · 视频版】wide shot, eye-level camera angle, the camera rises smoothly on a crane,
opening up the space, anamorphic widescreen lens, 2.39:1, golden hour backlight ...
【中文分镜】 ...
```

工具参数表由 zod 的 `toJSONSchema` 现算（21 个字段，含枚举与默认值），说明 `createTools` 在沙箱内正常求值——这正是阶段一要解决的问题。

仍未接通：`videoComposer`（`fs`、`fs/promises`、`os`、`path`）与 `novelToTxt`（`fs/promises`、`module`、`path`、
`timers/promises`、`util`、`worker_threads`）。它们属于阶段二/三，按设计文档处理，不在本轮放开。

## 5. 复现方式

```powershell
# 1) 下载真实制品到本地临时目录（凭据只在环境变量里，不落盘）
# 2) 执行夹具，显式 opt-in
cd backend
$env:HM_MARKET_TOOL_DIR="C:/Users/c1338/Desktop/绘梦/.m2-work/market-tools"
$env:HM_MARKET_TOOL_EXEC="composeCinematicPrompt"
$env:HM_MARKET_TOOL_ARGS='{"scene":"雨夜的老上海街头","mode":"video","duration":10}'
.\mvnw.cmd -s .mvn/settings.xml -B -ntp '-Dtest=MarketToolHarness' test
```

报告写到 `backend/target/market-tool-report.json`（含每个包的 `loadError` 与工具定义）。夹具不进普通 `verify`。

## 6. 已知边界

1. 执行器结果上限 10 万字符；超长提示词会被截断（`composeCinematicPrompt` 单次约 2–4 千字符，未触发）。
2. `deleteFile` 在服务端没有可写的项目工作区映射入口；阶段二接入文件 API 后再验证真实删除。
3. zod 版本跟随上游语义（v4）；若上游改用 v3 专有 API，需要按工具逐一回归，不能假设向下兼容。
4. 内置模块只解决「模块解析」，不改变「工具仍是可信服务端代码」这一判断：安装仍需管理员批准。
