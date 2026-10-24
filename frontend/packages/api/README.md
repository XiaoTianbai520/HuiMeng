# 统一 Web 客户端

HTTP、Fetch 流、EventSource 与插件脚本 URL 均在这里映射到显式 `/api/v1` 入口。`context.ts` 保存内存中的会话、项目和 CSRF 状态；不保存外部 Bearer 或公司密钥。协议解析器见 `streams.ts`。

`route-map.json` 由 [同步脚本](../../../docs/scripts/sync_frontend_contracts.py)生成，不能手写扩展通配路径。这个前端清单不授予服务端权限；Spring 仍须默认拒绝、逐次授权。

在 `frontend/` 运行 `bun test tests/client.test.ts tests/protocol.test.ts`。调用方显式 AbortSignal 可对已取得 `X-Run-Id` 的运行发送取消请求；断线或项目切换只终止本地传输，不推断服务端已停止或名额已释放。
