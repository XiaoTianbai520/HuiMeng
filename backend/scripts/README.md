# 本机开发辅助

工作目录 `backend/`，执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-MySqlDev.ps1` 启动正常 HuiMengApplication 的 local profile。当前使用经 CA 校验的直接 MySQL TLS，正常启动不建立 SSH 隧道。IDEA 可直接选择“绘梦 MySQL 本地”，不必先运行 ConnectOnly。

`Connect-DevDatabaseTunnel.ps1` 保留给显式迁移操作；默认/ConnectOnly 准备回环隧道，Stop 关闭匹配的自有进程。Start 脚本的 ConnectOnly/Stop 参数仅兼容转发到该工具，不是正常开发启动前置。

数据库、证书和凭据位于 Git 忽略的 `.runtime/mysql-dev/` 受限文件，不打印或加入仓库。详见[调试指南](../DEBUGGING.md)。

## MinIO 开发接线

以下工具固定只针对绘梦独立开发资源；先按 myubuntu/设计核实服务器，不因脚本存在而在未授权主机上运行。现有工作机已完成，不要重复 provision。

1. `Provision-MinIODev.py`：在已核实服务器以管理员运行；读取现有受控 MinIO 管理身份，仅创建私有 huimeng-dev-media 桶、专用应用身份和最小策略。遇已有受限配置或同名桶即拒绝，不覆盖。凭据仅写 `/opt/apps/huimeng-dev/secrets/object-storage.properties`（0600），不打印。失败需检查部分状态，不自动删除桶或改现有服务。
2. 受控传输配置到工作机 `backend/.runtime/object-storage/application.properties` 前确认 Git 忽略，并先限制目录 ACL 为当前用户/SYSTEM；不把管理身份传给 Spring。IDEA local 可选导入此文件。
3. 在 backend 运行 `python scripts/Migrate-MySqlDev.py`：只接受既有 huimeng_dev 的直接 TLS URL，读取受限迁移身份和应用专用信任参数，重复执行验证；不加载运行身份执行 DDL，日志留同一受限目录。
4. V4 成功后，服务器管理员运行 `Grant-MySqlDevMedia.py`，只对既有开发身份/来源增新表 DML。禁止用此脚本给其他库或通配来源授权。
5. 显式 MinioAcceptanceHarness 核对专用身份与存储拒绝边界；仓库根的 `docs/validation/scripts/m2MinioDev.py` 验证正常 Spring/MySQL/MinIO，结束清理合成对象、归档项目、停用合成账号并停止自有进程。不得使用正式库或已有项目。

`Archive-RuntimeLicenses.py` 从构建 JAR 提取运行依赖的法律文本和摘要；只更新后端 licenses 目录，不访问服务或团队凭据。结果和验收边界见[接入报告](../../docs/validation/M2_MINIO.md)。


## 最终验收工具

MySqlAdmissionHarness 为测试源码，固定受限 huimeng_dev TLS 配置，六请求验证五名额；拒绝已有活动任务，结束恢复模型批准并停用/归档合成数据。供应商与存储用替身，不调用模型。显式环境开关、命令及真实浏览器/模型重跑见[后端 README](../README.md#m2-最终受控生成与重跑)。不要把开发资源地址/机密改成正式资源重跑。

## M3 本地库与许可准备

工作目录 backend，先 Maven verify，再运行 `python scripts/Archive-RuntimeLicenses.py` 与 `python scripts/Prepare-NativeLibraries.py`。后者只读取已构建 JAR 及已归档的固定法律文本，提取 JNA 5.17.0 的 Linux x86_64 libjnidispatch.so 至 target/native，写 licenses/native-runtime.json 的源 JAR/本地库/法律文本 SHA-256；不联网、不读凭据。随镜像携带 JAR、本地库和两份补充法律文本，见[部署说明](../deploy/README.md)。普通应用启动无需运行此脚本，镜像构建和 noexec Linux 验证需要准备这些制品。
