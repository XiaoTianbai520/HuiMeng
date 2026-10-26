# M3-07 排除路由与页面入口清理

> 2026-10-26。范围：确认 25 条排除路由没有后端入口、前端白名单映射和页面调用；管理端把首页、团队、项目文件、画布与 8 个设置面板走一遍，记录失败请求与排除路由命中。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| 排除路由数量 | 25 条（`R033`～`R049`、`R067`～`R072`、`R124`、`R127`），与[路由清单](../api/route-inventory.md)一致 |
| 前端路由白名单 | 通过：25 条均不在 `route-map.json` 的 `routes`，也不作为任何 `targetPath` |
| 前端源码 | 通过：`frontend/src` 无 `/api/desktop/`、`/api/mobileLink/`、`/api/browser/` 引用，也没有 `files/reveal` 与 `workspaces/selectDirectory` 调用 |
| 后端入口 | 通过：`backend/src/main/java` 无对应映射，也没有 `/api/desktop`、`/api/mobileLink`、`/api/browser` 前缀 |
| 页面遍历 | 通过：12 次遍历（首页/团队/项目文件/画布 + 界面设置、常规配置、团队管理、插件市场、外部客户端、个性化、隐私、关于），0 个失败请求、0 个排除路由命中、0 个未捕获错误 |
| 市场入口 | 通过：只经 `/api/v1/admin/plugin-market/*` 与 `/api/v1/admin/plugins`，需要管理员会话与 CSRF；普通成员 403 |
| Web 替代 | 通过：剪贴板走浏览器 `navigator.clipboard`；"在文件夹中显示"改为提示使用项目文件下载，不再调用桌面接口 |

## 2. 实现要点

- **客户端契约闸门**（`frontend/packages/api/client.ts`）：非 `/api/v1/` 路径必须命中 `route-map.json` 的 `routes`，否则本地直接抛 `CAPABILITY_FORBIDDEN`，请求不会发出；`/api/v1/` 路径必须命中 `platform` 白名单。排除路由两条都不命中，因此页面无法调用它们。
- **白名单只覆盖已实现入口**：本轮补齐 `capabilities/ext/read`、`capabilities/mcp/status`、`capabilities/mcp/skill`，其余未实现的目标路径（素材库、单 Agent、节点 AI 生成等）保持不可达，避免"能点但必失败"的入口。
- **桌面能力不迁移**：`lib/clipboard.ts` 用浏览器剪贴板；`useWorkspaceFiles.reveal` 明确抛"Web 平台请使用项目文件下载"；设置面板没有开发者/设备连接/更新入口，`about` 面板只有版本与退出登录。
- **市场只走 Spring**：页面不直连上游市场，搜索/收藏/安装都经后端代理，凭据留在服务端。

## 3. 验证证据

| 层次 | 内容 | 证据 |
| --- | --- | --- |
| 静态审计 | 36 项：25 条逐条不在白名单、无目标路径重定向、后端无前缀、前端无引用、遍历未触发未实现路径、协议入口已覆盖 | [m3-m07-routes.json](evidence/m3-m07-routes.json) |
| 页面遍历 | 12 次遍历、0 失败请求、0 排除命中、0 未捕获错误 | [m3-m07-pages.json](evidence/m3-m07-pages.json) + [设置面板](evidence/m3-m07-settings.png)、[插件市场](evidence/m3-m07-market.png) |

复跑命令：

```bash
python docs/validation/scripts/m3M07RouteAudit.py
HM_BROWSER_ORIGIN=http://localhost:5187 HM_BROWSER_PROJECT=<projectId> \
  node docs/validation/scripts/m3M07PageSweep.mjs
```

遍历使用外部协议夹具（`ExternalProtocolHarness`，容器内 `127.0.0.1:18099`，隧道到 `127.0.0.1:18086`）与 Vite 开发服务器（`HUIMENG_API_TARGET=http://127.0.0.1:18086`）。

## 4. 未覆盖

- **单 Agent 与素材库**：`/singleAgent/*`、`/assets/*` 的映射仍在 `route-map.json`，后端未实现；当前页面不触发，属后续批次。
- **节点 AI 生成与媒体价格**：`/creative/ai/*` 未实现，内置节点包到位后需要一并接通。
- **自定义媒体供应商**：面板入口保留，写入接口显式拒绝（见[M3-08 记录](M3_PLUGIN_MARKET.md)）。
- **真实桌面客户端**：不迁移桌面应用，因此没有覆盖桌面端入口；本结论只对 Web 体验负责。
