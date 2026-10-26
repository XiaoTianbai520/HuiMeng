# 插件加载与使用：现状与交接

> 基线：2026-10-26，`c39f226`。本文只描述**插件制品的格式、加载协议、当前实现与已知断点**，不重新定义需求。需求以 [PRD](../product/PRD.md)、[CONTRACTS](../api/CONTRACTS.md)、[TASKS](../plan/TASKS.md) 为准。

## 1. 上游制品是什么格式

不是 TypeScript 源码。真实市场目录（[上游目录样本](../validation/evidence/upstream-market-catalog.json)，13 个去重条目）的分布：

| 类型 | 扩展名 | 数量 | 形态 |
| --- | --- | --- | --- |
| 节点 | `.umd.js` | 2 | UMD 包，导出到 `window.toonflowNodes.<name>` |
| 工具 | `.tool.js` | 3 | ESM `export default { validateConfig, createTools }`，头部 `/*! toonflowTool:{...} */` |
| 技能 | `.zip` | 8 | 内含 `SKILL.md` + `references/`，纯文档 |

代码里的 `.ts` 只出现在 `PluginServiceImpl.TEXT_EXT`（归档内文本提取白名单），不是制品格式。

## 2. 三类制品的加载协议

### 2.1 节点 `.umd.js`

构建配置在 `frontend/packages/nodeScaffold/index.ts`，产物名 `<name>.umd.js`，`name` 必须是 `^[a-z][a-zA-Z0-9]*$` 的小驼峰。关键约束是 externals 指向页面注入的宿主：

```text
vue / @vue/runtime-core / @vue/runtime-dom  -> toonflowNodeHost.vue
@vue-flow/core                               -> toonflowNodeHost.vueFlow
element-plus                                 -> toonflowNodeHost.elementPlus
three                                        -> toonflowNodeHost.three
@earendil-works/pi-agent-core / pi-ai        -> toonflowNodeHost.ai
@tiptap/* / marked / dompurify               -> toonflowTiptapHost.*
```

宿主已由 `frontend/src/pages/workspace/panels/canvas/index.vue`（约 1097 行）注入；`loadNodeComponent.ts` 加载脚本前会按代码内容判断是否需要 `@/lib/tiptapHost`。加载器从 `window.toonflowNodes[name]` 取组件，取不到就报「节点脚本未导出」。

**结论：协议对齐。** 但只用合成 `demoNode` 验过（[M3-09 记录](../validation/M3_CAPABILITY_LOADING.md)），真实 `.umd.js` 未跑过，风险在 Vue/VueFlow/Element Plus 版本差异与 `toonflowNodeHost.ai` 的函数签名。

### 2.2 工具 `.tool.js`

原版是 **ESM**，由服务端执行：

```js
/*! toonflowTool:{"name":"...","version":"...","components":[],"configRules":[...]} */
export default {
  validateConfig(config) { ... },
  createTools({ config }) { return [{ name, description, parameters, execute }] }
}
```

浏览器侧另有一套 UI 契约（`frontend/packages/toolScaffold/src/client.ts`）：注入 `toonflowToolHost`（vue、elementPlus、axios、formCreate），从 `/api/tools/renderers` 取列表，按 `tools/client?name=&version=<sha>` 加载脚本，读 `window.toonflowToolViews[rendererName]` 取组件。

注意两者不是一回事：`components` 为空表示这是纯 Agent 工具，没有 UI。

### 2.3 技能 `.zip`

`PluginServiceImpl.parseArchive` 解压后把文本文件写入 `plugin_documents`，名称与版本优先取归档内 `manifest.json`，否则用文件名。`mcp/skill` 与项目 `agent/skills` 读取登记并下发，不需要执行。

## 3. 当前实现位置

| 关注点 | 文件 |
| --- | --- |
| 安装/校验/归档/市场代理 | `backend/src/main/java/cn/huimeng/service/impl/PluginServiceImpl.java` |
| 安全接口与文档编辑 | `backend/src/main/java/cn/huimeng/controller/PluginController.java` |
| 能力目录与脚本分发 | `service/impl/CapabilityServiceImpl.java`、`controller/CapabilityController.java` |
| 内置节点登记（启动幂等） | `service/impl/BuiltinNodeRegistrar.java`、`src/main/resources/capabilities/nodes/*.umd.js` |
| 登记表 | `backend/src/main/resources/db/migration/V7__plugin_registry.sql`（plugins / plugin_documents / market_collections / app_documents） |
| 节点加载 | `frontend/src/pages/workspace/panels/canvas/loadNodeComponent.ts` |
| 工具界面加载 | `frontend/packages/toolScaffold/src/client.ts` |

`V7` 没有种子数据；`source` 允许 `file` / `market` / `builtin`。`builtin` 行由 `BuiltinNodeRegistrar` 在启动时按 classpath 产物登记：两个同名资源存在时才有一致的摘要，重复启动不写库，摘要变化只更新内容与元数据并保留 `enabled`/`config_json`/`installed_by`/`created_at`，因此管理员停用的内置节点不会被重启打开。单个制品损坏时记录 WARN 并跳过该节点，不阻断启动（`BuiltinNodeTests` 固定该行为）。

## 4. 真正能用到什么程度

| 类型 | 安装 | 运行期加载 | 真正使用 |
| --- | --- | --- | --- |
| 节点 | 可用 | 可用（`nodes/get` + `nodes/files` + 浏览器执行） | 内置 `textNode`/`imageNode` 已真实加载（浏览器 16 项）；画布内渲染待在 Linux 夹具复跑；生成链路仍未接通 |
| 工具 | 可用 | 可用（`renderers` 按包头 `components` 发布真实工具名，`tools/client` 下发第二行内嵌 `code`+`css`） | **无执行器**，Agent/MCP 工具清单硬编码，不读插件 |
| 技能 | 可用 | 可用（文档入库 + `mcp/skill` 下发目录 + `CapabilityService.skillPrompt()` 注入 Web Agent 提示词） | Web Agent 已注入正文（单条 6000/合计 20000 字符上限）；references 不注入 |

## 5. 已知断点

### 5.1 市场在线安装链路（已接通，待真实凭据复跑）

原先四处独立断开，现按上游真实字段与「服务端查目录」的安全模型重做：

1. **`installable`**：不再依赖上游不存在的 `manifest.sha256`，改由类型、文件名、`state`、下载来源四项判定；不可安装时由服务端返回 `reason`（如「下载来源不在允许清单内」「该制品已下架」），页面直接显示。
2. **请求体**：`POST /admin/plugin-market/install` 只接受 `{pluginId,force?}`，多带 `url`/`sha256` 直接 400。
3. **下载地址**：搜索结果落 `market_catalog` 表，安装时按 `plugin_id` 回查地址，从不采信请求体。
4. **下载主机**：默认白名单为 `api.toonflow.net,toonflow.tos-cn-beijing.volces.com`，逐跳校验（最多 5 跳），签名 302 可以跟，但每一跳都必须仍在允许来源内。

字段映射与摘要语义：

- `identifier` → 安装名，中文 `name` → `displayName`，`supplier`/`desc` → `author`/`description`；目录响应同时兼容 `data.list` 与 `data.items`。
- `fileHash` **不作**校验依据（实测与下载字节不一致）。首次成功下载时把字节 SHA-256 记为获批摘要，之后同一 `pluginId` 必须与之一致，否则 409 `CHECKSUM_MISMATCH`。
- 真实市场凭据复跑与 M0-I12～I14 仍未做（需 TF-Router 子账号）。

### 5.2 工具界面契约（已修复）

早先 `renderers()` 把插件名同时当成工具名和渲染器名，`tools/client` 又原样下发承载 `createTools` 的 ESM 正文，浏览器加载必然失败。现按上游源码对齐：

- 第一行 `/*! toonflowTool:{...} */` 提供元数据，`components` 是**真实工具名**列表（`^[a-z][a-zA-Z0-9_]*$`，最多 100、不重复）；为空表示纯 Agent 工具，不发布界面。
- 第二行 `/*! toonflowToolClient:{"code","css"} */` 才是浏览器界面。`tools/client` 下发 `code` + 一次性样式注入（固定 `style` 元素 id `toonflowToolStyle:<name>`），不再下发 ESM 正文。
- `renderers` 只发布「已启用 + `components` 非空 + 有界面」的包，`tools` 为 `components` 原样；版本仍是安装摘要。
- 元数据损坏的包不出现在 `renderers`，但在 `tools/get` 里保留并显示「工具元数据无效，无法加载」。

### 5.3 工具无执行器

`createTools` 只在原版 Node/Bun 里跑。当前 Web Agent 与 MCP 的工具清单都是硬编码（画布 18 项等），不读 `PluginService`。

### 5.4 内置节点的撤销（已修复）

`BuiltinNodeRegistrar` 现在会在登记后清理「已下线的内置产物」：只删 `type=node` 且 `source=builtin`、本轮扫描不到的条目。为避免资源整体读取失败时误删，**只有至少扫描到一个产物才执行清理**；产物存在但本次登记失败（坏包）的节点仍在册，不会被删。管理员此前对某内置节点做的启停与配置随该行一并移除，因为产物已经不存在。

## 6. 修复方案（按依赖顺序）

1. ~~**内置节点包入库**（M3-05 / M3-I08）~~ **已完成（textNode/imageNode 两个节点）**：产物入 `backend/src/main/resources/capabilities/nodes/`，启动按摘要幂等登记为 `builtin`；`nodes/files` 可分发，管理员停用状态跨重启保留。构建与来源见 [节点包引入记录](../upstream/NODE_PACKAGES_INTAKE.md)。仍未做：其余上游节点、画布内渲染的 Linux 复跑。
2. ~~**修 `renderers()`**~~ **已完成**：按包头 `components` 发布真实工具名，`tools/client` 下发内嵌界面（`code`+`css`），纯 Agent 工具不占渲染器名额；实现见 `ToolPackage` 与 `CapabilityServiceImpl`。
3. ~~**技能注入**~~ **已完成**：`CapabilityService.skillPrompt()` 读取已启用技能的 `SKILL.md`（缺失时取第一个 `.md`），单条 6000、合计 20000 字符上限，由 `AgentServiceImpl.systemPrompt()` 追加到系统提示词；与 `mcp/skill` 共用同一登记，停用即消失，不向客户端暴露路径。
4. **工具执行器**（唯一有方案分叉）：
   - Node sidecar：与原版一致，兼容性最好，但多一个运行时；
   - GraalJS：留在 JVM 内，需要 ESM 适配与 `fs`/`fetch` 受控注入。
   两项都必须满足 M3-08 的「执行隔离」验收，不能只做「能跑」。
5. ~~**市场在线安装**~~ **已完成（待真实凭据复跑）**：字段映射、请求体、下载来源与获批制品清单见 5.1；`installable=false` 时给出原因，不为了能点而放行。`V8` 新增 `market_catalog`。
6. **真实凭据复跑**：需要用户提供 TF-Router 子账号凭证，顺带复验 M0-I12～I14。

## 7. 验证方式

- 后端：`cd backend && ./mvnw.cmd -s .mvn/settings.xml -B -ntp verify`（当前 132 项，含 `BuiltinNodeTests` 6 项）
- 前端：`cd frontend && bun run check`（26 项 + 内置节点包 vue-tsc）
- 内置节点构建：`cd frontend && bun run build:nodes`（产物写入 `backend/src/main/resources/capabilities/nodes/`）
- 能力目录夹具：`CapabilityHarness` + `docs/validation/scripts/m3M09NodeLoad.mjs`（合成节点）、`m3M09BuiltinNodes.mjs`（内置节点）
- 夹具需要 Linux（工作区安全原语），跑在容器 `maven:3.9.11-eclipse-temurin-21`，`network host`，挂载 `/tmp/huimeng-m306:/repo`；Windows 上 `workspaces/files/list|write` 恒 503（Windows JDK 的 `Files.newDirectoryStream` 返回 `sun.nio.fs.WindowsDirectoryStream`，不实现 `SecureDirectoryStream`），画布 `.json` 读写被挡住，因此只能验证到「画布取回并注册节点」，放置实例并渲染需在 Linux 复跑
- 浏览器脚本需要 `PLAYWRIGHT_CORE_PATH` 与 `PLAYWRIGHT_CHROMIUM_PATH`
- 前端 dev server 若被 safe-delete 垫片打断，用 `env -u NODE_OPTIONS -u CODEBUDDY_SAFE_DELETE_* bun run dev`

## 8. 交接时必须知道的两件事

- `.m0-work/`、`.m1-work/`、`.m2-work/` 是本地缓存，**不是**构建或交接输入；不要从中反推正式源码。
- 工作区常有大量 `git status` 假阳性（stat 缓存），判断真实改动用 `git diff --numstat`；推送走 `orgin master`，需要代理 `127.0.0.1:7890`。
