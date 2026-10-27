# M4-01/02 局域网部署上的连续回归与并发验收

> 2026-10-27。对象：`192.168.1.102` 上的 `huimeng-api:m3`（库 V10）+ 工作站 ComfyUI `192.168.1.100:8188` + 服务器 MinIO `192.168.1.102:18090`。全部为真实执行器、真实对象存储，不使用合成替身。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| 真实图片出片 | 通过：1024×1024 PNG，48.2 秒，1 608 107 字节，下载 sha256 与库内一致，PNG 头 `89504e47` |
| 同项目 5 并发 | 通过：5 个请求全部 200，耗时 52.2 / 88.9 / 160.3 / 124.1 / 199.0 秒，全部 SUCCEEDED，产物摘要逐一核对一致 |
| 第 6 个名额 | 通过：0.06 秒返回 429 `PROJECT_CAPACITY_EXCEEDED`，未触达执行器 |
| 不同项目独立计数 | 通过：另一个项目在 A 项目占满 5 个名额时仍被接受并成功，234.3 秒 |
| 客户端断线 | 通过：客户端读到 64 字节即关闭连接，服务端继续执行并落库为 SUCCEEDED，未误报失败，也没有无产物的「成功」 |
| 进程重启 | 通过：生成中途 `docker restart huimeng-api`，该运行收敛为 `LOST`、`capacity_held=0`，重启后名额为 0 可继续提交 |
| 资源占用 | 记录：并发期间采样 30 次，显存最低剩余 574 MB，ComfyUI 队列最多 1 执行 + 5 排队 |
| 失败率 | 本轮 7 次真实生成 7 次成功（1 次单发 + 5 并发 + 1 断线），0 失败；此前 1 条 FAILED 记录来自接入存储前的 503，非本轮 |

## 2. 部署前置修复（本轮才具备验收条件）

| 问题 | 表现 | 修复 |
| --- | --- | --- |
| API 容器没有出口 | `entry`/`executor`/`database` 全部 `internal: true`，容器无默认路由，连 `192.168.1.100:8188` 与外网都不可达 | `compose.yml` 新增非 internal 的 `egress` 网络，只挂 API，不发布端口 |
| 媒体存储未启用 | 生成准入后被拒：`503 EXECUTOR_UNAVAILABLE`「媒体存储暂不可用」 | MinIO 建生产桶 `huimeng-media`，建仅限该桶的账号，写入 `HUIMENG_STORAGE_*` 六个变量 |
| ComfyUI 只监听回环 | 工作站本机 `127.0.0.1:8188` 正常，`192.168.1.100:8188` 与服务器侧一律「积极拒绝」 | portable 版启动脚本加 `--listen 0.0.0.0` 并重启；该机 Windows 防火墙本就全关，无需加入站规则 |
| 模型未启用 | 全部 `approved_models.enabled=0`，生成前 403 | `PUT /api/v1/admin/models/approval` 启用 `qwenImage21Local/qwenImage21Text` 与 `qwenImage21Edit` |
| 无 ACTIVE 项目 | 8 个历史项目全为 `ARCHIVED`，写操作被状态判定拒绝（403，与成员权限无关） | 新建 ACTIVE 项目并加成员 |

## 3. 连续回归（M4-01）

同一部署上的完整链路：登录 → CSRF → 审批模型 → 建项目 → 加成员 → 生成（`Idempotency-Key`）→ 轮询 `runs/{id}` → `media/{id}/content` 下载比对。

| 项 | 值 |
| --- | --- |
| 端点 | `https://192.168.1.102:18443`（严格 CA 校验，非跳过验证） |
| 模型 | `qwenImage21Local/qwenImage21Text`，`1K 1:1` → 1024×1024 |
| 单发耗时 | 48.2 秒 |
| 产物 | 1 608 107 字节，sha256 `27fedd4aad3b3b9f…`，下载一致 |
| 说明 | 该次为本轮所有并发/断线用例的基线，后续 6 次均由同一入口进入 |

## 4. 并发与名额（M4-02）

```
5 个同项目请求同时发起 → 全部 200
2 秒后第 6 个 → 429 PROJECT_CAPACITY_EXCEEDED（0.06 秒，未到执行器）
另一项目 1 个请求 → 200（独立计数）
总墙钟 236.4 秒
```

| 用例 | 耗时 | 结果 |
| --- | --- | --- |
| A1 | 52.2 秒 | SUCCEEDED，1 797 660 B |
| A2 | 88.9 秒 | SUCCEEDED，1 763 214 B |
| A3 | 160.3 秒 | SUCCEEDED，2 020 411 B |
| A4 | 124.1 秒 | SUCCEEDED，1 714 827 B |
| A5 | 199.0 秒 | SUCCEEDED，2 317 892 B |
| B1（另一项目） | 234.3 秒 | SUCCEEDED |

六个产物全部 `media/{id}/content` 下载成功（原始采样与耗时见 [evidence/m4-concurrency-20261027.json](evidence/m4-concurrency-20261027.json)），sha256 与库内记录一致，PNG 头正确。ComfyUI 侧是串行执行：`running` 恒为 1、`pending` 峰值 5，显存最低剩余 574 MB（12 GB 卡），没有出现 OOM 或执行器 5xx。

## 5. 断线与重启（M4-02）

**断线**：发起生成后只读 64 字节就 `close()`（模拟浏览器/代理断开）。服务端没有中断该任务，最终 `SUCCEEDED` 并落库产物；项目名额在结束后归零。

**重启**：发起生成 10 秒后 `docker restart huimeng-api`。重启后该运行 `state=LOST`、`capacity_held=0`，`upstream_ref` 保留可核查；项目 `capacity_held` 计数回到 0，可以继续提交新任务，未出现「误报成功」。

## 6. 本轮发现并修掉的缺陷

`markLostAfterRestart()` 只把状态改成 `LOST`，**没有释放名额**；而兜底核对只挑 `upstream_ref IS NOT NULL` 的行。于是「重启时还没提交到执行器」的运行（`RESERVED`、无上游参照）会永久占着 5 个名额中的一个；同样地，上游若被重建、`/history` 永远查不到终态，已提交的 `LOST` 行也会一直占名额。

修复（`GenerationRunMapper` + `MediaGenerationServiceImpl`）：

1. 重启收敛时，`upstream_ref` 为空的运行**当场**置 `LOST` 并 `capacity_held=FALSE`、写 `ended_at`；
2. `reconcileLost` 增加兜底：`LOST` 且 `capacity_held=TRUE` 超过 30 分钟的运行直接释放。

新增用例 `restartReleasesRunsThatNeverReachedUpstream`（SecurityIntegrationTests）：插入一条 `RESERVED`/无上游的运行，调用 `recoverAfterRestart()` 后断言 `held=0`、状态 `LOST`。`SecurityIntegrationTests` 62 项全过。

> 后续补充：第 7 节列出的视频缺口已在当日补测（真实 H3 出片 + 前端播放），另外发现并修复了外部入口媒体入库的外键缺陷；结论以[最终验收报告](M4_SIGNOFF_20261027.md)为准。

## 7. 未覆盖

- 视频（H3 五模式）在生产上只做过本机直连执行器的验证，**经服务端的生产出片尚未重跑**；`h3Local3060/*` 在生产库里仍是 `enabled=0`。
- Web/MCP/A2A 三入口混用的真实并发仍沿用 A16 的 46 项证据，本轮只跑了 Web 入口。
- 灰度回滚（M4-04）与发布检查（M4-05）未在本轮签收。
