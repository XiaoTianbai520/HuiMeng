# 后端结构与本地调试

以下命令的工作目录均为 `backend/`。使用 JDK 17+，先在 IDE 中导入本目录的 `pom.xml`，等待 Maven 索引完成。

## 分层与规范适配

| 位置 | 职责 |
| --- | --- |
| `controller/` | 参数绑定、Bean Validation、状态码、调用 Service 接口；不写 SQL，不返回 DO |
| `service/` | 业务接口，包含中文职责与方法说明 |
| `service/impl/` | 身份校验、业务编排、DTO/DO 转换和事务；需要多步提交的方法在这里声明事务 |
| `dao/mapper/` | 固定参数化 SQL、行映射和游标查询；使用现有 Spring JDBC |
| `dao/entity/` | `XxxDO` 持久化记录；账号认证数据和工作区映射不作为响应发送 |
| `dto/req/`、`dto/resp/` | 独立命名的 `XxxReqDTO`、`XxxRespDTO`；保持现有 JSON 字段、时间和 ID 类型 |
| `common/exception/` | `ApiException` 和 `GlobalExceptionHandler`；统一转换安全错误响应 |
| `security/` | 可信账号/客户端身份、会话过滤、限速、加密及请求上下文 |
| `config/` | Spring Bean、入口安全链、初始化、迁移校验与调度；不承载业务 SQL |

按 java-standards 适配绘梦：保留 `cn.huimeng`、Java 17、Spring Boot 3.5.7、JDBC、显式构造器注入及 Google Java Format。暂不引入 MyBatis-Plus、Lombok、Redis 或新的响应包装。接口文档继续由仓库 OpenAPI 维护；没有为添加 Swagger 注解增加文档服务。成功响应仍是原来的对象/列表或 204，错误仍含 code/message/requestId/details，分页仍是 items/nextCursor。

持久层的 `CursorMapper.query` 仅包内可见，表/列/过滤表达式来自 Mapper 内的固定代码；用户数据通过参数绑定。偏好的动态 JSON、模型 capabilities/options 和尚未接通的协议消息按原契约保留动态类型，不额外套静态结构。生产 Flyway SQL V1–V3 未重写。

## 使用服务器 MySQL 调试（当前工作机已配置）

2026-10-25 已建立独立 `huimeng_dev` 开发库并完成 V1–V3。**当前使用直接 MySQL TLS 连接，IDEA 运行不再依赖 SSH 隧道或 ConnectOnly。** 运行账号只限当前工作机、强制 TLS，无 DDL，不能修改审计/迁移历史或访问团队运行库。开发库与团队运行库 `huimeng` 保持隔离。

**IDEA** 选择“绘梦 MySQL 本地”共享配置即可 Run/Debug：[仓库根配置](../.run/HuiMengMySql.run.xml)、[独立 backend 项目配置](.run/HuiMengMySql.run.xml)。已有临时运行配置不会自动修改；若沿用它，确保：

- Main class：`cn.huimeng.HuiMengApplication`。
- Working directory：本仓库的 `backend/`。
- **Program arguments**：`--spring.profiles.active=local`。

无需手动执行数据库连接脚本。共享配置字段对应[IDEA 官方说明](https://www.jetbrains.com/help/idea/run-debug-configuration-java-application.html)。VS Code 仍选择“绘梦：服务器 MySQL 开发调试”，已移除隧道前后置任务。

命令行工作目录 `backend/`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-MySqlDev.ps1
```

脚本只运行正常应用的 local profile；应用监听 `127.0.0.1:18085`。启动只校验迁移摘要，不自动建库或执行 DDL。`application-local.yml` 仅在显式选择 local 时导入 `.runtime/mysql-dev/application.properties`；缺失文件则明确失败，不提供默认凭据，不影响 test 或部署入口。

受限文件中 JDBC 使用 `sslMode=VERIFY_CA`，配置 Hikari 的应用专用 PKCS12 信任库。公用 MySQL CA 经已校验的 SSH 主机通道取得，服务端私钥没有复制；只信任此 MySQL CA，没有安装系统 CA 或关闭证书校验。实测会话协商 TLS，错误 CA 握手失败，明文连接被账号策略拒绝。服务器地址、当前工作机 IP 与信任库仅在本机受限配置中维护；网络或证书变化后重新核实，不放宽为任意来源。

首次开发管理员为 `dev-admin`，初始密码在本机 `.runtime/mysql-dev/bootstrap-password` 受限文件中，自行在编辑器查看，不复制到聊天或文档。数据在真实开发库持久保存；测试账号已停用、项目归档、凭证撤销。配置、密钥和工作区均在忽略目录，权限仅当前用户/SYSTEM，不随 Git 交付。

前端在 `frontend/` 启动：

```powershell
$env:HUIMENG_API_TARGET = 'http://127.0.0.1:18085'
bun run dev --port 5187 --strictPort
```

访问 `http://localhost:5187/`。Secure Cookie/CSRF 保留，localhost 页面不等于生产 HTTPS 浏览器信任验收。

如果错误为 `'url' must start with "jdbc"`，检查是否选中 local 及工作目录；如果为连接失败，先检查开发服务器可达性、证书和来源账号授权。当前直连配置无需排查或启动旧隧道。

迁移仍为独立、显式的操作：保留的 `migration.env` 和 [Connect-DevDatabaseTunnel.ps1](scripts/Connect-DevDatabaseTunnel.ps1) 只供受控迁移通道使用，不由 main 自动执行。先核实版本、目标库与备份，再按后端 README 的迁移流程处理；不得把迁移账号装入正常应用。

## 独立内存库调试

打开[本地调试入口](src/test/java/cn/huimeng/debug/LocalDebugApplication.java)，用 **Debug main**：

- Main class：`cn.huimeng.debug.LocalDebugApplication`。
- Working directory：本仓库的 `backend/`。
- Program arguments：`--local-debug`。
- Classpath：本模块的 **test** 类路径，包含测试资源与 H2；IDEA 的普通测试源码 main 配置可使用此类路径。

VS Code 打开仓库根目录后，可选择[启动配置](../.vscode/launch.json)中的“绘梦：本地隔离调试”再按 F5；需要安装 Java 调试扩展。配置使用[官方 Java 调试器](https://code.visualstudio.com/docs/java/java-debugging)的 `$Test` 类路径及本模块 artifactId。

此入口显式固定：

- Spring 只监听 `127.0.0.1:18086`；浏览器 Origin 为 `http://localhost:5187`。
- 数据源与迁移均是独立内存 H2，重启重建，不读取终端继承的 HM_DB_* 作为数据库配置。
- 密钥与工作区在忽略的 `target/local-debug-secrets/`、`target/local-debug-workspaces/`，与集成测试临时文件分开。
- 初始化三个**公开合成账号**：`debug-admin`、`debug-member`、`debug-outsider`；公共夹具密码见入口类的 `SYNTHETIC_PASSWORD`。只能用于本地隔离环境，不能用团队真实账号或 Key 替换。
- 管理员和成员加入第一个合成项目，第二个项目未授予成员；可调试允许与拒绝两条链路。
- Cookie 的 Secure/HttpOnly/SameSite 和 CSRF 校验沿用应用配置，未关闭鉴权或开放创作路由。浏览器必须从 `localhost` 打开前端。

命令行启动同一工具：

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd -s .mvn/settings.xml -B -ntp test-compile spring-boot:test-run '-Dspring-boot.run.main-class=cn.huimeng.debug.LocalDebugApplication' '-Dspring-boot.run.arguments=--local-debug'
```

如需从 IDE 附加命令行 JVM，在上述命令末尾追加：

```powershell
'-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005'
```

随后选择“绘梦：附加本地 JVM 5005”，或在 IDEA 建立同地址的 Remote JVM Debug。`suspend=y` 会等待调试器连接后才启动 Spring；5005 只监听回环，不改服务器 Compose 或开放远程调试端口。

启动前核实 18086/5187 未被其他任务使用。需要前端联调时另开终端，工作目录 **`frontend/`**：

```powershell
$env:HUIMENG_API_TARGET = 'http://127.0.0.1:18086'
bun run dev --port 5187 --strictPort
```

浏览器访问 `http://localhost:5187/`。结束时 Stop 调试进程或 Ctrl+C，并停止临时前端。内存库随进程销毁，生成文件受 Git 忽略；这不是服务器 MySQL/HTTPS 部署验收。原来的限时[浏览器验收工具](src/test/java/cn/huimeng/BrowserAcceptanceHarness.java)仍独立保留，端口相同，不能同时启动。

## 常用断点

MinIO 媒体已接通当前工作机的 local 配置，原 main/工作目录/调试参数不变。可从 `ProjectMediaController` → `ProjectMediaServiceImpl` → `ProjectMediaMapper` / `S3ObjectStorage` 跟踪上传、授权、元数据、Range 和删除；请勿在日志或共享截图展开 S3Client、凭据 provider 或受限 Properties。原工作区文档的 Windows 503 与独立媒体 API 分别处理，详见[配置和接口](README.md#私有-minio-媒体存储2026-10-25-增补)。

| 场景 | 推荐调用链 |
| --- | --- |
| 登录、会话轮换 | `IdentityController.login` → `SessionServiceImpl.login` → `UserServiceImpl.authenticate` → `UserMapper.findByUsername` |
| 账号管理 | `UserController.create/update` → `UserServiceImpl.create/update` → `UserMapper` → `AuditServiceImpl.record` |
| 每次会话校验 | `BoundaryFilter.doFilterInternal` → `UserServiceImpl.findActor` → `UserMapper.findById` |
| 成员及路径授权 | `ProjectController` → `ProjectServiceImpl.requireMember/resolve` → `ProjectMapper.member` |
| 密钥与模型 | `ConfigurationController` → `ConfigurationServiceImpl` → `ConfigurationMapper`；不要输出密钥或展开到日志 |
| 外部凭证拒绝 | `ExternalBoundaryFilter` → `ExternalClientServiceImpl.authenticate/authorize` → `ExternalClientMapper` |
| 错误状态 | `GlobalExceptionHandler.known/invalid/unexpected`；对 ApiException 设置异常断点可提前定位业务拒绝 |

业务事务经 Spring 注入的 Service 接口进入实现类；不要 `new XxxServiceImpl` 或把需要代理的方法改成同类自调用。账号创建和凭证/授权写入都与审计处于同一数据库事务，已有审计失败回滚测试。文件系统不参与该事务；项目创建目前仍可能在数据库提交失败后留下空目录，不能把数据库事务当成文件系统原子性。

断点停留会消耗会话/CSRF、限速与 20 秒内部签名有效期，超时后重新登录或重新签发探针，不为调试取消安全校验。生产日志继续只记允许元数据，不全量打印请求、SQL 参数、异常或 DTO。

## 验证

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd -s .mvn/settings.xml -B -ntp verify
```

`verify` 运行独立 H2 测试、检查分层约束、打包并检查格式；不启动长驻调试工具。需格式修正时单独执行 `spotless:apply`。编译器显式保留 source/lines/vars 和参数名，便于断点与局部变量查看。调试入口、H2 和合成身份均不进入生产 JAR。


## 最小媒体生成断点

正常 IDEA main/local 入口不变。项目文件页与 Qwen 1K/1:1 已接通，可从 `MediaGenerationController` → `MediaGenerationServiceImpl.generate` → `GenerationRunMapper.lockProject/countHeld/insert` 跟踪权限、幂等和五名额；后台 `execute` → `ComfyImageClient` → `ProjectMediaServiceImpl` 跟踪真实生成/落存储。使用源码实际方法定位断点，不展开密钥对象或打印请求提示词。模型批准和项目成员在入场及发布前均重新校验。

ComfyUI 由用户手动启动，可选受限配置见[后端 README](README.md#m2-最终受控生成与重跑)。未启动会在占位前拒绝；未知状态 LOST 保留名额而不是误报完成，先核对上游运行状态。重跑工具和最新验证见[M2 最终验收](../docs/validation/M2_ACCEPTANCE.md)。
