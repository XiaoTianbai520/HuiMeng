# 1.0 验收用例索引

状态指**M0 原版基线取证**，不是绘梦 1.0 验收结论。验收定义见[迁移矩阵](../product/TOONFLOW_WEB_MIGRATION.md#6-一期验收矩阵)。新平台实现后须重新执行全部 A01–A18。

| 编号 | 1.0 要验证的核心动作 | M0 可复用夹具/证据 | M0 状态 |
| --- | --- | --- | --- |
| A01 | 管理员建停账号、登录退出、限速 | 无，上游无团队账号 | 待 1.0 |
| A02 | 项目成员可访问创作数据，非成员被拒；管理员管理元数据和成员，访问创作内容仍需成员身份；普通成员调用管理员接口被拒 | [样例项目](fixtures/sample-project/) | 待 1.0 |
| A03 | 修改项目 ID、路径、下载链接不能跨项目 | [上游路径响应](evidence/upstream-file-api.json) | 待 1.0 |
| A04 | 打开、保存、重开原格式画布/剧本/素材 | [画布](fixtures/sample-project/画布1.json)、[剧本](fixtures/sample-project/M0-剧本.md)、[素材](fixtures/sample-project/assets/M0-sample.png) | 原版部分已取证 |
| A05 | 双编辑者基于旧版本保存触发 409 | [文档旧 revision 冲突](evidence/upstream-file-api.json) | 原版文档已取证；画布待 1.0 |
| A06 | 上传、复制、重命名、删除、下载及大文件读取 | [素材写读复制](evidence/upstream-asset-api.json)、[浏览器下载](evidence/upstream-browser-export.png) | M0 部分基线已取证；完整重命名/删除/大文件回归转 M3-01/M4，非 M0 遗留阻塞 |
| A07 | 文本模型、SSE、停止与错误 | [DeepSeek 正常、错误与客户端停止](../archive/m0/DEEPSEEK_BASELINE.md)、[早期无模型失败](evidence/upstream-protocol-limits.json) | 原版已取证；上游取消效果未量测；1.0 待验收 |
| A08 | 图/视频/音频进度、预览、落盘 | [当前供应商](LOCAL_PROVIDERS.md)、[现用 Qwen 补测](M0_FINAL.md)、[H3 三模式与 Web 成片](../archive/m0/M0_CLOSEOUT.md#3-新-h3-实际出片) | M0 基线已取证：现用 Qwen 文生/单图编辑 1K 成功、落盘、预览及刷新恢复通过；H3 同哈希可引用三模式证据；完整取消和其他规格转 M3/M4，独立音频不做成功流；1.0 待验收 |
| A09 | Agent 会话、画布工具、取消/恢复、NDJSON | [历史会话/取消/继续](../archive/m0/DEEPSEEK_BASELINE.md)、[页面画布工具](evidence/upstream-ui-text-agent-closeout.json) | M0 核心基线已取证；1.0 待验收 |
| A10 | FFmpeg 合成/转码、进度、取消、下载 | [实际转码/进度/取消/输出下载](evidence/upstream-ffmpeg-closeout.json) | 原版转码基线已通过；目标 Linux 发布环境和完整处理场景待 1.0 |
| A11 | 管理员统一 Key，成员只见获批模型 | 无，上游设置不能替代新权限 | 待 1.0 |
| A12 | 插件市场发现/搜索/收藏、管理员在线及文件安装更新/配置/启停/导出/卸载、成员越权拒绝、凭据保护、离线已安装可用；Agent 实际加载工具 | [路由清单](../api/route-inventory.md)、[节点菜单](evidence/upstream-canvas-node-menu.png)、[新增范围](../product/PLUGIN_MARKET.md) | M0-06 在线/管理/页面/断网及 Agent 工具已取证，见[市场报告](../archive/m0/M0_PLUGIN_MARKET.md)；版本/摘要、节点授权、原构建扩展缺陷待修复；1.0 待验收 |
| A13 | Spring HTTPS MCP/A2A 凭证、项目隔离 | [MCP 实际工具/越界拒绝](evidence/upstream-mcp-closeout.json)、[A2A 提交/查询/取消](evidence/upstream-a2a-closeout.json) | 原协议基线已通过；Spring 身份/项目隔离待 1.0 |
| A14 | 只保留 Web 体验，页面无排除入口与 404 | [原版引导](evidence/upstream-first-page.png)、[路由清单](../api/route-inventory.md) | 待 1.0 清理 |
| A15 | 项目备份恢复和回滚 | [样例项目](fixtures/sample-project/) | 待 1.0 |
| A16 | 每项目 5 并发，Web/MCP/A2A 混用 | [已跑通的本地模型与协议](../archive/m0/M0_CLOSEOUT.md) | M0 只有串行生成；五名额及混用仍待 1.0 |
| A17 | 项目列表不泄漏服务器绝对路径 | [上游泄漏基线](evidence/upstream-file-api.json) | 待 1.0 |
| A18 | 断线/取消/重启任务归属与状态 | 无 | 待 1.0 |

当前结论见[M0 最终收尾](M0_FINAL.md)。原版过程报告位于[归档](../archive/README.md)；每项 1.0 验收结果必须在实现后另附证据。

## M2 新平台证据

[M2 报告](M2_REPORT.md)与[真实 HTTPS 状态结果](evidence/m2-live-smoke.json)覆盖 A01/A02 的账号与元数据/成员权限、A03/A17 的授权/路径服务、A11 的加密只写/模型批准、A13 的凭证及协议前拒绝。26 项 Spring 和 4 项 Bun 测试通过。内部 CA 安装已按用户要求改为可选；A01 真实浏览器检查仍待执行，文件/下载、实际模型、导出、真实协议与任务属于 M3/M4；不将本节记成 A01–A18 全部通过。

[M2 界面收尾](M2_UI_REPORT.md)补齐真实 Spring/H2 的 A01 登录/退出与偏好，以及成员项目过滤、管理路由和目录读取；15 项浏览器检查通过，已部署 API 49 项通过。浏览器中未提交管理写表单，这些写操作由 HTTP 集成/部署验证覆盖；实际模型与文件业务仍按 M3/M4 补齐。
