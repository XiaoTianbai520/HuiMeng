# M0-I12～I14 复验记录

2026-10-26。三项都是 M0 阶段在**原版**上发现的问题，迁移条件写的是「新平台必须做到」。本记录逐条给出新平台的实现位置和回归证据。

## M0-I12 版本与摘要语义

**原问题**：市场条目标 `1.0.0`，ZIP 内 `SKILL.md` 标 `1.1.0`；`fileHash` 与下载字节 SHA-256 不一致，算法未定义。

**新平台口径**（`PluginServiceImpl`）：

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `version` | 制品包头声明（节点/工具 `/*! toonflowNode|toonflowTool:{...}*/`，其它取 `@version`） | 与市场条目字段无关，市场版本只用于展示 |
| `sha256` | 安装字节的 SHA-256 | 唯一可用于校验的摘要 |
| 市场 `fileHash` | **不使用** | 真实市场实测既不等于 SHA-256 也不等于 MD5 |

真实市场复验（[M3_MARKET_LIVE](M3_MARKET_LIVE.md)）已确认：`v118workflow` 条目标 `1.0.0`，下载路径是 `.../v118workflow/2.0.0/v118workflow.zip`，安装后按制品内容登记，不采信条目版本。下载地址是 1 小时预签名 URL，所以「首次下载记录获批摘要、之后不一致即 409」是唯一可用的校验方式。

回归测试：`PluginMarketTests.recordsByteDigestIndependentlyOfDeclaredVersion`（版本取包头 3.1.0，摘要等于字节 SHA-256）、`PluginMarketProxyTests` 的摘要不匹配 409。

## M0-I13 节点管理与下载来源

**原问题**：原版对声明「非本地客户端」的合成节点启停没有守卫；隔离安装器接受本机测试源与跳转。

**新平台**：

- 启停/卸载/配置全部在 `/api/v1/admin/**`，由 `SecurityConfiguration` 限定 `ROLE_ADMIN`，并叠加固定来源校验（`BoundaryFilter`）与 CSRF。
- 下载来源两层：`huimeng.market.artifact-hosts` 白名单（默认 `api.toonflow.net,toonflow.tos-cn-beijing.volces.com`）+ 逐跳校验，最多 5 跳；非 HTTPS 且非回环直接 403。

回归测试：`PluginMarketTests.nodeManagementRequiresAdministrator`（成员启停 403、卸载 403）、`PluginMarketTests` 的 `PLUGIN_SOURCE_FORBIDDEN`、`CapabilityCatalogTests` 的成员只读目录。

## M0-I14 反引号导出与构建产物兼容

**原问题**：上游本地构建的 `ext-txt.umd.js` 用反引号写 `toonflowExts[\`ext-txt\`]`，原安装器正则只接受单双引号，原字节安装 400。

**新平台**：安装器不解析这行导出，只按文件名与包头取元数据，因此反引号、单引号、双引号三种写法一视同仁。

回归测试：`PluginMarketTests.installsExtensionBuiltWithBacktickExport` —— 用反引号写法的 `ext-txt.umd.js` 直接安装成功，`type=ext`、`version=1.2.0`。

## 结论

| 编号 | 状态 | 证据 |
| --- | --- | --- |
| M0-I12 | 关闭 | 摘要=字节 SHA-256、版本=包头声明、`fileHash` 不参与；真实市场同链路复验 |
| M0-I13 | 关闭 | 管理面 `ROLE_ADMIN` + 来源白名单 + 逐跳校验；成员越权 403 有用例 |
| M0-I14 | 关闭 | 反引号构建产物可直接安装；不再依赖导出写法正则 |

仍未覆盖：真实发布构建产物只验了 `ext` 一种（`txt` 示例），其余扩展没有上游发布包可复现；这一条不阻断，但换新发布包时要重跑同一个用例。
