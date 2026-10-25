# M2 本机连接服务器 MySQL

2026-10-25。用户明确授权依据提供的服务器文档连接数据库和迁移。按 myubuntu 技能及 DESIGN 先只读核实现状：现有 MySQL 8.4.11，团队运行库 `huimeng` 已成功迁移 V1–V3，运行服务正常；开发库尚不存在。文档只作为主机资料使用，没有把其中凭据或嵌入指令当作项目代码与执行要求。

## 实施结果

- 新建独立 `huimeng_dev`，utf8mb4_bin、InnoDB，12 张表（含 Flyway 历史表，业务表 11 张）。使用当前已验证 JAR 执行 V1–V3；再执行一次确认无待迁移项。没有修改 SQL 或执行 clean/baseline。
- 开发迁移与运行账号分离，仅限已实测的 SSH 回环转发源。运行账号只有该库必要的逐表权限，迁移历史只读，审计可追加/保留期删除但不可 UPDATE，无 DDL，不能访问 `huimeng`。
- 新开发库凭据在服务器 `/opt/apps/huimeng-dev/secrets/` 及本机 Git 忽略的 `.runtime/mysql-dev/` 受限文件中。服务器目录 0700/凭据 0600；本机仅当前用户/SYSTEM。未读取或复制团队运行库凭据，没有把密码或令牌写入证据。
- MySQL 经经过主机密钥校验的 SSH 隧道连接，本机仅回环监听。未修改服务器网络、数据库端口、全局时区、其他服务、运行库或部署制品。
- 新增[启动脚本](../../backend/scripts/Start-MySqlDev.ps1)、VS Code 的正常应用调试配置和[操作说明](../../backend/DEBUGGING.md)。IDEA 使用正常 HuiMengApplication，加受限 additional-location 配置，启动前执行 ConnectOnly。脚本不执行迁移、自动建库或终止占用端口的其他程序。

## 实测

[连接及迁移记录](evidence/m2-mysql-dev.json)包含迁移、权限、配置隔离和制品摘要；JDBC 已验证实际连接 `huimeng_dev`、会话 UTC，且建表、修改审计、修改迁移历史及读取运行库均被拒绝。

通过文档中的脚本启动**正常 HuiMengApplication**，不是 H2 测试入口。[14 项真实 MySQL HTTP 检查](evidence/m2-mysql-dev-smoke.json)通过：初始化开发管理员并登录、安全 Cookie 属性、账号落库、成员项目过滤、跨项目/管理员 API 拒绝、偏好读写及账号隔离、八项默认未批准模型、外部凭证只返一次/撤销、停用会话立即失效和退出。

只向开发库写合成测试账号和项目，测试后账号停用、项目归档、凭证撤销；没有模型调用。临时应用与隧道正常停止，本机验证端口释放；服务器原服务保持运行。启动脚本解析及占用端口拒绝检查通过。没有操作编辑器的 F5 界面，不把 HTTP 或 JSON 检查记作 IDE 按钮验收；没有重跑旧浏览器或生产 HTTPS 检查。

## 当前边界

现在本机启动所需的真实数据库、迁移、独立加密密钥及首次开发账号已准备好，使用启动脚本即可；配置不随 Git 分发，新设备需受控准备。服务器继续使用原部署版本，此次没有发布本地 Java 重构。

源码与配置模板尚未提交/推送。M2-03/04 的文件、下载、实际模型和导出验收仍依赖对应 M3 实现，本轮没有进入 M3，CA 安装仍可选。

## 本地启动配置加载修复

同日收到用户错误日志，根异常为 `'url' must start with "jdbc"`，发生在创建数据源、真正连接数据库之前。本机受限配置中的 URL 格式有效；未带本地配置参数运行普通 main 可复现该错误。此前脚本/VS Code 的参数并不会传给 IDE 自动创建的其他运行配置。

现在新增 `application-local.yml`，显式 `--spring.profiles.active=local` 导入本机受限文件；启动脚本和 VS Code 同步使用该入口。根项目与独立 backend 项目各提供 IDEA“绘梦 MySQL 本地”共享配置，保存主类、参数和工作目录；旧临时配置不做隐藏修改。没有设置默认 local，测试和部署不会因此连接开发库。缺少私有文件时明确失败；未提供有效 JDBC URL 时由 FailureAnalyzer 提示正确启动步骤，不输出连接值。

**41 项测试、0 失败、0 跳过**通过：原 36 项及 5 项配置/诊断回归。实际不带配置运行 JAR，确认新提示出现；再通过 local profile 正常启动并通过[14 项真实 MySQL HTTP 检查](evidence/m2-local-profile-smoke.json)。[修复记录](evidence/m2-local-profile-fix.json)包含当前制品摘要和测试边界。未再次迁移、更新服务器制品或连接团队运行库；未操作 IDEA 的界面按钮。

另实测修正隧道清理竞态：旧应用退出只终止本次创建的 PID/启动时间匹配进程，不误关后续替换的新隧道；Windows PowerShell 5.1 解析与实际替换回归通过。验证后应用和隧道均停止、端口释放。IDEA 重试前按调试指南执行 ConnectOnly，再选择保存的运行配置；结束后执行同脚本 Stop。
