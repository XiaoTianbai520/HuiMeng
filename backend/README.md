# 绘梦 Spring 安全入口

M2 模块，独立于前端和原 Toonflow。遵守[现行设计](../docs/architecture/DESIGN.md)和[冻结契约](../docs/api/CONTRACTS.md)，创作路由、插件执行与真实 MCP/A2A 分派按 M3 接续；没有通配代理。

## 结构与版本

参考 [BaseStructure cf21af88](https://github.com/XiaoTianbai520/BaseStructure/tree/cf21af8805372954bf179bed48e7cdc45bb3abb3) 的 Maven、Java 17、Spring Boot 3.0.7 参考基线、compiler 3.11.0、Surefire 3.1.2、Spotless 2.43.0 及 controller/service/config 分层。代码按绘梦契约独立实现，未复制其业务示例、响应包装或默认认证；没有引入 Cloud、Nacos、Redis、MyBatis、Knife4j、Lombok。Maven Wrapper 3.3.4 使用 Apache 发行脚本和 Maven 3.9.7；脚本保留 Apache 许可头。现有 MySQL 8.4.11 超出原 Flyway 9.5.1 验证范围，因此实际固定 Spring Boot 3.5.7、Flyway 11.15.0；没有更改业务契约或 Java 17 目标。Spring BOM 固定 Security、JDBC、MySQL Connector 等依赖版本。

`src/main/java/cn/huimeng/` 保存入口、控制器、安全边界和事务服务；`src/main/resources/db/migration/` 保存不可重写的 Flyway SQL。H2 只属于测试依赖，不提供生产运行 profile 或控制台。`executor-boundary/` 是零依赖的 Bun 1.3.14 健康接收器。服务及后续插件的发布依赖许可和安全更新核对仍需 M4 完成，不能因沿用参考版本而视为已发布验收。

2026-10-25 按 java-standards 整理为 `controller → service/impl → dao/mapper`，Service 接口位于 `service/`，持久记录和独立请求/响应模型分别在 `dao/entity/`、`dto/req/`、`dto/resp/`；异常集中在 `common/exception/`。保留显式构造器、现有 JDBC、格式化与冻结 JSON 协议。[结构与调试指南](DEBUGGING.md)提供 IDE Debug main、VS Code 配置、本地 JVM 附加和常用断点。

本机还已配置服务器上的独立 MySQL 开发库 `huimeng_dev`（V3），可从 `backend/` 运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-MySqlDev.ps1` 启动正常应用，或使用 VS Code 的“绘梦：服务器 MySQL 开发调试”。凭据仅在受限的 `.runtime/mysql-dev/`，不会随 Git 交付；详细前端联调与 IDEA 参数见[MySQL 调试说明](DEBUGGING.md#使用服务器-mysql-调试当前工作机已配置)。团队运行库 `huimeng` 未用于本机调试。

当前开发连接已改为使用应用专用 CA 信任库校验的直接 MySQL TLS；IDEA 正常 main 加 local 参数即可，不再预先 ConnectOnly。服务器及系统信任库未改变，实测与证据见[直连修复](../docs/validation/M2_MYSQL_TLS.md)。

M2 收尾补齐 `/api/v1/preferences` 的账号隔离读写，以及 `/api/v1/admin/models` 的管理员模型目录。前者仅接收 ui/general/stores/documentExtensions 对象，最大 32 KiB、嵌套最多 32 层，拒绝混入密钥/令牌/供应商配置；后者只返回登记模型的安全元数据和批准状态，成员仍只能读已批准模型。数据库结构保持 V3，没有为收尾重复迁移已有表。

## 构建与验证

所有下列命令工作目录为 **`backend/`**，要求 JDK 17+；本机实测 JDK 21.0.8。Wrapper 首次需网络，缓存放本目录 `.m2/`。指定版本与仓库设置已纳入 Git，后续同命令可复现。

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd -s .mvn/settings.xml -B -ntp verify
.\mvnw.cmd -s .mvn/settings.xml spotless:apply
```

Linux 使用 `MAVEN_USER_HOME="$PWD/.m2" sh ./mvnw -s .mvn/settings.xml -B -ntp verify`。`verify` 编译、运行真实过滤器/数据库集成测试、打可运行 JAR 并检查格式；`spotless:apply` 仅修复格式。测试密钥和合成账号仅写入忽略的 `target/`。Bun 测试工作目录为 `backend/executor-boundary/`，执行 `bun test`。

## 本机真实浏览器验收

`src/test/java/cn/huimeng/BrowserAcceptanceHarness.java` 仅属于测试源码，不进入运行 JAR，普通 verify 不运行这个长驻工具。它使用独立 H2、真实 Spring 过滤器/控制器及明确合成账号，监听 `127.0.0.1:18086`；不连接服务器业务库，不导入 CA，不关闭 Secure Cookie。

在 `backend/` 设置 `HM_BROWSER_ACCEPTANCE=true` 后执行 `.\mvnw.cmd -s .mvn/settings.xml -B -ntp '-Dtest=BrowserAcceptanceHarness' test`；就绪标记为 `target/browser-harness.ready`。前端在其自身目录设置 `HUIMENG_API_TARGET=http://127.0.0.1:18086`，运行 `bun run dev --port 5187 --strictPort`，浏览器访问 `http://localhost:5187/`。仅测试夹具账号使用源码中标明的公共合成密码，禁止换成团队真实凭据。测试完创建 `target/browser-harness.stop`，工具正常结束；前端临时进程也应停止。最长自动运行 30 分钟。

localhost Cookie 行为依据[浏览器文档](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie)及本轮实际验证；这份回环 HTTP 证据不是局域网 HTTPS 浏览器信任证明。正式配置继续要求 HTTPS。检查范围及截图见[M2 界面收尾报告](../docs/validation/M2_UI_REPORT.md)。

## 数据库与启动

先检查现有主机，不把已有业务库用作测试。新建 **huimeng**、utf8mb4_bin、InnoDB；迁移和运行账号分开，迁移账号仅此库 DML/CREATE/ALTER/INDEX/REFERENCES，运行账号仅此库 DML。主密钥和凭据由部署受控文件注入，不提供内置账号、密码或 Key。

迁移进程需要 `HM_DB_URL`、`HM_MIGRATION_USER`、`HM_MIGRATION_PASSWORD`，运行 `java -jar target/huimeng-api-1.0.0-SNAPSHOT.jar --migrate-only`。没有 baseline/clean 或自动建库。正式进程关闭自动迁移，仅用运行账号校验迁移摘要；迁移凭据不挂载到正式进程。

正式进程需要 `HM_DB_URL`、`HM_DB_USER`、`HM_DB_PASSWORD`、`HM_MASTER_KEY_FILE`、`HM_INTERNAL_KEY_FILE`；后两者为独立随机 32 字节密钥的 Base64 文件。另配置 `HM_PUBLIC_ORIGIN`（同源 HTTPS）、`HM_WORKSPACE_ROOT`、可选监听地址/端口。执行 `java -jar target/huimeng-api-1.0.0-SNAPSHOT.jar`；默认只监听回环。不要把机密填进命令历史，使用受限 env_file 或密钥管理器。配置文件没有真实凭据。

第一次初始化由 `HM_BOOTSTRAP_USERNAME` 和 `HM_BOOTSTRAP_PASSWORD_FILE` 指向受控密码文件，数据库行锁保证只初始化一次；没有公众 setup/register 入口。管理员创建账号；最后一名有效管理员不能被停用/降级。停用、角色或密码变化增加 auth_version，使后续请求的旧会话失效。部署步骤见[deploy/README](deploy/README.md)。

## 安全参数及验证边界

会话 Cookie 为 `__Host-huimeng-session`，Secure/HttpOnly/SameSite=Lax/Path=/；闲置 8 小时、最长 24 小时。所有浏览器写操作含登录要求同源 Origin 和会话 CSRF；登录更换会话与令牌。账号连续失败最多 10 次/15 分钟，来源 IP 最多 100 次/15 分钟；敏感写操作每账号 60 次/分钟。限速状态为单实例内存数据，重启清空；没有在当前 M2 设计中承诺多 Spring 实例会话共享。

项目列表按成员过滤，管理员仅多出元数据管理；创作仍须成员关系。项目/路径服务拒绝绝对路径、父目录、反斜线、盘符/ADS、编码歧义及符号链接。当前没有文件读写控制器，M3 文件执行必须在隔离环境或保持目录句柄的原子 I/O 中使用该授权，不能把单次路径预检当作并发文件系统沙箱。

团队供应商与 TF-Router Key 使用 AES-256-GCM（标识及密钥版本作 AAD）、独立主密钥、随机 nonce；只写不回显。模型目录默认全部未批准，仅管理员可启停已登记模型；Qwen 保留 2K。外部凭证为 256 位随机数、SHA-256 存储、单项目及显式能力范围、逐请求重查并可撤销；团队成员会话不能代用。MCP/A2A 未接通时合法凭证也不会执行创作。

审计仅记录操作者、项目、操作、目标标识、结果、请求 ID 和 UTC 时间；默认 180 天保留，每日清理过期元数据。日志不接受正文、原始路径、查询、认证头、密钥或异常内容，仅记录规范化 requestId、actorId、路由模板、状态与耗时。部署、MySQL 以及真实 HTTPS 验证结果见[M2 报告](../docs/validation/M2_REPORT.md)。
