# M2 IDEA 开发数据库直连修复

2026-10-25。用户在 IDEA 已启用 local 后，Flyway `validate()` 报 Communications link failure。现场检查确认本机数据库隧道端口未监听，URL 配置已加载；之前把连接准备放在独立脚本，IDE 单独 main 不能保证该进程存在。

## 当前实现

本机开发连接已改为现有局域网 MySQL 的直接 TLS，正常 main 不依赖 SSH 子进程。继续使用独立 `huimeng_dev` V3；新增仅限当前工作机且 REQUIRE SSL 的开发运行/迁移账号，沿用各自受控凭据和最小权限，没有修改团队运行库或现有网络/端口。

JDBC `sslMode=VERIFY_CA`，通过 Hikari data-source-properties 读取应用专用 PKCS12 信任库。只取 MySQL 公用 CA，未复制服务端私钥，未安装系统 CA；此模式验证 CA 链，不声称已做 DNS 主机名校验。配置与证书留在受限且 Git 忽略的本机目录，不放在代码、日志或证据中。

IDEA 保存的“绘梦 MySQL 本地”及 `--spring.profiles.active=local` 继续有效，不再要求预先 ConnectOnly。Start-MySqlDev 只负责启动本地应用；旧隧道工具独立保留给显式迁移通道，不由应用执行。运行进程不加载迁移账号，Flyway 仍仅 validate。未增加数据库迁移或创作接口，M2-03/04 待验收状态不变。

## 验证

- 清理本模块生成的 target 后，固定 Wrapper 的 `spotless:apply verify`：**41 项测试，0 失败、0 跳过**。测试继续使用 H2，不读取开发凭据。
- JDBC 实测：真实库与 UTC 会话、非空 TLS cipher；使用错误信任 CA 时握手失败；禁用 TLS 后账号拒绝。运行账号不能建表、UPDATE 审计、UPDATE 迁移历史、读取团队运行库。
- 没有预启动隧道，直接运行正常 JAR 和 local profile，成功完成数据库验证。[14 项 MySQL HTTP 回归](evidence/m2-direct-tls-smoke.json)通过，包含登录、成员与跨项目拒绝、偏好、凭证、停用失效和退出。
- 检查本地脚本语法、IDE XML/JSON、应用制品排除 H2 和受限配置；[连接修复记录](evidence/m2-direct-tls.json)保存摘要与边界。没有操作 IDEA 的 UI，不将命令行验证称为 IDE 按钮验收。

验证后临时应用停止，18085 释放，13306 无监听；用户可直接在 IDEA Run/Debug。没有修改服务器应用制品、团队运行库、全局时区、端口或系统证书信任。合成测试账号停用、项目归档、凭证撤销，没有模型调用。

此前报告中的 SSH、本地预连接和测试数保留为当时记录；当前开发操作以[调试指南](../../backend/DEBUGGING.md)和本报告为准。源码及模板未提交/推送。
