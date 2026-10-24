# 绘梦独立 Web 前端

M1 模块。保留固定提交的创作界面，接入独立会话/项目边界与单一 API 客户端。源码、许可及适配记录见[引入说明](../docs/upstream/FRONTEND_INTAKE.md)。Spring、执行器及完整业务回归仍由 M2/M3 实现；独立构建不表示平台已可发布。

## 安装、检查与构建

工作目录：`frontend/`。实测 Node 24.11.1、Bun 1.4.2；使用本目录 package.json、bun.lock、bunfig.toml，不能复用上游工作目录或 node_modules。

```powershell
bun install --frozen-lockfile --ignore-scripts
bun run check
bun run build
```

`check` 执行 Web 边界/格式检查、严格 vue-tsc 检查和离线测试。`build` 输出 `dist/`，同时核对实际入包依赖的许可与补充摘要，并生成 `THIRD_PARTY_NOTICES.txt`、`license-inventory.json` 和 `licenses/`。出现大 chunk 提示会如实记录，不把它改造成未确认的性能发布阈值。

## 本地开发

```powershell
bun run dev --port 5186 --strictPort
```

只监听 127.0.0.1；端口被占用时停止。没有默认 Bun/API 代理。接通已实现的 Spring 时，在启动前设置 `HUIMENG_API_TARGET` 为其 HTTP(S) Origin；开发 Origin 的 CSRF 允许策略由 Spring 配置。生产静态文件与 Spring 经同一个局域网 HTTPS 入口发布。未接后端时可以看到登录入口，但不能完成登录或创作。

## 契约与测试

从仓库根目录运行 `python docs/scripts/sync_frontend_contracts.py --check` 检查生成的路由元数据和平台类型，使用[契约工具依赖](../docs/scripts/requirements-contracts.txt)。修改 OpenAPI 后运行不带 `--check` 的同一脚本；不要手写生成文件。

- [统一客户端](packages/api/README.md)：Cookie/CSRF、显式路径、项目切换、流式和取消。
- [独立类型和纯函数](packages/contracts/README.md)：平台 DTO 与原 Web 共享类型，不加载服务模块。
- [离线协议回归](../docs/validation/contracts/README.md)：上游与新入口录制比对、MCP/A2A 用例入口。
- [许可归档](licenses/README.md)：当前产物原文及源码获取说明。

运行中的合成 UI 桩和 `.m1-work/` 不是构建依赖，也不是后端实现。测试仅使用合成身份和已有脱敏记录，不读取公司 Key、不启动模型、不重跑 M0。

## 历史辅助文件

[local-nodes](local-nodes/README.md) 是 M0 旧供应商节点预设及辅助脚本，不参加应用构建和类型检查。现用模型见[当前供应商](../docs/validation/LOCAL_PROVIDERS.md)，不得据旧夹具恢复供应商。
