# M4 集成、容量、恢复与发布：当前进度

2026-10-27。M4 五项已在真实部署上完成验收，逐项结论与遗留风险见[最终验收报告](M4_SIGNOFF_20261027.md)：M4-01/03 可签收，M4-02 有条件签收（A2A 不参与媒体名额），M4-04/05 附约束可签收。

签收后的试用期加固（`kind` 修正、失败原因透出、文本能力恢复、MCP/A2A 与排队复验）见[试用期加固验收](M4_HARDENING_20261027.md)，当前生产镜像 `huimeng-api:m4h`（Flyway V12）。

## M4-01 端到端回归

**状态：完成（同一部署上的连续回归）**

A01–A14、A17 的逐项证据分散在 M1–M3 的验收记录里（[M2 验收](M2_ACCEPTANCE.md)、[M3-01](M3_PROJECT_FILES.md)、[M3-02](M3_TEXT_GENERATION.md)、[M3-03](M3_MEDIA_GENERATION.md)、[M3-04](M3_AGENT_TOOLS.md)、[M3-05](M3_CAPABILITIES_FFMPEG.md)、[M3-06](M3_EXTERNAL_PROTOCOLS.md)、[M3-07](M3_EXCLUDED_ROUTES.md)、[M3-08](M3_PLUGIN_MARKET.md)、[A16](M3_CAPACITY.md)）。

本轮补上的是「同一套部署上从头到尾走一遍」：登录 → CSRF → 审批模型 → 建 ACTIVE 项目 → 加成员 → 真实生成 → 轮询运行 → 下载产物比对摘要，7 次真实出片全部成功，产物的 SHA-256 与库内记录逐一一致。见[局域网连续回归与并发](M4_INTERNAL_REGRESSION_20261027.md)。

## M4-02 跨入口并发与重启

**状态：有条件完成（真实 5 并发、断线、重启、取消、隔离已过；A2A 不参与媒体名额）**

| 项 | 证据 |
| --- | --- |
| 5 个真实并发、真实 ComfyUI 耗时与失败率 | [本轮实测](M4_INTERNAL_REGRESSION_20261027.md)：5 个全部成功，52.2 / 88.9 / 160.3 / 124.1 / 199.0 秒，总墙钟 236.4 秒；显存最低剩 574 MB，队列峰值 1 执行 + 5 排队 |
| 第 6 个 429 且不触达执行器 | 0.06 秒返回 `PROJECT_CAPACITY_EXCEEDED` |
| 异项目独立计数 | A 项目占满 5 个时，另一项目仍被接受并成功（234.3 秒） |
| 客户端断线 | 读到 64 字节即断开，服务端继续执行并落库 `SUCCEEDED`，无「无产物的成功」 |
| 进程重启 | 生成中途重启容器，该运行收敛为 `LOST`、名额释放，重启后可继续提交 |
| Web×2 + MCP×2 共享同一计数（真实部署） | [最终验收](M4_SIGNOFF_20261027.md)：第 6 个经 MCP 入口 0.04 秒 429 |
| 真实取消 | 202 → `CANCELLED`，10.1 秒收敛，素材数不变、名额释放 |
| A2A 参与名额 | **不适用**：A2A 只跑文本智能体，不调用媒体执行器；当前环境无文本模型，任务 `TASK_STATE_REJECTED` |

本轮还修掉一个只有重启才暴露的缺陷：`LOST` 且上游永远给不出终态时名额会被永久占用；改为「无上游参照的重启当场释放 + 超过 30 分钟兜底释放」，并加 `restartReleasesRunsThatNeverReachedUpstream` 用例。

## M4-03 安全与泄漏检查

**状态：完成（本机自动检查）**

新增 `M4LeakageTests` 3 项：

1. `writtenSecretsNeverAppearInReadsExportsOrAudit` —— 写入供应商密钥与市场密钥后，`/admin/plugins`、`/admin/plugin-market/status`、`/admin/models`、`/admin/configuration/export`、`/admin/providers/media/list` 五个读接口都不含明文；审计表只记动作与目标；`provider_secrets` 里是密文。
2. `issuedClientTokensAreNotReadableAfterCreation` —— 签发返回一次明文，列表只给标识与能力，不含 token 或哈希。
3. `errorResponsesDoNotEchoSubmittedSecrets` —— 用密钥当密码登录失败、市场安装失败，错误响应都不回显提交内容。

叠加既有：非成员 403、跨项目 403、路径穿越拒绝（[M2 验收](M2_ACCEPTANCE.md) 32 项边界）、外部凭证撤销即时 401（[M3-06](M3_EXTERNAL_PROTOCOLS.md)）。

未覆盖：部署层日志文件与反向代理访问日志的抽查，归 M4-05。

## M4-04 备份、恢复与回滚演练

**状态：完成（同 schema 内回滚已实测；跨迁移版本需配套数据库恢复）**

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

灰度与回滚（`A15`）已在真实部署完成：`huimeng-api:m3`（`892cbd2b4872`）↔ `huimeng-api:prev-m3`（`c9d00b4aa1ae`）切换与回滚，启动 4.766 秒，项目/素材/字节数/视频摘要/节点/工具六项指标三次完全一致；备份在 `/data/backup/huimeng/a15-20261027-134920/`。**边界**：两版同处 Flyway V10 才能直接换镜像；跨迁移版本会被 API 的迁移校验拦下，必须先恢复数据库。见[最终验收](M4_SIGNOFF_20261027.md#2-灰度回滚a15)。

## M4-05 发布检查

**状态：可签收（附条件：文本供应商未配置、`kind` 字段与运行失败原因待修）**

发布门槛要求在真实部署上复验 A01–A18。本批已完成：真实图片 7 次、真实 H3 视频 1 次（173.0 秒，608×352、5.17 秒、前端可播放）、5 路 Web/MCP 并发与第 6 个 429、真实取消、断线、进程重启、同 schema 双版本回滚、两个测试项目清理。期间修掉 4 个真实缺陷（外部入口媒体入库外键、失败无日志、视频不可预览、发布脚本先清站点）。

仍需注意：

1. 服务器没有任何文本模型密钥，A2A/Agent 文本能力不可用（环境缺口）；
2. `generation_runs.kind` 对视频仍写 `image`，`RunRespDTO` 不返回失败原因；
3. 跨 Flyway 迁移版本回滚必须配套数据库恢复。

详见[最终验收报告](M4_SIGNOFF_20261027.md)。

## 总结

| 项 | 状态 | 缺口 |
| --- | --- | --- |
| M4-01 | 可签收 | 无 |
| M4-02 | 有条件签收 | A2A 不参与媒体名额（设计如此，需产品确认口径） |
| M4-03 | 可签收 | 仅限自动检查；部署日志抽查已并入 M4-05 |
| M4-04 | 可签收（附约束） | 跨迁移版本回滚需配套数据库恢复 |
| M4-05 | 可签收（附条件） | 文本供应商未配置、`kind` 字段与失败原因待修 |
