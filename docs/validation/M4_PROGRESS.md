# M4 集成、容量、恢复与发布：当前进度

2026-10-26。M4 的五项里，能在开发环境独立完成的已经做完；剩下三项都依赖「最新源码部署到局域网」这个前置动作，本机不能替代。

## M4-01 端到端回归

**状态：部分（沿用 M3 的分批证据，尚未在新部署上重跑）**

A01–A14、A17 的逐项证据分散在 M1–M3 的验收记录里（[M2 验收](M2_ACCEPTANCE.md)、[M3-01](M3_PROJECT_FILES.md)、[M3-02](M3_TEXT_GENERATION.md)、[M3-03](M3_MEDIA_GENERATION.md)、[M3-04](M3_AGENT_TOOLS.md)、[M3-05](M3_CAPABILITIES_FFMPEG.md)、[M3-06](M3_EXTERNAL_PROTOCOLS.md)、[M3-07](M3_EXCLUDED_ROUTES.md)、[M3-08](M3_PLUGIN_MARKET.md)、[A16](M3_CAPACITY.md)）。

未做的是「上游样例项目 + 新建项目在**同一套部署**上打开/保存/再打开/导出」的连续回归。它需要把当前 `master` 部署到 `/opt/apps/huimeng`，属于发布动作，等你确认。

## M4-02 跨入口并发与重启

**状态：部分**

| 项 | 证据 |
| --- | --- |
| 同项目 5 个名额、第 6 个 429 且不触达执行器 | [A16 容量记录](M3_CAPACITY.md)（46 项） |
| Web×2 + MCP×2 + A2A×1 混用同一计数 | 同上 |
| 取消释放名额、异项目独立计数 | 同上 |
| 5 个真实用户并发、真实 ComfyUI 耗时与失败率 | 未做（需要真实 ComfyUI 与并发样本） |
| 断线重连、进程重启后任务状态 | 未做（需要部署后的进程级重启） |

## M4-03 安全与泄漏检查

**状态：完成（本机自动检查）**

新增 `M4LeakageTests` 3 项：

1. `writtenSecretsNeverAppearInReadsExportsOrAudit` —— 写入供应商密钥与市场密钥后，`/admin/plugins`、`/admin/plugin-market/status`、`/admin/models`、`/admin/configuration/export`、`/admin/providers/media/list` 五个读接口都不含明文；审计表只记动作与目标；`provider_secrets` 里是密文。
2. `issuedClientTokensAreNotReadableAfterCreation` —— 签发返回一次明文，列表只给标识与能力，不含 token 或哈希。
3. `errorResponsesDoNotEchoSubmittedSecrets` —— 用密钥当密码登录失败、市场安装失败，错误响应都不回显提交内容。

叠加既有：非成员 403、跨项目 403、路径穿越拒绝（[M2 验收](M2_ACCEPTANCE.md) 32 项边界）、外部凭证撤销即时 401（[M3-06](M3_EXTERNAL_PROTOCOLS.md)）。

未覆盖：部署层日志文件与反向代理访问日志的抽查，归 M4-05。

## M4-04 备份、恢复与回滚演练

**状态：部分（备份/恢复已验证，灰度回滚与 A15 待部署）**

演练对象：`huimeng_dev`（MySQL 8.4，容器 `mysql`）+ `huimeng-dev-media`（MinIO，容器 `minio`）。

| 步骤 | 实测 |
| --- | --- |
| `mysqldump --single-transaction --routines --triggers` | 50,907 字节，落在 `/data/backup/huimeng-drill-20261026-220344/db.sql` |
| 恢复到临时库 `huimeng_restore_drill` | 成功；表数 **13/13**、`flyway_schema_history` **4/4** |
| 关键表行数（源/恢复） | users 7/7、projects 11/11、project_members 8/8、audit_events 111/111、external_clients 3/3、project_media 0/0、provider_secrets 0/0 |
| 对象桶备份 | `tar` 打包桶目录 + 逐文件 SHA-256 清单，3/3 文件摘要一致 |
| 对象级恢复 | `mc mirror` 到临时桶，探针对象 `size=33`、`etag=41ef8450234ac808392bd1a882dda9bd` 两侧一致 |
| 清理 | 临时库已删（剩余 0）、临时桶已删、探针对象已删（桶内剩余 0） |

两点必须说明：

- 演练时开发桶里**没有真实媒体对象**（`project_media` 为 0 行），所以对象部分先用一个 33 字节探针对象证明「镜像 → 恢复 → 摘要一致」的链路，不能据此声称「历史素材已可恢复」。等有真实素材后要重跑同一步骤。
- 备份文件保留在服务器 `/data/backup/`，未纳入版本库；恢复用的是临时库，未触碰 `huimeng_dev` 与 `huimeng`。

灰度与回滚（`A15`）需要两个可切换版本的部署，等发布批次。

## M4-05 发布检查

**状态：未开始（被部署阻塞）**

发布门槛要求 A01–A18 全通过，其中 A15（备份回滚）、A18（断线重启）明确归 M4，且必须在新部署上复验。当前 `master` 尚未部署到 `/opt/apps/huimeng`，正式 API/Web/Bun 仍是旧版本，因此不能在发布检查里签「1.0 可上线」。

要推进这一步需要你确认：把当前 `master` 部署到局域网环境（保留旧版本可回滚）。

## 总结

| 项 | 状态 | 缺口 |
| --- | --- | --- |
| M4-01 | 部分 | 同一部署上的连续端到端回归 |
| M4-02 | 部分 | 真实 5 用户并发、断线重连、进程重启 |
| M4-03 | 完成 | 仅限本机自动检查；部署日志抽查归 M4-05 |
| M4-04 | 部分 | 灰度、回滚、A15；对象备份需在有真实素材后重跑 |
| M4-05 | 未开始 | 依赖部署 |
