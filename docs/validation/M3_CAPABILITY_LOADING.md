# M3-09：能力目录与已安装节点的运行期加载

M3-05 的「内置工具随部署安装并实际加载」与 M3-08 的「插件运行期加载」共用一个缺口：`capabilities/nodes/get` 等目录接口此前只返回空列表占位，装进库里的节点/工具包不会被页面看到，更不会执行。

本批把目录改为读取已安装能力登记，并补上脚本分发端点，让页面按既有前端逻辑加载节点脚本、注册组件。

## 1. 实现

| 端点 | 行为 |
| --- | --- |
| `GET /capabilities/nodes/get` | 已安装节点（含停用项）的 `name/displayName/url/enabled/config`；名称不符合页面脚本命名规则（`[a-z][a-zA-Z0-9]*`）的条目不发布 |
| `GET /capabilities/nodes/files?name=` | 节点脚本正文；`text/javascript; charset=utf-8`、`no-cache`、`ETag` 为安装摘要、`nosniff`；停用/不存在/越界名称一律 404 `CAPABILITY_NOT_FOUND` |
| `GET /capabilities/tools/get` | 内置 `canvasOperations` 与已安装工具的统一目录，`canManage=false` |
| `GET /capabilities/tools/renderers` | 已启用工具的界面清单 `{name, tools:[name], url}`，URL 带 `version=<sha256>` |
| `GET /capabilities/tools/client?name=&version=` | 工具界面脚本；版本必须与安装摘要一致，否则 404 |
| `GET /capabilities/mcp/skill` | 已启用的全局技能包 `{name, displayName, version}` |
| `GET /projects/{id}/creative/agent/skills` | 先校验项目成员身份，再返回同一技能目录 |

实现位于 `CapabilityService`/`CapabilityServiceImpl`，控制器只做协议转换。列表查询不带安装包正文，脚本请求按需读取单条内容；节点配置里的秘密字段（`apiKey`/`secret`/`token` 等）不下发。

与页面的约定沿用现有代码：节点 URL 必须是 `/api/v1/capabilities/nodes/files?name=<name>`，页面执行脚本后从 `window.toonflowNodes[name]` 取组件；工具界面 URL 必须是 `/api/v1/capabilities/tools/client?name=<name>&version=<64 位摘要>`。

## 2. 验证

**后端 5 项**（`CapabilityCatalogTests`，全量 126 项通过）：

- 安装节点后成员能读到目录与脚本正文，内容与安装源一致，`ETag` 等于摘要，未登录 401；
- 停用后目录 `enabled=false`、脚本 404；未知名称与 `../` 越界均 404；
- 工具目录包含 `canvasOperations` 与已安装工具；渲染器 URL 携带摘要版本，正确版本可取回脚本，错误版本 404，停用后清单为空；
- 技能安装后出现在 `mcp/skill`；项目成员能读 `agent/skills`；
- 节点配置写入 `apiKey` 后，目录只回 `label`，不含秘密字段。

**浏览器 10 项**（`m3M09NodeLoad.mjs`，真实 Spring 夹具 + Vite + Chromium）：

| 检查 | 结果 |
| --- | --- |
| 登录、打开画布工作区 | 通过 |
| `/capabilities/nodes/get`、`nodes/files` 均 200 | 通过 |
| 页面执行节点脚本并注册 `window.toonflowNodes.demoNode` | 通过（带 `render` 入口） |
| 登录后无失败请求、无控制台错误、无未捕获页面错误 | 通过 |

夹具 `CapabilityHarness` 安装一份合成节点 `demoNode.umd.js`、工具 `mediaGeneration.tool.js` 与技能 `demoSkill.md`；脚本执行后 `window.__hmDemoNodeLoaded === true`，证明不是只把 URL 返回给页面。

证据：`docs/validation/evidence/m3-m09-node-load.json`；夹具 `backend/src/test/java/cn/huimeng/CapabilityHarness.java`（`HM_CAPABILITY=true` 才启动）。

## 3. 尚未覆盖

- **随部署内置的节点包**：分发通道已通，但仓库还没有正式的内置节点脚本（上游 UMD 包属于构建产物，未纳入源码）；本轮用合成节点证明加载链路，内置包仍待单独引入与授权审查。
- **市场工具的服务端执行**：工具界面脚本只在浏览器运行，Agent 不会调用市场工具的函数；若需要模型直接调用，必须先设计沙箱与权限模型，本批不做。
- **自定义媒体供应商**：仍按 409 明确拒绝，未开放。
- 未做真实市场凭据复跑与 M0-I12～I14 的版本/摘要、节点授权回归。
