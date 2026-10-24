# 绘梦 1.0 系统设计（DESIGN）

> 状态：M1 已实现独立前端、统一客户端与契约基线；Spring 安全入口、数据底座及执行器迁移尚未实现。上游核对基线为 Toonflow-app [d2e94aca](https://github.com/HBAI-Ltd/Toonflow-app/commit/d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d)。业务需求见 [PRD](../product/PRD.md)，外部约定见 [CONTRACTS](../api/CONTRACTS.md)，阶段任务见 [TASKS](../plan/TASKS.md)。

## 1. 设计目标与约束

1. 让 Vue Web 独立构建和部署；Spring Boot 是局域网唯一业务入口；原 Bun 服务暂作**内部创作执行器**，复用画布文件、模型、Agent、内置节点及 FFmpeg 行为。
2. 在 Spring 边界完成账号/项目授权、目录映射、模型密钥管控、外部凭证校验、并发名额与必要审计。Bun 不接受来自浏览器或外部客户端自报的用户、目录和全局 token。
3. 创作主流程与上游 Web 基线等价；不改画布、素材和 Agent 会话的原文件格式。新增安全能力时，以契约适配层隔离页面变化。
4. 1.0 单 Bun 实例；无需实时共同编辑、任务队列、对象存储或旧数据批量导入。单实例是上游进程内文件锁及 A2A 任务状态的限制，不能直接横向扩容。

## 2. 组件与部署边界

图源：[系统组件](diagrams/architecture.puml)、[生成请求时序](diagrams/generation-sequence.puml)、[任务状态](diagrams/generation-state.puml)。图中英文标签对应下表。

| 组件 | 职责 | 部署与访问 |
| --- | --- | --- |
| Vue Web | 创作 UI、项目选择、保存冲突和管理员页面；统一 API 适配层 | 静态文件独立部署；仅访问 Spring HTTPS |
| Spring Boot API | 内部账号、RBAC、项目/成员、配置/密钥、外部客户端授权、工作区解析、网关、名额与审计 | 唯一局域网业务入口 /api、/mcp、/a2a；建议 Spring Security、MySQL 与数据库迁移工具 |
| Bun 执行器 | 保留上游文件、模型、Agent、内置能力、FFmpeg 及流式事件 | 仅监听专用内网或本机地址；只接受 Spring 服务身份和服务端项目上下文 |
| MySQL | 用户、项目、成员、外部客户端授权、运行记录、审计及项目目录映射 | 使用现有 MySQL 服务上的独立业务库与最小权限账号；不存大型媒体及原画布文件 |
| 受控工作区 | 项目画布 JSON、素材、Agent 会话、导出物和备份 | 仅 Spring/Bun 服务账号可访问；每项目独立根目录 |
| 模型供应商 | 生成能力与消耗计费 | 凭证由公司统一配置；优先由执行服务在受控网络出站访问 |

边界顺序：局域网浏览器或获授权客户端 → HTTPS/Spring → 内部鉴权通道/Bun → 项目工作区或供应商。不得将平台入口发布到公网，也不得将 Bun 端口、工作区目录或原上游全局 MCP/A2A token 暴露给客户端。入口的允许路径采用显式清单；[迁移清单](../product/TOONFLOW_WEB_MIGRATION.md#3-基于代码路由的迁移清单)中被排除的 25 条路由不得被通配代理放行。

## 3. 模块划分

| Spring 模块 | 主要责任 | 边界输入/输出 |
| --- | --- | --- |
| Identity | 内部账号、密码哈希、登录/退出/停用、会话、登录限速 | 浏览器安全 Cookie；不向 Bun 传递原密码 |
| Project | 项目、成员、项目 ID 到工作区根目录映射 | 普通成员只能枚举和操作获授权项目 |
| Authorization | 角色、路由能力清单、外部客户端的项目/工具/团队范围 | 默认拒绝；每次请求重新校验 |
| Configuration | 供应商密钥、获批模型、内置能力配置 | 密钥加密存储、只写不回显；成员只读可用模型 |
| Creative Gateway | 上游兼容路由映射、内容和路径校验、流式透传、取消 | 向 Bun 发受信任的 projectId/工作区上下文 |
| External Gateway | MCP/A2A 协议桥接、调用方凭证、页面控制连接/任务归属 | 原协议兼容；不同客户端隔离 |
| Run Admission | 每项目 5 个活跃名额、跨入口原子抢占与释放、项目外单 Agent 限流 | 占位成功才调用 Bun；执行状态可恢复核查 |
| Audit/Observability | 高权限变更、访问拒绝、运行状态、requestId | 不记录密钥、凭证原文和素材正文 |

## 4. 核心数据模型

以下是**建议的最小数据模型**；字段与索引在建表迁移时冻结。使用现有 MySQL 的独立数据库，表采用支持事务和行锁的 InnoDB；主键建议 UUID，时间统一 UTC 保存，API 用 ISO 8601。实体关系见表，物理 ER 图可在字段冻结后补充 PlantUML 源码。

| 实体 | 关键字段 | 约束 |
| --- | --- | --- |
| users | id, username, password_hash, status, platform_role, created_at | username 唯一；role 为 ADMIN 或 USER |
| projects | id, name, workspace_key, status, created_by, created_at | workspace_key 由服务端生成且唯一；不能由客户端传物理目录 |
| project_members | project_id, user_id, joined_at | 复合唯一；只有成员或管理员的明确管理操作可访问 |
| provider_secrets | provider_id, encrypted_value, key_version, updated_by, updated_at | 不回显 encrypted_value；加密主密钥不入库 |
| approved_models | provider_id, model_id, enabled, capabilities | 项目成员只能看到 enabled 模型 |
| external_clients | id, name, secret_hash, status, created_by, created_at, revoked_at | 凭证原文只在签发时返回一次；哈希查找与验证 |
| external_grants | client_id, project_id, capability_type, capability_name | 限定 MCP 工具、A2A 团队；默认无授权 |
| generation_runs | id, project_id, actor_type, actor_id, kind, state, upstream_ref, started_at, ended_at, lease_expires_at | 活跃任务占名额；记录外部调用方与归属 |
| audit_events | id, actor_type, actor_id, project_id, action, target, result, request_id, created_at | 只存必要元数据，设保留期 |

文件元数据可逐步加入数据库；1.0 以项目工作区为原文件事实来源。用户与项目、项目与外部授权是多对多关系；生成运行只归属一个项目，项目外单 Agent 按账号单独限流。

## 5. 身份、授权与目录安全

### 5.1 内部用户

- Spring Security 管理账号会话；密码用适当的自适应哈希；Cookie 设置 HttpOnly、Secure、SameSite，变更类请求验证 CSRF；登录和敏感操作限速。
- 平台管理员可管理账号、成员、供应商和外部凭证。创作项目数据的访问仍需项目成员身份，管理员可通过成员管理将自己加入项目并留审计；1.0 不新增应急审批流程。普通用户只可读写其项目。管理配置接口没有“管理员页面隐藏即可”的例外。
- 所有项目请求从已认证身份取得项目 ID，再用数据库查成员关系；工作区路径由服务器映射。目录允许范围要做规范化和符号链接处理，拦截绝对路径、父目录穿越、跨项目文件 ID 与下载链接绕过。

### 5.2 Bun 内部身份

Spring→Bun 使用短期服务身份或 mTLS，并传经签名/受控通道的用户标识、项目 ID、请求 ID 与工作区上下文；Bun 拒绝缺失或伪造的内部身份。配置、防火墙和部署检查同时确保浏览器不能直连 Bun。旧路由参数 `directory` 由适配层生成或在 Bun 内据 projectId 解析，浏览器值一律忽略并拒绝。

### 5.3 外部 MCP/A2A

管理员签发独立凭证并绑定客户端、项目及能力；请求凭 Bearer 凭证进入 Spring，按能力清单和项目校验。每个外部凭证在 1.0 建议只绑定**一个项目**，避免上游协议没有统一 projectId 字段时产生歧义。MCP 页面控制会话按 clientId、projectId、连接 ID 隔离；A2A 创建、查询、取消按 clientId、projectId、任务 ID 绑定。撤销使新请求立即失效。协议握手、流式返回和任务标识须以 M0 录制的上游样例为兼容基线。

## 6. 创作兼容与并发写入

前端保留原页面和行为，通过单一 API 适配层把项目 ID 送到 Spring；公共 DTO 和流式事件类型来自 [CONTRACTS](../api/CONTRACTS.md) 或 OpenAPI 生成，不再从 `@toonflow/server` 导入运行代码。对上游路由逐条建台账：原方法/路径、调用页面、新入口、权限、请求/响应样例、流式类型和迁移状态。无调用点的管理路由不自动开放。

画布和剧本读取返回**不透明修订号**，保存使用 If-Match 或明确的 revision 字段。上游文档编辑已用 `snapshot=true` 读取 SHA-256 修订号并在写入时传 `expectedRevision`；上游画布自动保存只顺序化同页请求，未传 `expectedRevision`。因此 1.0 要给画布读取和自动保存补齐修订号链路。服务端在同一文件锁下比较修订号并原子写入；不匹配返回 409，前端暂停该画布自动覆盖并提示重新读取后处理。修订号不改变原文件正文格式。并发写入只保护保存一致性，不建立实时同步。详见[固定提交 Web 功能基线](../validation/WEB_BASELINE.md)。

## 7. 生成任务与限额

项目内图片、视频、音频和一次 Agent 运行占一个名额，Web、MCP、A2A 共用同一计数。Agent 内部的媒体调用使用父运行 ID，不再申请名额。单独文本请求只做请求限速；项目外单 Agent 按账号限流。第 6 个任务立即返回 429 和 `PROJECT_CAPACITY_EXCEEDED`，**不得先启动 Bun 或供应商调用**。

实现上在 MySQL/InnoDB 事务中锁定项目并统计/登记最多 5 个活跃运行，再转发给 Bun；请求幂等键阻止网络重试重复占位。运行状态建议 `RESERVED → RUNNING → SUCCEEDED/FAILED/CANCELLED/LOST`。服务端确认实际停止后才能从 `CANCELLING` 释放；客户端断线时若 Bun 仍执行，名额继续占用。Spring/Bun 重启后对活跃记录进行核对，无法确认的任务标为 `LOST`，并保守处理限额直至确认执行已停止；不可只靠租约过期就允许新的供应商调用。1.0 不做队列。数据库单项目行锁是跨 Spring 实例原子性的基础，虽初期只需一个 Bun 实例。

## 8. 流式、文件与错误处理

| 类型 | 设计要求 |
| --- | --- |
| 文本 AI | 原 SSE 帧、顺序与结束语义透传；Spring/反向代理关闭不必要缓冲；设置心跳和合理读超时 |
| Agent | 原 NDJSON 按行转发，不合并为普通 JSON；对问答、画布操作与结束事件保留顺序 |
| 媒体生成 | 保留提交、进度、保活、取消及结果落盘语义；运行 ID 与项目绑定 |
| FFmpeg | 进度与取消独立处理；下载先授权再由 Spring 转发或签发短期受控链接 |
| 大文件 | 流式上传/下载；校验大小、类型与项目范围，临时文件失败后清理 |
| 错误 | 网关自己的错误使用 [CONTRACTS](../api/CONTRACTS.md) 统一结构；上游成功/流式事件尽量兼容原协议 |

### 8.1 插件市场迁移

Spring 提供管理员市场查询/收藏代理和插件管理入口，后端代管 TF-Router 凭据；Bun 复用原版安装器和插件运行接口。市场目录、下载安装源分开校验：仅允许获批来源和逐跳重定向，检查 DNS/地址、防 SSRF、限时限量、文件类型、归档路径、元数据及版本冲突。M0-06 已发现市场版本与包内不一致，且 fileHash 算法未明；下载必须与算法明确的制品清单核验，不直接把目录字符串当作可信签名。外部描述作为资料处理。JS 工具插件是可信服务端代码，原 Bun 插件机制不构成项目沙箱；发布前须审查插件的文件/网络访问并限制执行环境，不能仅靠界面隐藏权限。

插件按平台全局安装，记录类型、标识、版本、来源、摘要、启停状态和操作者；配置密钥不进入成员 DTO 或导出物。更新和卸载检查运行占用，失败保留可用版本；用户确认后才强制覆盖或降级。成员读取获批能力清单，管理员修改通过独立管理 API。市场离线仍可加载已安装能力并使用本地安装。首次部署按发布清单安装画布、媒体生成及依赖工具，并通过实际 Agent 查询与画布调用检查，避免供应商存在但工具为空。完整范围见[插件市场](../product/PLUGIN_MARKET.md)，接口见[CONTRACTS §2.4](../api/CONTRACTS.md#24-插件市场与管理)。

## 9. 配置、审计与运维

供应商 Key 由管理员写入加密配置，密钥主密钥从部署密钥系统注入；API、日志、导出和错误中不得出现明文。插件市场和受控远程安装纳入 1.0；内置及第三方节点、工具、技能、扩展可用性由管理员批准。执行器限制工作区、网络、资源与超时；供应商出站目标采用配置白名单。FFmpeg 的原始参数、滤镜和清单可能间接访问文件，不能把显式路径检查当作进程沙箱：成员仅使用获批处理模板/参数白名单，或在具备项目文件系统隔离的进程环境执行；不直接透传任意 fluent 调用。

日志包含 requestId、actorId、projectId、route、status、duration、runId；高权限变更记录前后元数据但不记录密钥值。监控至少覆盖 5 名额占用、生成失败、Bun 连接、SSE/NDJSON 断线、磁盘容量、备份结果和外部凭证拒绝。定义项目工作区及数据库一致性备份、恢复演练与上游固定版本回滚步骤；1.0 无存量业务项目导入。

## 10. 局域网部署与测试环境

根据用户提供的《Ubuntu服务器规范.md》，服务器记录的局域网地址为 `192.168.1.102`，已有 MySQL、Redis、Jenkins 等服务；该文档是部署参考；M0 已在现有服务器完成隔离构建与编译产物启动，正式部署前仍须核实当时的服务、目录与端口。1.0 沿用目录约定：应用 Compose 与配置放 `/opt/apps/huimeng/`，前端静态文件放 `/var/www/huimeng/`，项目工作区放 `/data/huimeng/workspaces/`，备份放 `/data/backup/huimeng/`，日志放 `/var/log/apps/huimeng/`。不覆盖现有服务目录、数据库和端口。

只在局域网地址发布一个 HTTPS 入口，统一承载 Web、`/api`、`/mcp`、`/a2a`；部署前检查实际监听端口并选择空闲端口，配置受公司设备信任的证书或内部 CA。反向代理与 Spring、Spring 与 Bun 走受控内部连接，Bun 不映射宿主机公开端口；Spring 使用独立低权限账号连接现有 MySQL，不改动该服务已有端口。现有 Redis 可留作后续评估，1.0 的五名额用 MySQL 事务实现，不为迁移目的强制接入 RocketMQ、Nacos、XXL-JOB 或其他中间件。

本机现用媒体供应商为 h3Local3060 2.0.0（5 模型）和 qwenImage21Local 1.0.0（2 模型），版本和源码摘要见[当前清单](../validation/LOCAL_PROVIDERS.md)。现用 Qwen 文生/单图编辑的 1K Web 生成、落盘与刷新预览已[独立验证](../validation/M0_FINAL.md)，新平台取消与其他规格继续回归；不得沿用已退役 localQwenImage21 的证据。模型切换后须核对保留规格，避免原版节点保留 2K 覆盖供应商推荐的 1K。供应商与地址继续作为配置管理，不硬编码进平台业务。

首轮生成回归与并发测试使用用户已有的本地 ComfyUI。本机测试的 ComfyUI 地址、模型和已验证工作流见[现用供应商](../validation/LOCAL_PROVIDERS.md)，Ubuntu 执行器到 Windows ComfyUI 的网络连接尚需在部署时确认，不能把 localhost 当作跨机地址。按已确认范围测图片/视频；独立音频只检查路由、默认配置、权限和名额，不补音频成功流。其他保留适配器可用可控响应检查契约，不要求远程媒体调用。记录设备、模型、并发、耗时、失败和资源占用。性能只记录实测基线与明显故障，不先写死响应耗时和错误率数字。

## 11. 交付顺序与关键决策

按 [TASKS](../plan/TASKS.md) 的 M0–M4 实施：先固定上游与录制契约，再解除前端依赖，建立 Spring 安全入口，分批迁移创作路由，最后做全链路验收、备份和回滚。每一批只有在对应回归通过后才扩大流量。

| 决策 | 理由 | 重新评估条件 |
| --- | --- | --- |
| Spring 对外、Bun 内部 | 快速保留原模型/Agent/FFmpeg 行为，先解决公司权限边界 | 上游执行逻辑稳定、契约覆盖充分后可逐步替换 |
| 单 Bun 实例 | 原文件锁和 A2A 任务为进程内状态 | 多实例前引入分布式协调、任务持久化与回归 |
| 文件原格式与受控工作区 | 降低迁移风险 | 对象存储或大规模并发需求明确后 |
| 不做实时共同编辑 | 业务已确认 | 新版本另行立项 |

## 12. 实施时确定的参数

部署范围已定为公司局域网。身份会话超时、凭证保留期、审计保留期、文件大小限制、HTTPS 监听端口和 ComfyUI 测试工作流在实现或测试时记录为配置；响应耗时与错误率先记录实测值，不预设发布阈值。
