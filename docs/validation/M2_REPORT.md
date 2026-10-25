# M2 Spring 安全入口验证

2026-10-25 后续结构整理见[Java 分层与调试报告](M2_JAVA_STRUCTURE.md)：本地 36 项测试和 11 项调试 HTTP 检查通过，尚未部署。以下 10-24 的部署与浏览器记录保留其原版本边界。

2026-10-24。基线 `master / d8020fa`，接手时工作区干净。按 PRD、DESIGN、CONTRACTS、TASKS、ISSUES 和 M1 报告推进。**M2 安全基座和隔离部署已实现，部分验收待完成；M3–M4 未开始，1.0 未验收。** 没有重新执行 M0、恢复旧供应商或调用模型。


本轮界面与登录链收尾已完成，最新结果为 **30 项后端测试、21 项前端测试、15 项本机真实 Spring/H2 浏览器检查、49 项部署 HTTPS 检查**，详见[M2 界面收尾](M2_UI_REPORT.md)。M2-02/08 基础已验；M2-03/04 仍保留跨 M3 的验收条件。下文原 26/18/42 项记录保留为首次 M2 基座验证结果，不能替代最新收尾证据。

## 1. 各项交付与状态

| 任务 | 证据与边界 |
| --- | --- |
| M2-01 完成 | [独立 Maven 模块](../../backend/README.md)、固定 Wrapper/格式/测试、Flyway V1–V3；现有 MySQL 上新库 huimeng 迁移成功，12 表均为 InnoDB、10 个外键。重复迁移仅验证摘要；运行账号不能 DDL，不能枚举其他业务库，迁移历史只读 |
| M2-02 完成（基础） | 首管理员受控文件初始化、PBKDF2、会话/CSRF 轮换、安全 Cookie、限速、退出/停用/改角色/密码撤销均自动验证，真实 HTTPS API 通过。本轮 A01 本机真实 Spring 浏览器检查完成，与部署 HTTPS 证据分开记录；CA 安装可选 |
| M2-03 待验收 | 项目/成员及服务端目录映射已实现；跨项目、管理员非成员、撤销成员、归档、绝对路径/穿越/符号链接拒绝通过。A03/A17 的文件、下载及完整页面随 M3-01 补齐，未声称完整通过 |
| M2-04 待验收 | 供应商/TF-Router Key AES-256-GCM 加密只写、AAD 防串换、获批模型 API；合成 Key 不在查询/审计/日志出现，Qwen 保留 2K。A11 实际模型可用及导出留 M3；没有输入真实团队 Key |
| M2-05 完成（边界层） | Spring 签名器与 [Bun 私有健康接收器](../../backend/executor-boundary/README.md)；20 秒上下文、方法/路径/正文/用户/项目/工作区键及 nonce 绑定。部署中缺失/旧凭证/重放 401、合法签名健康检查 200。没有挂载上游业务 router |
| M2-06 完成 | 管理员签发/列表/撤销；256 位随机凭证、SHA-256 存储、单项目/工具/团队范围、只显示一次；Cookie/Bearer 互不代用；撤销后立即拒绝。真实协议业务留 M3-06 |
| M2-07 完成（M2 范围） | 管理变更事务审计、拒绝审计、固定字段日志、180 天保留及清理；26 项 Spring 集成测试、真实 HTTPS 拒绝与日志抽查通过。M4 仍需全业务泄漏验收 |
| M2-08 完成（部署基础） | [隔离 Compose、Nginx、账号/目录生成器](../../backend/deploy/README.md)已经真实部署；LAN 静态页/API、严格证书校验、非 LAN 403、无 Spring/Bun 宿主机映射通过。本轮已补 15 项本机真实 Spring 浏览器检查，CA 安装可选；没有公网探针实测，不把配置检查冒充外部测量 |

TASKS 保留原完成标准；跨阶段 A 编号没有改成更宽松标准后标完成。M2 完成状态仍以该表和 TASKS 为准。

## 2. 可复现构建与测试

命令见[后端 README](../../backend/README.md)和[前端 README](../../frontend/README.md)，不能在根目录猜测命令。

- 后端 `verify`：**26 测试、0 失败、0 跳过**；真实过滤器、H2 空库迁移/外键、账号/会话、CSRF/Origin、授权/路径、密钥、外部凭证、分页、审计及默认拒绝；Spotless 和可运行 JAR 打包通过。
- Bun：本机 1.4.2 及服务器固定 1.3.14 均 **4 测试、0 失败、16 次断言**；涵盖错误签名、时效、重放和请求/上下文篡改。
- 前端：按现有锁文件安装，正常文件访问权限下 check 与 build 通过，**18 测试、74 次断言**。受限环境的 node_modules 链接读取曾导致错误，使用正常权限后通过；没有修改前端源码。大 chunk 提示延续 M1，不转成未确认的性能门槛。
- 契约/文档：38 操作/127 映射、生成一致性、2 项录制比对及链接检查；23 个平台操作的 x-implemented 根据[实现清单](../../backend/api-implementation.json)更新，15 个保持 false，所有源创作路由 publicEnabled=false。

本机 JDK 21.0.8，编译目标 Java 17、Maven Wrapper 3.9.7（发行包 SHA-512 经官方文件校验后固定 SHA-256）、compiler 3.11.0、Surefire 3.1.2、Spotless 2.43.0。参考 [BaseStructure cf21af88](https://github.com/XiaoTianbai520/BaseStructure/tree/cf21af8805372954bf179bed48e7cdc45bb3abb3) 的分层、Java 目标与构建工具。原 Boot 3.0.7 带入 Flyway 9.5.1，在 MySQL 8.4 给出验证范围告警；依据[官方 Boot 发布](https://spring.io/blog/2025/10/23/spring-boot-3-5-7-available-now/)及[Flyway 兼容记录](https://github.com/flyway/flyway/issues/4168)固定 **Boot 3.5.7、Flyway 11.15.0**，重跑测试及三份迁移摘要校验通过，告警消失。没有引入 Cloud/Nacos 等非必要中间件。

## 3. 真实部署与数据隔离

主机 `192.168.1.102` / `linus-stark-NHx0DB-DE`。先按 MyUbuntu 技能及 DESIGN 只读检查服务、目录、端口、网络与 MySQL；未发现绘梦库/账号/部署目录，18443/18085 空闲。SSH alias 的 MagicDNS 本次不能解析，改用已验证主机密钥的局域网 IP；未修改 SSH、Tailscale、既有服务的端口或数据库。

| 项目 | 实测 |
| --- | --- |
| 应用/静态/工作区 | `/opt/apps/huimeng`、`/var/www/huimeng`、`/data/huimeng/workspaces`；日志 `/var/log/apps/huimeng`、备份目录 `/data/backup/huimeng` |
| 数据库 | 现有 `local/mysql:8.4.11-binlog` 上仅新建 huimeng，utf8mb4_bin/InnoDB；全局时区仍 +08:00，8 个应用 JDBC 连接均 +00:00 |
| 账号 | huimeng_migrate 仅此库 DML/CREATE/ALTER/INDEX/REFERENCES；huimeng_app 仅业务表 DML、迁移历史只读、审计 INSERT/DELETE（保留期清理）不能 UPDATE，无其他库授权；实测 CREATE 返回 1142 |
| 网络 | 新建无冲突 `172.29.46.0/24` 内部数据库网络，现有 MySQL 仅追加连接、不重启；Spring/Bun 在内部网络，无宿主机映射；仅 Web 代理公布 LAN 18443 |
| HTTPS | `https://192.168.1.102:18443/`；独立内部 CA，SAN IP 正确，严格 X.509 验证通过。补齐 critical Key Usage 后 Python 3.14 严格验证通过；服务器证书有效期至 2027-04-22 |
| 来源拒绝 | LAN Windows 192.168.1.100 严格 TLS 校验后静态页 200；代理内 127.0.0.1 来源得到 403。宿主机 3000/18085 拒绝连接；没有公网探针测试 |
| 浏览器 | 内置 Browser 导航返回 ERR_CERT_AUTHORITY_INVALID；没有跳过警告或自动安装系统信任，设备 CA 安装现为可选；实际证书未受信任的观察保留，真实页面验收未完成 |

迁移独立进程只执行新库的 migrate；正式进程只有运行凭据和主密钥、内部密钥，启动仅 validate。首次管理员密码及所有密钥在服务器安全随机生成，不进入命令参数、聊天、代码或报告。公用 CA 的工作机副本只在忽略目录 `.m2-work/ca.crt`；这不是构建或交接必需缓存。首次管理员须通过受控渠道接管并更改密码，然后去除 bootstrap 配置/挂载。

## 4. 真实 HTTPS 检查结果

[脱敏状态记录](evidence/m2-live-smoke.json)由[服务器验证脚本](../../backend/deploy/smoke.py)对最终部署运行，**42 项 PASS**：安全 Cookie、登录/CSRF 轮换、账号和项目、成员过滤/越权/移除、停用、Key 加密/不回显、批准模型、外部凭证一次展示/授权/撤销、团队成员会话不能进入协议、Bun 身份与重放、关闭直连端口、退出和日志/审计检查。

验证后合成账号停用、合成项目归档、测试凭证撤销、合成供应商 secret 删除；没有清除审计或工作区目录，没有模型/市场调用。原业务库不参与迁移和测试。

最终 JAR、容器镜像、数据库与检查数量见[部署元数据](evidence/m2-deployment.json)。本次源码和文档仍为本地未提交改动，没有提交或推送；部署镜像摘要用于区分本次运行制品，不充当 Git 提交号。

合法外部凭证返回 503/EXECUTOR_UNAVAILABLE 表示 M3 协议业务尚未接通；没有把它当 MCP/A2A 成功流。浏览器未信任 CA 和跨 M3 的验收条件已记录为 M2-I02/I03。用户随后取消设备安装内部 CA 的强制要求；这一决策不改变已记录的验证结果，也不自动完成真实页面验收。

## 5. 后续边界

设备 CA 安装已改为可选，不再作为 M2 阻塞项；M2-08/A01 的基础和本机页面检查已补齐，见界面收尾报告；M2-03/04 的全业务 A03/A11/A17 按相应 M3 批次继续。M0-I06/I08/I09/I12–I15 没有被提前关闭。127 条源路由维持 8 frozen、92 proposed、25 excluded、2 internal，全部 publicEnabled=false。

保存修订链、生成五名额/取消、真正创作执行器、FFmpeg 隔离、市场安装、MCP/A2A SDK 分派及业务回归属于 M3；容量/重启、成套备份恢复、最终依赖许可/安全更新及 A01–A18 总验收属于 M4。此次新增的备份目录与停止/恢复说明不等于已经完成备份恢复演练。
