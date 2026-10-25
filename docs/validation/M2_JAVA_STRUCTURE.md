# M2 后端结构与调试整理

2026-10-25。按用户指定的 java-standards，在现有 M2 未提交改动上整理后端。工作前已检查 Git、AGENTS、交接入口、模块 POM、配置及调用链；保留现行 PRD、DESIGN、CONTRACTS、TASKS 和既有前端/部署改动。本轮只在本地验证，没有连接服务器、MySQL 或供应商，没有更新服务器部署。

## 改动

- 控制器依赖 `service` 接口，实现放 `service.impl`。会话编排由 `SessionServiceImpl` 负责，账号管理单独进入 `UserController`。
- 业务 SQL 和行映射移到 `dao.mapper`；`dao.entity` 使用独立 DO，Service 负责转换。会话过滤器通过 `UserService.findActor` 每次读取可信身份，保留立即失效校验。
- `Inputs` 中的嵌套请求模型拆为独立 `dto.req.XxxReqDTO`；响应使用 `dto.resp.XxxRespDTO`，控制器不再以 Object/Map 返回固定平台对象。动态偏好、模型 JSON 和外部协议字段按原契约保留。
- 统一异常移至 `common.exception`，仍保留原 HTTP 状态和 code/message/requestId/details。没有新增 Result 包装或改变字段、时间、ID、游标及 204 语义。
- 保留 Spring JDBC、显式构造器、原版本及已启用的 Google Java Format；未增加 MyBatis-Plus、Lombok、Swagger、Redis 等依赖。接口文档仍由现有 OpenAPI 维护。
- 业务事务保留在 Spring 代理调用的 ServiceImpl 边界，明确 `rollbackFor=Exception.class`。已验证审计失败会回滚账号创建，以及凭证和能力授权的共同写入。文件系统仍不参与数据库事务，未宣称项目目录创建具有跨资源原子性。
- 新增[本地调试入口](../../backend/src/test/java/cn/huimeng/debug/LocalDebugApplication.java)、[IDE 配置](../../.vscode/launch.json)和[调试指南](../../backend/DEBUGGING.md)。入口只位于测试源码，显式合成身份、独立内存 H2、回环端口及 target 内临时目录，不依赖 CA、服务器或真实凭据。编译器显式保留源码、行号和局部变量信息。

## 验证

工作目录 `backend/`，固定 Wrapper / JDK 21.0.8、Java 17 目标：

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2'
.\mvnw.cmd -s .mvn/settings.xml -B -ntp clean spotless:apply verify
```

**36 项测试、0 失败、0 错误、0 跳过**：32 项真实安全/数据库集成测试（原 30 项及两项事务回滚回归）、2 项分层约束、2 项调试入口隔离。`verify` 同时完成可执行 JAR 和格式检查。首次沙箱测试遇到本机 JVM attach 命名管道权限问题，停止后在允许本机 JVM 附加的环境重跑通过；没有用跳过测试代替通过。

[11 项本地调试 HTTP 检查](evidence/m2-java-debug-smoke.json)通过：实际用文档中的 `spring-boot:test-run` 命令启动 main，验证合成账号登录、安全 Cookie 属性、明确 DTO 的响应形状、跨页游标、模型目录、凭证一次返回/安全列表/撤销、成员过滤、管理员拒绝错误、偏好保存与退出。工具已经停止，回环端口关闭。测试客户端只在进程内处理合成 Cookie/令牌，没有将它们写入证据。

生产 JAR 排除 H2、调试入口、浏览器测试工具及旧 api 包残留；检查调试符号和 IDE 配置 JSON。OpenAPI/前端生成一致性仍为 39 操作、26 已实现，未重写数据库迁移，127 条源路由仍 publicEnabled=false。文档与契约检查结果见[结构验证记录](evidence/m2-java-structure.json)。

此处验证的是实际命令行 main 和 HTTP，未操作 IDEA/VS Code 的界面，不把配置检查记为编辑器按钮或断点交互验收。2026-10-24 的 15 项浏览器及 49 项部署 HTTPS 记录仍只覆盖当时版本，不能据此宣称本轮重构已部署。

## 交接边界

本轮源码、文档和调试配置未提交、未推送、未部署。服务器继续使用[上一轮制品](evidence/m2-followup-deployment.json)。本地调试 H2 不替代服务器 MySQL 兼容验证；正式更新部署前按部署说明重新核实和验证制品。

M2-03/04 的 A03/A17 文件/下载和 A11 实际模型/导出继续待对应 M3 业务补验；M3–M4 未开始。本轮没有修改验收范围、开放创作通配入口或将内部 CA 安装改为必需。
