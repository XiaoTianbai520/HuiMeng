# 绘梦 Spring 安全入口

M2 模块，独立于前端和原 Toonflow。遵守[现行设计](../docs/architecture/DESIGN.md)和[冻结契约](../docs/api/CONTRACTS.md)，创作路由、插件执行与真实 MCP/A2A 分派按 M3 接续；没有通配代理。

## 结构与版本

参考 [BaseStructure cf21af88](https://github.com/XiaoTianbai520/BaseStructure/tree/cf21af8805372954bf179bed48e7cdc45bb3abb3) 的 Maven、Java 17、Spring Boot 3.0.7 参考基线、compiler 3.11.0、Surefire 3.1.2、Spotless 2.43.0 及 controller/service/config 分层。代码按绘梦契约独立实现，未复制其业务示例、响应包装或默认认证；没有引入 Cloud、Nacos、Redis、MyBatis、Knife4j、Lombok。Maven Wrapper 3.3.4 使用 Apache 发行脚本和 Maven 3.9.7；脚本保留 Apache 许可头。现有 MySQL 8.4.11 超出原 Flyway 9.5.1 验证范围，因此实际固定 Spring Boot 3.5.7、Flyway 11.15.0；没有更改业务契约或 Java 17 目标。Spring BOM 固定 Security、JDBC、MySQL Connector 等依赖版本。

`src/main/java/cn/huimeng/` 保存入口、控制器、安全边界和事务服务；`src/main/resources/db/migration/` 保存不可重写的 Flyway SQL。H2 只属于测试依赖，不提供生产运行 profile 或控制台。`executor-boundary/` 是零依赖的 Bun 1.3.14 健康接收器。服务及后续插件的发布依赖许可和安全更新核对仍需 M4 完成，不能因沿用参考版本而视为已发布验收。

2026-10-25 按 java-standards 整理为 `controller → service/impl → dao/mapper`，Service 接口位于 `service/`，持久记录和独立请求/响应模型分别在 `dao/entity/`、`dto/req/`、`dto/resp/`；异常集中在 `common/exception/`。保留显式构造器、现有 JDBC、格式化与冻结 JSON 协议。[结构与调试指南](DEBUGGING.md)提供 IDE Debug main、VS Code 配置、本地 JVM 附加和常用断点。

本机已配置服务器上的独立 MySQL 开发库 `huimeng_dev`（当前 V4），可从 `backend/` 运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-MySqlDev.ps1` 启动正常应用，或使用 VS Code 的“绘梦：服务器 MySQL 开发调试”。凭据仅在受限的 `.runtime/mysql-dev/`，不会随 Git 交付；详细前端联调与 IDEA 参数见[MySQL 调试说明](DEBUGGING.md#使用服务器-mysql-调试当前工作机已配置)。团队运行库 `huimeng` 未用于本机调试。

当前开发连接已改为使用应用专用 CA 信任库校验的直接 MySQL TLS；IDEA 正常 main 加 local 参数即可，不再预先 ConnectOnly。服务器及系统信任库未改变，实测与证据见[直连修复](../docs/validation/M2_MYSQL_TLS.md)。

M2 收尾补齐 `/api/v1/preferences` 的账号隔离读写，以及 `/api/v1/admin/models` 的管理员模型目录。前者仅接收 ui/general/stores/documentExtensions 对象，最大 32 KiB、嵌套最多 32 层，拒绝混入密钥/令牌/供应商配置；后者只返回登记模型的安全元数据和批准状态，成员仍只能读已批准模型。该次界面收尾时数据库结构保持 V3；后续 MinIO 批次新增 V4，不重写已有迁移。

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

在 `backend/` 设置 `HM_BROWSER_ACCEPTANCE=true` 后执行 `.\mvnw.cmd -s .mvn/settings.xml -B -ntp '-Dtest=BrowserAcceptanceHarness' test`；就绪标记为 `target/browser-harness.ready`。前端在其自身目录设置 `HUIMENG_API_TARGET=http://127.0.0.1:18086`，运行 `bun run dev --port 5187 --strictPort`，浏览器访问 `http://localhost:5187/`。仅测试夹具账号使用源码中标明的公共合成密码，禁止换成团队真实凭据。测试完创建 `target/browser-harness.stop`，工具正常结束；前端临时进程也应停止。最长自动运行两小时；应主动停止，不依赖超时清理。启用真实 MinIO 时结束会清理本夹具创建的精确对象键，有未终结运行则拒绝误清理。

localhost Cookie 行为依据[浏览器文档](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie)及本轮实际验证；这份回环 HTTP 证据不是局域网 HTTPS 浏览器信任证明。正式配置继续要求 HTTPS。检查范围及截图见[M2 界面收尾报告](../docs/validation/M2_UI_REPORT.md)。

## 数据库与启动

先检查现有主机，不把已有业务库用作测试。新建 **huimeng**、utf8mb4_bin、InnoDB；迁移和运行账号分开，迁移账号仅此库 DML/CREATE/ALTER/INDEX/REFERENCES，运行账号仅此库 DML。主密钥和凭据由部署受控文件注入，不提供内置账号、密码或 Key。

迁移进程需要 `HM_DB_URL`、`HM_MIGRATION_USER`、`HM_MIGRATION_PASSWORD`，运行 `java -jar target/huimeng-api-1.0.0-SNAPSHOT.jar --migrate-only`。没有 baseline/clean 或自动建库。正式进程关闭自动迁移，仅用运行账号校验迁移摘要；迁移凭据不挂载到正式进程。

正式进程需要 `HM_DB_URL`、`HM_DB_USER`、`HM_DB_PASSWORD`、`HM_MASTER_KEY_FILE`、`HM_INTERNAL_KEY_FILE`；后两者为独立随机 32 字节密钥的 Base64 文件。另配置 `HM_PUBLIC_ORIGIN`（同源 HTTPS）、`HM_WORKSPACE_ROOT`、可选监听地址/端口。执行 `java -jar target/huimeng-api-1.0.0-SNAPSHOT.jar`；默认只监听回环。不要把机密填进命令历史，使用受限 env_file 或密钥管理器。配置文件没有真实凭据。

第一次初始化由 `HM_BOOTSTRAP_USERNAME` 和 `HM_BOOTSTRAP_PASSWORD_FILE` 指向受控密码文件，数据库行锁保证只初始化一次；没有公众 setup/register 入口。管理员创建账号；最后一名有效管理员不能被停用/降级。停用、角色或密码变化增加 auth_version，使后续请求的旧会话失效。部署步骤见[deploy/README](deploy/README.md)。

## 安全参数及验证边界

会话 Cookie 为 `__Host-huimeng-session`，Secure/HttpOnly/SameSite=Lax/Path=/；闲置 8 小时、最长 24 小时。所有浏览器写操作含登录要求同源 Origin 和会话 CSRF；登录更换会话与令牌。账号连续失败最多 10 次/15 分钟，来源 IP 最多 100 次/15 分钟；敏感写操作每账号 60 次/分钟。限速状态为单实例内存数据，重启清空；没有在当前 M2 设计中承诺多 Spring 实例会话共享。

项目列表按成员过滤，管理员仅多出元数据管理；创作仍须成员关系。项目/路径服务拒绝绝对路径、父目录、反斜线、盘符/ADS、编码歧义及符号链接。精确 GET 文件 read 控制器以 SecureDirectoryStream 逐层无链接打开；Windows 默认文件系统不支持时返回 503。独立 MinIO 媒体 API 已实现，详见下节；原文档写入、上传、修订冲突及完整创作执行留 M3。

团队供应商与 TF-Router Key 使用 AES-256-GCM（标识及密钥版本作 AAD）、独立主密钥、随机 nonce；只写不回显。模型目录默认全部未批准，仅管理员可启停已登记模型；Qwen 保留 2K。外部凭证为 256 位随机数、SHA-256 存储、单项目及显式能力范围、逐请求重查并可撤销；团队成员会话不能代用。MCP/A2A 未接通时合法凭证也不会执行创作。

审计仅记录操作者、项目、操作、目标标识、结果、请求 ID 和 UTC 时间；默认 180 天保留，每日清理过期元数据。日志不接受正文、原始路径、查询、认证头、密钥或异常内容，仅记录规范化 requestId、actorId、路由模板、状态与耗时。部署、MySQL 以及真实 HTTPS 验证结果见[M2 报告](../docs/validation/M2_REPORT.md)。

## M2 读取、导出与模型验收

管理员模型与密钥页的“导出安全配置”调用 GET `/api/v1/admin/configuration/export`，只含版本、导出时间、供应商配置状态、市场是否配置及模型批准标识，不含任意 options、密钥、密文、端点或路径；成员禁止访问，导出写审计。文件精确路径为 GET `/api/v1/projects/{projectId}/creative/workspaces/files/read`，只收 path/snapshot/maxBytes，快照最大 8 MiB，下载最大 512 MiB；无文件写入/上传或通配入口。

以下均为**显式选择**的测试源码工具，不在普通 verify 中执行，不进入生产 JAR。工作目录 backend：

- Linux 上要求 Python 3、回环 18087 空闲，执行 `HM_FILE_ACCEPTANCE=true MAVEN_USER_HOME="$PWD/.m2" sh ./mvnw -s .mvn/settings.xml -B -ntp '-Dtest=LinuxFileAcceptanceHarness' test`。只用新 H2 与随机 target/validation 目录；结果保存在该目录 result.json；不得改成团队数据库。完整源码仓库需保留 docs/validation/scripts。
- 本机 ComfyUI 手动启动并确保队列空闲；先核对当前节点、模型与现用供应商哈希，将本轮 API 图复制到 target/m2-comfy-api.json。设置 `HM_COMFY_ACCEPTANCE=true` 后执行 `.\mvnw.cmd -s .mvn/settings.xml -B -ntp '-Dtest=ComfyUiAcceptanceHarness' test`。工具校验管理员非成员与模型未批准拒绝，再批准并执行一次本机模型，最后撤销测试批准；JSON 结果和 PNG 在 target/m2-comfy-*。图、实际参数和依赖见[本轮夹具](../docs/validation/fixtures/m2-acceptance/README.md)。这只验证测试用 Spring 服务授权与实际模型，不提供公共媒体执行器或五名额。

实测边界与剩余验收见[M2 推进记录](../docs/validation/M2_REMAINING_PROGRESS.md)。本轮无新增 SQL 迁移，正式服务器版本未更新。

## 私有 MinIO 媒体存储（2026-10-25 增补）

最新[接入报告](../docs/validation/M2_MINIO.md)补充 V4 媒体迁移和四个精确 API，开发库已迁移，正式库和部署仍未更新。独立媒体 API 不依赖 Windows 安全目录句柄；原工作区文档读取限制保持。

当前工作机 IDEA 正常 main 加 `--spring.profiles.active=local`，工作目录 backend，即可加载既有 MySQL 和可选 `.runtime/object-storage/application.properties`。新的工作机须自行取得受控配置，不从仓库领取凭据。内存调试/test profile 默认关闭对象存储，不读取真实配置。

受限文件中的配置项为 `huimeng.storage.enabled=true`、endpoint、bucket、region（默认 us-east-1）、access-key、secret-key（后五项带同一个 huimeng.storage 前缀）。endpoint 由管理员指定，不接收浏览器输入；专用应用身份不能使用 MinIO root。部署使用受限 config import 文件或等价机密注入；不在命令行放 Key。

| 方法与路径（前缀 `/api/v1/projects/{projectId}/media`） | 行为 |
| --- | --- |
| POST `?filename=显示名称` | Body 为 application/octet-stream，Content-Length 0–512 MiB，需 CSRF；201 返回媒体 ID/摘要，不接受目录/对象键/URL |
| GET | cursor/limit 分页，只返回 READY 媒体元数据 |
| GET `/{mediaId}/content` | 原字节 attachment，支持单 Range，不跳转到存储地址 |
| DELETE `/{mediaId}` | 成员且项目活动，需 CSRF；失败保留 DELETING 可重试 |

没有上传自动重试或覆盖键；应用按项目重新授权，管理员也必须是成员。项目文件页与固定 Qwen 生成结果已作为 M2 最小依赖接通；完整素材操作、原文档 CRUD/冲突、导出物和 FFmpeg 接线继续 M3。

在 backend 工作目录执行（真实工具均显式选择）：

```powershell
# 已准备受限开发配置，且已验证目标为独立开发库时
python scripts/Migrate-MySqlDev.py
$env:HM_MINIO_ACCEPTANCE = 'true'
try { .\mvnw.cmd -s .mvn/settings.xml -B -ntp '-Dtest=MinioAcceptanceHarness' test }
finally { Remove-Item Env:HM_MINIO_ACCEPTANCE }
python scripts/Archive-RuntimeLicenses.py
```

迁移后须由服务器管理员按[脚本说明](scripts/README.md)为新表授予运行账号 DML。HTTP 联调从仓库根执行 `python docs/validation/scripts/m2MinioDev.py`，会启动独立回环 18088，测试合成数据并停止自有进程。现有正式 V3 部署不能直接替换为 V4 JAR；部署升级与独立运行桶另验。


## M2 最终受控生成与重跑

当前[M2 验收](../docs/validation/M2_ACCEPTANCE.md)为 71 项默认后端测试；前述 47/55 等属于历史批次。首页“文件与素材”进入 `/projects/:projectId/files`，复用私有媒体 API。生成仅接 qwenImage21Local/qwenImage21Text、1K/1:1；控制器/服务/Mapper 分层，未挂创作通配代理。

IDEA local 可选导入受限 `.runtime/comfy/application.properties`，配置 `huimeng.comfy.enabled=true` 和 `huimeng.comfy.endpoint`（工作机当前为本机 8188）。默认关闭；不读取浏览器 URL，不自动启动 ComfyUI。不打印完整受限配置；未启服务时在占位前返回 503。实际出图还需要已有模型/节点和管理员批准。

以下可选工具会访问明确开发资源，均不包含在普通 verify，工作目录 backend：

```powershell
# 独立 huimeng_dev：先确认没有其他占位/调试写操作；供应商和存储用替身
$env:HM_MYSQL_ADMISSION = 'true'
try { .\mvnw.cmd -s .mvn/settings.xml -B -ntp '-Dtest=MySqlAdmissionHarness' test }
finally { Remove-Item Env:HM_MYSQL_ADMISSION }

# 真实模型：先由用户手动启动 ComfyUI，并核对现用模型/队列和专用 MinIO 配置
$env:HM_BROWSER_ACCEPTANCE = 'true'
try { .\mvnw.cmd -s .mvn/settings.xml -B -ntp '-Dtest=BrowserAcceptanceHarness' '-Dhuimeng.comfy.enabled=true' test }
finally { Remove-Item Env:HM_BROWSER_ACCEPTANCE }
```

MySqlAdmissionHarness 固定独立开发库及 TLS，六请求验证五名额后恢复批准、停用合成账号并归档项目；拒绝已有活动运行。浏览器工具用新 H2、真实 MinIO 和真实 ComfyUI，按本页前端命令操作合成账号页面上传/生成。完成后在仓库根运行 `python docs/validation/scripts/m2AcceptanceBoundary.py`，它仅访问显式浏览器夹具，末尾移除合成成员以检查页面撤权；不得指向正常应用或正式库。结束创建 stop 文件并停止临时前端。

查询/取消只限原发起人和有效成员；LOST 状态保留名额，不能通过重启清零。完整取消/重启、跨入口和真实容量仍属 M3/M4。最新源码没有覆盖正式 V3 部署。
