# 独立浏览器契约与纯函数

`platform.ts` 从 [OpenAPI](../../../docs/api/openapi.yaml)生成。其他文件为固定 Toonflow 提交中与浏览器共享的纯函数或类型：节点菜单、可读文件类型、Agent/单 Agent 事件和引用数据。来源及原始哈希见[迁入清单](../../../docs/upstream/frontend-intake.json)。这里没有路由、文件系统操作、进程配置或服务初始化。

工具事件的类型引用独立的 tools-scaffold 包；API、契约、scaffold 之间的类型依赖不加载原 Bun 服务。源码版权与许可保留于 [LICENSE.Toonflow](../../LICENSE.Toonflow)。更新平台类型运行仓库根目录的 `python docs/scripts/sync_frontend_contracts.py`，检查运行同命令加 `--check`。
