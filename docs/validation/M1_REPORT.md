# M1 契约与独立前端验证

2026-10-24。M1-01～M1-06 完成；下一项为 M2-01。依据现行 PRD、DESIGN、CONTRACTS、TASKS、ISSUES，未扩展业务范围，未恢复旧供应商或重新执行 M0。本文的完成结论限于独立前端、契约与离线回归，不表示 1.0 或 A01–A18 已验收。

## 1. 各项交付与证据

| 任务 | 交付与验证 |
| --- | --- |
| M1-01 | [OpenAPI 3.1](../api/openapi.yaml)的 38 个操作 / 32 个路径通过标准与项目约束检查；[冻结决策](../api/M1_CONTRACT_REVIEW.md)定义 DTO、会话/CSRF、分页、错误、幂等重试、取消、修订和插件摘要策略；[目标映射](../api/route-map.json)覆盖全部 127 条源路由 |
| M1-02 | 从固定提交选择性迁入独立 Vue 应用与必要支持包，[280 条来源映射](../upstream/frontend-intake.json)保存原路径与原件 SHA-256；消除对服务运行代码的依赖，锁定安装、边界检查、严格类型检查与生产构建通过 |
| M1-03 | [统一客户端](../../frontend/packages/api/README.md)控制显式路由、Cookie/CSRF、项目上下文、物理路径拒绝、旧鉴权头移除、重定向拒绝、幂等和显式取消；9 项客户端测试通过 |
| M1-04 | [9 项流/协议测试](../../frontend/tests/protocol.test.ts)覆盖 SSE、Agent NDJSON、媒体 JSON 保活、FFmpeg、MCP/A2A；[录制比对器](../scripts/compare_contracts.py)及 2 项测试验证事件顺序、错误和文件摘要。复用 M0 脱敏记录，无供应商调用 |
| M1-05 | 移除桌面更新、移动、设备互联、服务器目录浏览等入口；保留管理员市场和已安装管理，经 Spring 显式代理、公司 Key 只写；成员管理按钮不可见，Agent 市场安装继续关闭；合成浏览器检查见下文 |
| M1-06 | 保留 [Toonflow MIT](../../frontend/LICENSE.Toonflow)，按实际浏览器 chunk 收窄至 260 个第三方包版本、11 个工作区包；另核对 1 项静态旗帜素材；[295 份许可/NOTICE 原文及摘要](../../frontend/licenses/artifact/manifest.json)归档，构建在缺少原文或补充摘要不符时失败 |

127 条映射的状态是 **8 frozen、92 proposed、25 excluded、2 internal**，全部 `publicEnabled=false`。冻结的是新增平台与关键创作契约；92 条兼容细项保留已核实的源字段、响应表达式、调用点及目标约束，在对应 M3 批次补完整 DTO、业务与权限测试后才能开放。客户端有路由映射不构成服务器白名单。

## 2. 可复现检查与结果

本次环境：Windows，Node 24.11.1、Bun 1.4.2、Python 3.11.9。执行器未迁入，固定上游的 Bun 1.3.14 基线不变。前端版本与锁定策略见[引入记录](../upstream/FRONTEND_INTAKE.md)；命令见[frontend/README](../../frontend/README.md)。

在 `frontend/` 执行：

```powershell
bun install --frozen-lockfile --ignore-scripts
bun run check
bun run build
bun run licenses:archive
```

结果：锁定安装通过；Web 边界/格式检查、vue-tsc 严格类型检查通过；**18 项测试、74 次断言，0 失败**（9 客户端 + 9 流/协议）。生产构建成功并输出许可清单，归档 295 份文件。存在大型 chunk 提示，最大约 6.2 MB（未压缩，含原富文本/Markdown 等依赖）；尚未开展性能验收，也不将该提示转成未确认的发布数值门槛。

仓库根目录的契约/文档检查使用[工具依赖及说明](../scripts/README.md)：

```powershell
python docs/scripts/check_contracts.py
python docs/scripts/sync_frontend_contracts.py --check
python -m unittest discover -s docs/scripts -p test_compare_contracts.py
python docs/scripts/check_docs.py
```

结果：OpenAPI 标准、38 操作的权限/CSRF/FR/A 元数据、127 条映射/来源与请求正反例通过；前端生成内容一致；2 项录制比对测试通过；文档相对链接、JSON 和脚本语法检查通过。另逐份复算许可归档 SHA-256，确认清单中的原文存在且摘要一致。以上离线检查不调用模型、市场或原 API。

## 3. 浏览器合成检查

前端开发页与临时合成响应桩仅监听 `127.0.0.1:5186` 和 `127.0.0.1:18181`，使用 fixture 身份、项目、模型和插件元数据；未输入真实账号、公司 Key 或外部客户端凭证，未执行安装、生成或删除。检查后停止临时服务，桩和缓存位于忽略的 `.m1-work/`，不属于构建或交接输入。

| 场景 | 结果 / 截图 |
| --- | --- |
| 未登录 | 展示内部登录入口；无真实后端时不能创作。[截图](evidence/m1-login.png) |
| 管理员市场 | 市场可见，Agent 安装仍禁用；没有获批摘要清单的条目明确显示暂不能安装。[截图](evidence/m1-admin-market.png) |
| 管理员已安装管理 | 列出合成已安装项，保留本地包安装、启停、导出、卸载控件；没有点击写操作。[截图](evidence/m1-admin-installed.png) |
| 项目成员设置 | 不展示插件市场、外部客户端等管理分类。[截图](evidence/m1-member-settings.png) |
| 项目选择 | 展示服务端返回的授权项目，没有服务器目录选择器。[截图](evidence/m1-project-picker.png) |

新建管理员和成员浏览器检查页未记录控制台 error。这里验证页面和客户端接线，不能证明服务端鉴权、安装完整性或真实任务执行通过。

## 4. 后续实施与限制

- **M2-01**：建立 Spring Boot、数据库版本化迁移和后端可复现构建/测试，再接会话、成员授权、密钥与外部客户端凭证。当前没有公司用户库或新平台已部署服务。
- **M2/M3**：完成账号/成员/供应商配置的业务交互与真实后端接通；旧 settings 整包写公司配置已阻断，不借旧 UI 绕过只写密钥接口。管理员管理项目元数据不自动获得创作内容权限。
- **M3**：补齐兼容 DTO、文件/画布修订 409 链、跨 Web/MCP/A2A 五名额与幂等、执行器取消确认及结果归属；断开传输不表示取消成功或可以释放名额。
- **M3-05/08**：实际实现 FFmpeg 项目文件系统及网络隔离、市场来源/摘要/包安装/回滚，复验真实扩展发布产物；客户端禁用按钮不能代替服务器拒绝测试。
- [ISSUES](ISSUES.md) 中的路径泄漏、业务终态、FFmpeg、市场及 Qwen 规格问题仍按原后续任务复验；支持的 2K 不因推荐默认 1K 而被强制降级。没有新增真实 2K/取消验收证据。
- **M4**：真实业务、局域网 HTTPS、同项目容量、重启、恢复、泄漏与最终发布许可再核对。当前归档只覆盖此次浏览器产物，未来服务、模型或动态插件仍需核对。

M1 实施时保留了原有未提交资料；后续按用户要求将现行源码、文档、夹具、许可和证据统一纳入 Git，远程及分支见[交接入口](../handoff/README.md#6-如何验证本次交接资料)。node_modules、dist、个人记录、临时目录和公司凭据不作为版本管理或交付资料。
