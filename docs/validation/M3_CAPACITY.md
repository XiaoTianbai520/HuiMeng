# M3-06 / A16：Web、MCP、A2A 共用每项目五个名额

本记录对应验收项 A16「每项目 5 并发，Web/MCP/A2A 混用」。目标不是再测一次单入口限额，而是确认三个入口看到的是同一份项目级名额，并且在拒绝、取消、独立项目计数与完成后释放上都一致。

## 1. 夹具

`backend/src/test/java/cn/huimeng/CapacityHarness.java`（`HM_CAPACITY=true` 才启动，普通 `verify` 不会加载）：

- **真实 Spring HTTP 入口**：H2 + Flyway、真实安全链、真实 `MediaGenerationServiceImpl` 与名额事务；只把对象存储换成进程内实现。
- **合成 ComfyUI**：进程内 `HttpServer`，`/system_stats`、`/prompt`、`/history/{id}`、`/view`、`/api/jobs/{id}[/cancel]`。每张图固定延迟 `HM_CAPACITY_DELAY_MS` 毫秒（本轮 25 秒）后才完成，足够观察并发占位。
- **合成文本供应商**：OpenAI 兼容 SSE；首轮返回一次 `generateImage` 工具调用，收到 `tool` 消息后返回文本，用于驱动 A2A 任务里的 Agent 内部生图。
- **数据**：两个项目、两个 Web 账号（admin/member）、两条外部凭证（alpha 有 `generateMedia/getRun/cancelRun/listMedia/listTeams` 与 `capacityTeam`，beta 只有生成类工具）；`qwenImage21Text` 与 `deepseek-chat` 已批准。
- 工作区安全原语只支持 Linux，夹具在容器内运行（`maven:3.9.11-eclipse-temurin-21`，`--network host`）。

## 2. 结果：46 项通过

| 分组 | 断言 |
| --- | --- |
| 入口准备 | 两个 Web 会话独立登录；MCP 握手成功且 `generateMedia`/`cancelRun` 出现在工具目录 |
| 五名额混合占用 | Web（admin）1 个 + Web（member）1 个 + MCP 2 个 + A2A 内部生图 1 个；执行器恰好收到 5 次提交 |
| 第六个拒绝 | HTTP 429、`PROJECT_CAPACITY_EXCEEDED`，且执行器提交数不变（拒绝未触达供应商） |
| 取消释放 | `cancelRun` 后运行到终态且 `capacityHeld=false`，新生成随即准入；补位运行取消成功 |
| 项目独立 | alpha 满额时 beta 凭证仍可准入并取消 |
| 完成释放 | 两个 Web 运行 `SUCCEEDED`、`capacityHeld=false`；A2A 任务 `TASK_STATE_COMPLETED` 且有 artifact |
| A2A 产物 | 项目素材出现 `generated-*.png`，说明 A2A 的图片确实落在绑定项目 |
| 执行器账目 | 提交 7（5 占用 + 1 补位 + 1 独立项目）、完成 4、取消 3、结束时无活跃任务 |

时间线（脚本时间戳）：1.3 秒两路 Web 准入 → 1.6 秒 MCP 两路准入 → 1.7 秒 A2A 任务提交 → 1.9 秒第五次执行器提交与第六次拒绝 → 2.5 秒取消释放 → 2.6 秒另一项目准入。之后等待 Web 与 A2A 完成并核对产物。

证据：`docs/validation/evidence/m3-m16-capacity.json`（46 条断言与执行器统计）；脚本 `docs/validation/scripts/m3M16Capacity.py`。

## 3. 过程中发现并修复的缺陷

首次运行时 A2A 任务在生图成功后失败，错误事件为「请先登录」。原因是 `MediaGenerationServiceImpl` 的图片/视频执行在 `finally` 里直接 `SecurityContextHolder.clearContext()`：Agent 工具调用跑在 A2A 工作线程上，内部生图结束后把该线程的登录态清空，Agent 回合末尾保存会话时 `RequestContext.actor()` 为空。

修复：执行前保存当前 `Authentication` 与 `ExternalCallContext`，结束时按原值还原（原来为空才清除）。修复后 A2A 任务能正常完成、写回文本结果并保留生成产物；全量后端 121 项测试与 Spotless 通过。这个缺陷只在「Agent 工具调用 + 同线程后续还要写会话」的组合下出现，是跨入口验收特有的覆盖。

## 4. 复现

```bash
# 容器内（工作区安全原语要求 Linux）；本机可用同一镜像
HM_CAPACITY=true HM_CAPACITY_DELAY_MS=25000 \
  sh ./mvnw -s .mvn/settings.xml -B -ntp -Dtest=CapacityHarness -Dhm.capacity.port=18095 test

# 另开终端
M316_PORT=18095 \
M316_READY=backend/target/capacity.ready \
M316_EVIDENCE=docs/validation/evidence/m3-m16-capacity.json \
python3 docs/validation/scripts/m3M16Capacity.py
```

夹具写 `target/capacity.ready` 后等待 `target/capacity.stop`，默认两小时超时。

## 5. 边界

- 本轮验证的是**协议与名额语义**：三个入口、同一项目、同一份计数。五人同时操作、断网重连、进程重启后的名额收敛与设备资源曲线属于 M4-02，仍未做。
- 合成执行器只模拟 ComfyUI 的提交/轮询/取消契约，不代表真实出图耗时；真实出图已在 M3-03/M3-05 覆盖。
- 夹具不校验图片内容，只校验 PNG 字节写入项目素材与命名规则。
