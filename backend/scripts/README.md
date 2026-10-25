# 本机开发辅助

工作目录 `backend/`，执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-MySqlDev.ps1` 启动正常 HuiMengApplication 的 local profile。当前使用经 CA 校验的直接 MySQL TLS，正常启动不建立 SSH 隧道。IDEA 可直接选择“绘梦 MySQL 本地”，不必先运行 ConnectOnly。

`Connect-DevDatabaseTunnel.ps1` 保留给显式迁移操作；默认/ConnectOnly 准备回环隧道，Stop 关闭匹配的自有进程。Start 脚本的 ConnectOnly/Stop 参数仅兼容转发到该工具，不是正常开发启动前置。

数据库、证书和凭据位于 Git 忽略的 `.runtime/mysql-dev/` 受限文件，不打印或加入仓库。详见[调试指南](../DEBUGGING.md)。
