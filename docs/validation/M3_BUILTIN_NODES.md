# M3-05 / M3-I08：内置节点包入库与真实加载

> 2026-10-26，基线 `8b91541`。范围：把 **textNode（文本）与 imageNode（图片）** 两个上游节点包随部署分发，启动时幂等登记为 `builtin`，并由页面真实取回、执行、注册。验收口径为**加载可用**，不含节点内的生成链路。

## 1. 做了什么

| 部分 | 内容 |
| --- | --- |
| 源码引入 | 从固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d` 用 `git archive` 导出，迁入 `frontend/packages/nodeShared`、`frontend/packages/nodes/textNode`、`frontend/packages/nodes/imageNode`；来源与逐文件摘要见[节点包引入记录](../upstream/NODE_PACKAGES_INTAKE.md) |
| 构建 | `frontend/scripts/build-nodes.ts`（`bun run build:nodes`）逐个执行节点包自己的 `vite build`，把字节原样收口到 `backend/src/main/resources/capabilities/nodes/`；`frontend/build/`、`frontend/data/` 为中间目录并已忽略 |
| 分发 | 既有 `capabilities/nodes/get`、`nodes/files` 直接读登记表，无需改动；`ETag` 为安装摘要 |
| 登记 | 新增 `BuiltinNodeRegistrar`（`ApplicationRunner`），启动扫描 classpath `capabilities/nodes/*.umd.js`，`type=node`、`source=builtin`、`id=node:<name>`（沿用安装链 `type:name` 约定），摘要取产物字节 SHA-256，版本/显示名/作者/配置规则解析产物包头 `/*! toonflowNode:{...}*/` |

**幂等语义**（`BuiltinNodeTests` 固定）：

- 摘要相同 → 完全不写库，`enabled`/`config_json`/`installed_by`/`created_at`/`updated_at` 全部不动；
- 摘要变化 → 只更新 `content`/`sha256`/`version`/`manifest_json`，**保留**管理员启停与配置；
- 管理员停用的内置节点，重启后不会被重新启用；
- 同名但非 `builtin` 的既有节点不被覆盖，记录 WARN 跳过；
- 单个制品损坏（缺包头、文件名不合规、readme 超过 20000 字符）→ WARN 并跳过该节点，其余照常登记，不阻断启动。

## 2. 产物

| 产物 | 版本 | 显示名 | 字节 | SHA-256 |
| --- | --- | --- | --- | --- |
| `textNode.umd.js` | 2.0.0 | 文本 | 413453 | `8dfdcb18ed11d0742b0cbe2da9470053795e62790e034606935543c451b8056e` |
| `imageNode.umd.js` | 2.0.0 | 图片 | 520959 | `92e153b6a4cdef96ecfbb5abd2688b886bd9234ee2525c9b21dab070f3ff8eae` |

`bun run build:nodes` 重建后的字节与仓库内产物**完全一致**（产物不含时间戳），因此「重启后摘要不变 → 不写库」这一前提可复现。

## 3. 验证

### 3.1 后端

`cd backend && ./mvnw.cmd -s .mvn/settings.xml -B -ntp verify` → **132 项通过**（原 126 + `BuiltinNodeTests` 6 项），Spotless 清缓存后 218 个 Java 文件实际检查、0 需改动。

`BuiltinNodeTests` 覆盖：重复登记仍单行且时间戳不变；`nodes/get` 的 URL 形态；`nodes/files` 字节与 classpath 产物逐字节一致且 `ETag` 等于摘要；停用与配置跨重启保留；摘要变化后的更新与保留；坏包跳过；`builtin` 卸载 403 `PLUGIN_BUILTIN`；管理面 `source=builtin`、`canManage=false`。

### 3.2 前端

`cd frontend && bun run check` → **26 项通过**，含 `nodeShared`/`textNode`/`imageNode` 三个包的 `vue-tsc`。

### 3.3 真实浏览器

夹具：`CapabilityHarness`（`HM_CAPABILITY=true`，H2，端口 18097）+ Vite dev server（5187，`HUIMENG_API_TARGET` 指向夹具）。

- `m3M09BuiltinNodes.mjs`（内置节点专项，17 项）：目录发布两项且启用、`toonflowTiptapHost` 按需注入、两个脚本执行后注册到 `window.toonflowNodes`、导出可挂载组件、下发字节与 classpath 产物摘要一致、能力脚本请求无失败响应，**17/17 通过**。
- `m3M09QaBuiltinNodes.mjs`（QA 独立复验，43 项断言）：A 段目录/摘要/停用/卸载/管理面 28 项、B 段浏览器加载 8 项、C 段画布阻塞点 3/6（3 项失败即下述平台限制）、D 段构建可复现性，结论 PASS。

两处必须说准的实测细节：

1. 夹具里 `nodes/get` 实际返回**三项**——多出的 `demoNode` 是 `CapabilityHarness` 自己安装的合成节点，属夹具预置；`source=builtin` 的仍**恰好两项**。
2. 页面自身（不经脚本手工 import）在进入工作区后就会请求 `nodes/get` 与两个 `nodes/files`，并由画布代码把 `textNode`/`imageNode` 注册进 `window.toonflowNodes`，`.vue-flow` 容器已挂载。也就是说「画布取回并注册节点」这条路径是真实画布代码验证的，不只是脚本模拟。

## 4. 未覆盖：画布内渲染（Windows 平台限制）

节点实例放到画布上并渲染出来，需要一份画布 `.json` 文件，而画布的枚举/读取/创建全部走 `workspaces/files/*`：

| 接口 | Windows 实测 |
| --- | --- |
| `GET …/creative/workspaces/files/list?path=` | 503 `EXECUTOR_UNAVAILABLE`「当前文件系统不支持此安全文件操作」 |
| `PUT …/workspaces/files/write?…&exclusive=true` | 503 `EXECUTOR_UNAVAILABLE`「当前文件系统不支持安全原子保存」 |

根因经 JDK 直接探测（JDK 21.0.8 / Windows 11）：`sun.nio.fs.WindowsFileSystemProvider` 提供的 `sun.nio.fs.WindowsDirectoryStream` **未实现** `SecureDirectoryStream`，安全目录句柄路径直接抛 `UnsupportedOperationException`。

**这不是本批引入的**：本批 `git diff --numstat -- backend/src/main/java` 为空（只新增 `BuiltinNodeRegistrar.java`），抛 503 的文件最后改动是基线祖先 `fd57510`；与交接页既有结论「原文档 Windows read 缺安全目录句柄仍 503」一致。

因此本批**不能**声称「节点在画布内可渲染」。补齐条件：Linux 夹具（`SecureDirectoryStream` 可用）或为 Windows 提供等价安全文件原语；有画布文件后即可在 VueFlow 内放实例并截图取证。

## 5. 记录在案的风险

1. **内置登记没有 prune**：`BuiltinNodeRegistrar` 只做 insert/update，而 `uninstall` 对 `builtin` 恒 403。若后续从 classpath 移除某个内置产物，数据库里那行会残留并继续分发旧字节，管理员无法清除。当前两个产物都在，不构成现网问题；补 prune 时需同时定义「管理员配置如何处理」。
2. **浏览器脚本的控制台过滤已修正**：初版 `m3M09BuiltinNodes.mjs` 把含 `503` 的 console error 静默丢弃，`failures` 数组也从未赋值，导致「无控制台错误」是过滤出来的结论。已改为如实收集并单独归类 `platformNoise`，新增「能力脚本请求无失败响应」检查，本次证据中如实记录 2 条平台 503（`workspaces/files/list`、`agent/list`）。

## 6. 仍待

- 其余 6 个上游节点（`imageGenerationNode`、`videoNode`、`videoGenerationNode`、`audioNode`、`audioGenerationNode`、`director3dNode`）。
- 画布内渲染的 Linux 复跑。
- 节点内生成链路：`/creative/ai/generate`、`/creative/ai/media/generate` 后端未实现，点击生成返回能力缺失。

## 7. 证据

- `docs/validation/evidence/m3-m09-builtin-nodes.json`（内置节点专项，17 项）
- `docs/validation/evidence/m3-m09-qa-builtin-nodes.json`（QA 独立复验，43 项）
- `docs/validation/evidence/m3-m09-qa-build-and-platform.json`（重建摘要、JDK 平台探测、本批 git 范围）
- 脚本：`docs/validation/scripts/m3M09BuiltinNodes.mjs`、`docs/validation/scripts/m3M09QaBuiltinNodes.mjs`
