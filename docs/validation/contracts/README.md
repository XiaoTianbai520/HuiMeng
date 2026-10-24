# M1 离线契约回归

此目录是 M1-04 的验证入口。实现与测试分别在 [流解析器](../../../frontend/packages/api/streams.ts)和[协议测试](../../../frontend/tests/protocol.test.ts)，本次使用 Bun 1.4.2；执行器的固定上游基线仍为 Bun 1.3.14。不需要公司凭据、ComfyUI 或运行中的上游实例。

在仓库根目录执行：

```powershell
bun test frontend/tests/protocol.test.ts
python -m unittest discover -s docs/scripts -p test_compare_contracts.py
```

测试直接读取已提交资料范围内的 M0 原始 JSON：现用 Qwen 两模型结果、HTTP 200 内的媒体/Agent 错误、FFmpeg 事件、MCP initialize/tools/list/tools/call，以及 A2A 提交/查询/取消。只在内存中重建 SSE/NDJSON 字节流，不向记录中的历史地址发请求。中文逐字节切分、CRLF/CR 心跳、多行 SSE、截断和终态后多余事件是明确标记的合成传输负例，不宣称来自实际模型。

新入口实现后，同一解析器可读取新入口响应。比对时保留事件顺序、正文 code、文件字节/相对路径及 A2A 状态；仅请求 ID、运行 ID、时间和有记录的脱敏改写可按用例规范化。不得用 HTTP 200、空流或缺终态作为成功。MCP/A2A 不经过平台业务 JSON 包装。

上游与新入口各录制一个 JSON（建议 `{protocol,events,files:[{path,sha256}]}`），使用严格比对器：`python docs/scripts/compare_contracts.py baseline.json candidate.json`。只有经过用例记录的动态字段可逐个加 `--ignore /events/0/file` 等 JSON Pointer；默认不忽略字段。错误、顺序或文件摘要不同返回退出码 1，忽略列表写入输出报告。fixture 采集与比对分离，避免离线检查意外触发供应商。

当前通过只代表离线协议兼容；不代表 M2/M3 权限、名额、执行器取消、文件锁或 A01–A18 已验收。可重复的真实 MCP/A2A 客户端用例继续使用[既有脚本](../scripts/README.md)，切换到新入口时必须使用新签发的合成项目凭证，不能复用原版全局 token。
