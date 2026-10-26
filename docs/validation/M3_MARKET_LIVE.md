# 真实市场凭据复验（M3-08 收尾）

> 2026-10-26。用团队提供的 TF-Router API Key 对 `api.toonflow.net` 做真实调用，验证「市场在线安装」链路。
> Key 只在临时环境以环境变量方式使用：**未写入仓库任何文件、未进入服务端日志**（见第 8 节）。

## 1. 结论

| 能力 | 结论 |
| --- | --- |
| 凭据有效性 | 有效，`POST /v1/plugin/getPlugin` 返回 200 |
| 目录查询代理 | 可用，21 项中 19 项判定可安装，1 项因标识不合规被拒并给出原因 |
| 技能安装 | **端到端可用**：下载、解析、入库 23 份文档，正文可注入 Agent |
| 节点安装 | 可下载入库（渲染未验证，见第 9 节） |
| 工具安装 | 可下载入库，但**当前全部无法执行**：均依赖外部模块 `toonflow:tool-zod` |
| 制品摘要 | 上游 `fileHash` 既不是 SHA-256 也不是 MD5，**不能当校验依据** |

顺带修复两处缺陷（第 7 节）：`loadError` 字段恒为空、外部模块检测漏掉紧凑写法。

## 2. 验证环境

```powershell
# 夹具：真实 Spring + H2 + 合成账号，端口 18097；市场地址由环境变量覆盖（默认仍是死端口）
cd backend
$env:HM_CAPABILITY="true"; $env:HM_MARKET_ENDPOINT="https://api.toonflow.net"
.\mvnw.cmd -s .mvn/settings.xml -B -ntp test -Dtest=CapabilityHarness
```

- 夹具账号写在 `backend/target/capability.ready`（`cap-admin`）。
- 写入类请求必须同时满足：`Origin: http://localhost:5187`、`X-CSRF-TOKEN`、会话 Cookie。应用下发 `Secure` Cookie，因此 **curl/urllib 在 http 下会被标准 Cookie 策略丢弃**，验证脚本需自行管理 Cookie。
- 密钥写入走 `PUT /api/v1/admin/plugin-market/secret`，请求体字段是 `apiKey`（不是 `key`）。

## 3. 真实目录

`total=21`，第一页 20 项、第二页 1 项。分布：

| 类型 | 数量 | 说明 |
| --- | --- | --- |
| 技能 | 15 | 均为 `.zip`，内含 `SKILL.md` + `references/` |
| 工具 | 4 | `deleteTool`、`composeCinematicPrompt`、`videoComposer`、`novelToTxt` |
| 节点 | 2 | `depthCaptureNode`、`webEpisodeEditorProNode` |

比 2026-10-22 的 M0 快照（13 项）多 8 项，新增集中在技能类。

被拒 1 项：`minimaxh3PromptWritingOptimizationInspection `（标识尾部带空格），服务端返回原因「制品标识不符合安装规范」，未写入目录表，因此不可安装——**这属于上游数据问题，不是我们的缺陷**。

## 4. 安装实测

| 制品 | 类型 | HTTP | 耗时 | 入库结果 |
| --- | --- | --- | --- | --- |
| `v118workflow` | 技能 | 200 | 561 ms | 23 份文档 |
| `deleteTool` | 工具 | 200 | 97 ms | 可存、不可执行 |
| `depthCaptureNode` | 节点 | 200 | 151 ms | 可存 |

目录查询耗时 0.4–1.6 s（受上游响应影响），安装单件在 1 s 内完成。

## 5. 技能：装了就能用，但正文会被截断

`v118workflow.zip` 实测：

| 项 | 值 |
| --- | --- |
| 制品字节 | 123 515 |
| 解压后 | 25 条目 / 241 253 字节，全部 `.md` |
| 根文档 | `SKILL.md` 11 619 字节 |
| 其余 | `references/**` 22 份，单份最大 29 374 字节 |
| 无 `manifest.json` | 名称回退为文件名，即 `v118workflow` |

正文注入规则是单条 6 000 字符、合计 20 000 字符（`CapabilityServiceImpl`）。这份技能光 `SKILL.md` 就有 11 619 字节，**注入时会被截断到前 6 000 字符加提示语**；`references/` 下的细则不会进提示词。

结论：技能包是当前唯一「装上立刻生效」的类别，但长技能只剩开头部分可用。若要真正发挥这类技能，需要按需读取 `references/`（例如给 Agent 一个读取技能附件的工具），不在本轮范围。

## 6. 工具：全部不可执行

直接检查制品源码（预签名地址可直接下载，无需鉴权头）：

| 工具 | 大小 | 外部依赖 |
| --- | --- | --- |
| `deleteTool.tool.js` | 49 009 B | `toonflow:tool-zod` |
| `composeCinematicPrompt.tool.js` | 91 935 B | `toonflow:tool-zod` |
| `videoComposer.tool.js` | 55 504 B | `fs`、`fs/promises`、`os`、`path`、`toonflow:tool-zod` |
| `novelToTxt.tool.js` | 3 825 924 B | `fs/promises`、`module`、`path`、`timers/promises`、`util`、`worker_threads`、`toonflow:tool-zod` |

我们的执行器（GraalJS，`IOAccess.NONE`、无宿主访问）对这类包在加载期拒绝，`loadError` 现在会显示「工具包引用了外部模块，当前执行器只支持自包含工具包」。

需要注意的是：**补 `toonflow:tool-zod` 虚拟模块只是第一道门**。`videoComposer` 与 `novelToTxt` 还要 Node 内建 `fs`/`worker_threads`，而当前上下文是不给 IO 的。真正让市场工具可用要同时决定两件事：

1. 提供 zod 虚拟模块（把 zod 打成单文件 ESM，通过自定义 `FileSystem` 或源码重写注入）；
2. 设计工具的文件访问边界（工作区沙箱路径白名单），而不是简单开 `IOAccess.ALL`。

两项都属于新增设计，不是本轮的修 bug 范围。

## 7. 本轮修复的两个缺陷

### 7.1 `loadError` 字段恒为空

`PluginRespDTO.loadError` 在契约里存在（上游 `/api/tools/get` 也返回它），但实现里一直传 `null`。后果是：工具包装上了、加载失败，管理端看到的仍是「已安装、无异常」，只能去翻服务端日志。

修复：`ToolRuntime.loadError(plugin)` 按「插件 id + 摘要」缓存加载结果，`PluginServiceImpl.view` 对 `type=tool` 填该字段。新增测试 `reportsWhyInstalledToolCannotLoad`。

### 7.2 外部模块检测漏掉紧凑写法

原正则 `^\s*(?:import\s|...)` 只认 `import ` 后带空白。真实制品是 `import{z as e}from"toonflow:tool-zod";`——`import` 后紧跟 `{`，检测不到，于是交给 GraalJS 去解析，最终报出内部错误 `Unsupported URI scheme toonflow`。

修复：改为 `^\s*(?:import\b|export\s+[^;]*\bfrom\b|.*\brequire\s*\()`，并用真实写法新增固定用例 `IMPORTING_MINIFIED`。

## 8. 安全核对

| 项 | 实测 |
| --- | --- |
| 密钥出现在服务端日志 | 0 次（`grep` 全日志） |
| 密钥进入仓库文件 | 无（只在临时目录脚本里以环境变量读取） |
| 响应回显密钥 | 无：`status` 只返回布尔，`providers` 列表排除 `market`，配置导出只含布尔 |
| 审计记录 | 只记操作者与结果，不含密钥 |

**建议**：该 Key 已在对话中明文出现，请到上游重置一次，再把新 Key 填进「设置 → 插件市场」。

## 9. 未验证 / 边界

1. **节点渲染**：两个节点只为「入库」取证，没在画布内放实例——Windows 上 `workspaces/files/*` 恒 503（既有平台限制）。
2. **工具执行**：如上，缺模块桥与文件访问设计。
3. **收藏切换**：本轮只做目录与安装，未验证 `toggleCollection`。
4. **目录第二页**：确认存在 1 项（`videoComposer`），未做分页连续性断言。
5. **真实部署**：验证跑在 H2 夹具上，未在 MySQL/生产配置复跑。

## 10. 制品契约的两个坑（供后续实现注意）

1. **下载地址是预签名 URL，有效期 1 小时**（`X-Tos-Expires=3600`）。`market_catalog` 里的行一旦过期，再安装会 502；**安装前必须刷新目录**，不能把行长期当缓存用。
2. **`version` 字段与 URL 路径版本不一致**：`v118workflow` 条目标 `1.0.0`，而下载路径是 `/plugins/skill/v118workflow/2.0.0/v118workflow.zip`。说明上游的 version 字段不可靠，安装后展示的版本应以制品包头/文件名为准（这是 M0-06 已记录的同类问题）。

另外实测确认：同一制品的 `fileHash = 4157b9da…`，而下载字节的 SHA-256 是 `6674e568…`、MD5 是 `a343e402…`，三者互不相等。所以「首次下载记获批摘要、之后必须一致」的做法是必要且正确的最小校验。
