# Flyway 跨迁移版本回滚演练方案

2026-10-27。本方案只做**方案与判定标准**，尚未执行；M4-04 目前只证明了「跨迁移版本会被 API 的迁移校验拦下」，没有做过「恢复数据库后回滚」的完整演练。

## 1. 为什么需要这份方案

V12 之后生产的迁移版本是 12，而 A15 灰度时用的回滚镜像 `huimeng-api:prev-m3` 只到 V10。直接换镜像会被启动校验拦住（这是保护，不是故障）。要真正回到旧版本，必须同时恢复数据库到对应的迁移版本，这一步涉及生产数据，不能边试边改。

## 2. 前置条件（缺一不可）

| 条件 | 说明 |
| --- | --- |
| 生产库全备 | `mysqldump --single-transaction --routines --triggers` 落到 `/data/backup/huimeng/rollback-drill-<ts>/db.sql` |
| 对象桶备份 | `mc mirror` 到备份桶，并生成逐文件 SHA-256 清单 |
| 演练库 | 独立库 `huimeng_rollback_drill`，**不碰** `huimeng`；演练只在演练库上做 |
| 演练镜像 | 备份 `huimeng-api:m3`（V12）与 `huimeng-api:prev-m3`（V10）两个镜像 ID |
| 记录基线 | 回滚前后比对项目数、素材数、素材字节数、运行数、节点数、工具数六项指标 |

## 3. 执行步骤

### 3.1 备份与基线

1. 记录 `SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE`（预期 12）。
2. 导出全库，记录表数与关键表行数（`users`、`projects`、`project_members`、`audit_events`、`project_media`、`generation_runs`）。
3. 备份桶并核对对象数与摘要清单。

### 3.2 演练库恢复

1. 建空库 `huimeng_rollback_drill`，导入备份。
2. 校验：表数一致、`flyway_schema_history` 版本一致、关键表行数逐表一致。
3. 确认 `generation_runs` 存在 `failure_reason` 列（证明演练库处于 V12 之后状态）。

### 3.3 尝试 A：只换镜像、不恢复库（预期被拦）

1. 用 V10 镜像对演练库启动 API。
2. 预期日志出现 `FlywayValidateException: resolved migration not applied: 12`，进程退出非 0。
3. 这是**防护生效**的证据，记录完整错误行与退出码。

### 3.4 尝试 B：恢复数据库后再换镜像（真正的回滚）

1. 把演练库重建为 V10 状态（从 3.1 的备份导入，或从 V11 时点的备份导入）。
2. 用 V10 镜像启动 API，预期启动成功。
3. 复验：登录、`/api/v1/models`、项目列表、素材列表、运行列表均 200；六项指标与基线一致。
4. 确认 `generation_runs` 里没有 `failure_reason` 列，且旧版本代码不读取它（回滚安全）。

### 3.5 回滚后再升级

1. 用 V12 镜像启动同一演练库。
2. 预期 V12 只执行一次，`failure_reason` 列重新出现，`flyway_schema_history` 回到 12 条。
3. 复验六项指标仍与基线一致（说明回滚往返没有丢数据）。

## 4. 风险与禁忌

- **禁止**在 `huimeng` 库上直接 `DELETE FROM flyway_schema_history` 或 `flyway repair` 来「凑」版本号：V12 的 `ALTER TABLE ... ADD COLUMN` 不是幂等的，列已存在而记录被删会导致下次升级直接失败。
- **禁止**在演练中操作生产库。演练库与生产库同名表结构是刻意的，但连接串必须指向 `huimeng_rollback_drill`。
- 若将来要演练「生产就地回滚」，必须停写并单独预约窗口，本方案不覆盖。
- `failure_reason` 的历史回填只在 V12 首次执行时跑一次；回滚再升级时因为备份里没有该列，会正常重跑，不会重复回填。

## 5. 判定标准

| 项 | 通过条件 |
| --- | --- |
| 备份完整性 | 表数、迁移版本、关键表行数逐项一致 |
| 防护有效性 | 版本不匹配时启动失败，且错误信息指出缺失的迁移号 |
| 回滚可用性 | 恢复库后旧镜像启动成功，六项指标一致，业务读接口 200 |
| 往返一致性 | 再次升级后 V12 只跑一次，指标与基线一致 |
| 数据不丢 | 备份点之后的写入在演练中不参与比对，需在报告中显式说明边界 |

## 6. 未覆盖

- 真实生产就地回滚（需要停服窗口）。
- V12 之前失败视频运行的 `kind` 无法回填（没有产物可反推），回滚演练不改变这一点。
