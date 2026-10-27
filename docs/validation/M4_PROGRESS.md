# M4 集成、容量、恢复与发布：当前进度

2026-10-27。最新源码已部署到 `192.168.1.102`，模型执行器（工作站 ComfyUI）与对象存储（服务器 MinIO）已接通，M4-01/02 在真实部署上重跑完成；M4-04 的灰度回滚与 M4-05 发布检查仍未签收。

## M4-01 端到端回归

**状态：完成（同一部署上的连续回归）**

A01–A14、A17 的逐项证据分散在 M1–M3 的验收记录里（[M2 验收](M2_ACCEPTANCE.md)、[M3-01](M3_PROJECT_FILES.md)、[M3-02](M3_TEXT_GENERATION.md)、[M3-03](M3_MEDIA_GENERATION.md)、[M3-04](M3_AGENT_TOOLS.md)、[M3-05](M3_CAPABILITIES_FFMPEG.md)、[M3-06](M3_EXTERNAL_PROTOCOLS.md)、[M3-07](M3_EXCLUDED_ROUTES.md)、[M3-08](M3_PLUGIN_MARKET.md)、[A16](M3_CAPACITY.md)）。

本轮补上的是「同一套部署上从头到尾走一遍」：登录 → CSRF → 审批模型 → 建 ACTIVE 项目 → 加成员 → 真实生成 → 轮询运行 → 下载产物比对摘要，7 次真实出片全部成功，产物的 SHA-256 与库内记录逐一一致。见[局域网连续回归与并发](M4_INTERNAL_REGRESSION_20261027.md)。

## M4-02 跨入口并发与重启

**状态：部分（真实 5 并发、断线、进程重启已过；三入口混用仍是夹具证据）**

| 项 | 证据 |
| --- | --- |
| 5 个真实并发、真实 ComfyUI 耗时与失败率 | [本轮实测](M4_INTERNAL_REGRESSION_20261027.md)：5 个全部成功，52.2 / 88.9 / 160.3 / 124.1 / 199.0 秒，总墙钟 236.4 秒；显存最低剩 574 MB，队列峰值 1 执行 + 5 排队 |
| 第 6 个 429 且不触达执行器 | 0.06 秒返回 `PROJECT_CAPACITY_EXCEEDED` |
| 异项目独立计数 | A 项目占满 5 个时，另一项目仍被接受并成功（234.3 秒） |
| 客户端断线 | 读到 64 字节即断开，服务端继续执行并落库 `SUCCEEDED`，无「无产物的成功」 |
| 进程重启 | 生成中途重启容器，该运行收敛为 `LOST`、名额释放，重启后可继续提交 |
| Web×2 + MCP×2 + A2A×1 混用同一计数 | [A16 容量记录](M3_CAPACITY.md)（46 项，夹具入口）——**尚未在真实部署上重跑** |
| 取消释放名额 | 同上（夹具）与 [M3-03](M3_MEDIA_GENERATION.md) 的真实取消 |

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

**状态：未开始（部署已具备，缺灰度回滚与视频生产复跑）**

发布门槛要求 A01–A18 全通过，其中 A15（备份回滚）归 M4-04，A18（断线重启）本轮已过。当前 `huimeng-api:m3`（`892cbd2b4872`）已在 `192.168.1.102` 连续运行，8 个内置节点幂等登记、日志 0 ERROR，图片链路真实可用；`huimeng-api:m2` 与三份历史 JAR 仍留作回滚。

签收前仍缺：

1. 灰度与回滚演练（A15），需要一次双版本切换；
2. H3 视频五模式经服务端的生产出片（库里 `h3Local3060/*` 仍 `enabled=0`，只在执行器侧直连验证过）；
3. Web/MCP/A2A 混用的真实并发（当前只有夹具证据）。

## 总结

| 项 | 状态 | 缺口 |
| --- | --- | --- |
| M4-01 | 完成 | 同一部署上的连续端到端回归已重跑 |
| M4-02 | 部分 | 真实 5 并发/断线/重启已过；三入口混用仍是夹具证据 |
| M4-03 | 完成 | 仅限本机自动检查；部署日志抽查归 M4-05 |
| M4-04 | 部分 | 灰度、回滚、A15；对象备份需在有真实素材后重跑 |
| M4-05 | 未开始 | 灰度回滚、视频生产复跑、三入口真实并发 |
