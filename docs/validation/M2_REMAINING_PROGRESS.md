# M2 剩余项推进记录

2026-10-25。中文提交 [5f0fdeb](https://github.com/XiaoTianbai520/HuiMeng/commit/5f0fdeb1d0965d16ea2a36ae61eb5fe8343799c8) 已先推送 master，本轮从该基线继续 M2-03/04。用户选择本机 ComfyUI 并自行启动；项目保存 connect 策略，不自动启动、更新节点或下载权重。按 java-standards 沿用现有分层、JDBC、独立 DTO 和异常协议。

## 已实现及证据

| 项目 | 验证结果 | 实际边界 |
| --- | --- | --- |
| 精确文件 GET read | 后端安全集成测试；36 项检查和 100 次链接竞态，[Linux HTTP 结果](evidence/m2-file-linux-result.json) | 成员授权后实际打开文件，原字节下载、四种文本编码/原字节 SHA-256、单 Range/416、跨项目/非成员/移除成员拒绝。拒绝每一级链接，100 次父目录链接替换及工作区根祖先链接测试；没有写入、上传或通配代理 |
| 安全配置导出 | 管理员接口、成员 403、审计与合成密钥/任意 options 泄漏负例通过 | 只导出版本、UTC 时间、供应商状态、市场配置布尔值与模型批准标识；不导出 Key、密文、端点、options 或物理目录 |
| 管理页导出操作 | 本机真实 Spring/H2 登录后点击按钮，真实接口 200、成功提示；[界面截图](evidence/m2-export-ui.jpg) | 浏览器工具未捕获下载事件，不能据提示声称下载文件已经落盘。接口返回内容由后端及 Linux 检查单独验证；不是 LAN HTTPS 信任验收 |
| 模型执行授权服务 | 每次重查成员、项目活跃及模型批准，管理员不能替代成员；撤销立即生效 | 供后续执行链复用；没有公共生成入口 |
| 真实 Qwen 调用 | 独立 Spring/H2 测试授权后调用本机 ComfyUI 一次，约 50 秒，1024×1024 PNG 已查看；[元数据](evidence/m2-comfy-result.json)、[输出](evidence/m2-comfy-output.png)、[实际图](fixtures/m2-acceptance/README.md) | 明确测试源码工具，合成账号/项目，批准测试结束撤回；无远程 Key。没有五名额、幂等、结果项目落盘、公共取消或 Web 生成成功流，不能等同于 M3-03/A11 全业务 |

## 环境和复现

后端 `spotless:apply verify`：**47 测试、0 失败、0 跳过**；模型工具另行显式执行 **1 测试通过**。前端 `bun run check`：**21 测试、78 断言**，类型及边界通过；`bun run build` 成功。OpenAPI 增加安全导出、登记已有 read 的实现，**28/40 操作有代码**；127 条源映射维持 frozen 8、proposed 92、excluded 25、internal 2，均不变。

Linux 使用既有固定 Java 21 JRE 镜像，低权限 UID 10001、只读根、tmpfs、无 capability、独立 internal 网络和新 H2 内存库；未连接 huimeng/huimeng_dev 或挂载真实工作区。使用生产 JAR、外置测试 H2 驱动和独立合成配置，通过 PropertiesLauncher 启动；这是测试制品扩展，H2 未加入生产依赖。Docker internal 网络未发布预期的回环映射，验证由宿主机访问该临时容器的私有 IP；没有为此修改现有网络。临时目录为 `/opt/apps/huimeng/validation/m2-read-20261025` 与 `/data/huimeng/validation/m2-read-20261025`；结束后停止临时容器、移除专属网络，留存独立测试夹具。

初次脚本漏收认证 GET 响应轮换后的 Cookie，登录后后续写请求被拒绝。临时诊断仅输出验证布尔值，定位后修正脚本；最终验证使用**无诊断覆盖的原始制品**及全新 H2/工作区。受控根路径保持词法路径，避免先跟随根祖先链接后把外部目录当作授权根。每层实际 open 均用 NOFOLLOW_LINKS；关闭异常仍清理目录句柄。

Linux 最终制品摘要、检查数和临时环境回收结果随[结果文件](evidence/m2-file-linux-result.json)记录。可复现工具见 [backend README](../../backend/README.md#m2-读取导出与模型验收)：LinuxFileAcceptanceHarness 在 Linux 启动独立 Spring/H2 并运行 [HTTP/文件脚本](scripts/m2FileBoundary.py)。该 Java 工具已编译；本轮 Linux 实际运行的是上述生产 JAR加外置测试驱动与同一 Python 脚本。普通 verify 不访问模型或服务器。

## 保留的待验收项

- **M2 未整体完成**。M2-03 的 A03/A17 全部文件入口/页面、M2-04 的公开获批模型执行链仍随对应 M3 批次接通；本次只推进其最小依赖。TASKS 的原完成标准保留。
- M3-01 仅精确读取/下载依赖部分实施，写入、上传、保存冲突、其他文件/素材操作及完整页面尚未实现；其余 M3/M4 未开始，1.0 未验收。
- Windows 默认 JDK 文件系统没有 SecureDirectoryStream 时返回 503 EXECUTOR_UNAVAILABLE，不提供有竞态的路径预检降级。账号、项目、密钥等本地 MySQL 调试仍可用；文件成功流在 Linux 验证。
- 本轮未更新正式 Web/API/Bun 部署、数据库迁移或系统 CA；源台账 publicEnabled 保持 false，作为发布开关，与 OpenAPI 的源码 x-implemented 分开理解。不能把临时 Linux/H2 或本机 ComfyUI 结果当作正式部署成功。
- 浏览器导出下载落盘仍需后续确认；内网 HTTPS 证书警告照实保留，安装 CA 不是里程碑前置。
