# M2 局域网隔离部署

先按 [DESIGN](../../docs/architecture/DESIGN.md) 与已安装的 MyUbuntu 技能只读核实主机、容器、MySQL、监听端口、目录与恢复路径。此目录不启动原 Toonflow，也不安装媒体模型。独立 `boundary` 只有已签名健康检查，不具备 M3 创作能力。

## 首次准备

工作目录为仓库根目录。先在 `backend/` 验证并打包，再构建镜像。所有服务器命令只针对新绘梦目录；不得覆盖已有目标。`provision.py` 在服务器执行，遇已有绘梦库/账号/目录即拒绝，凭据只由服务器安全随机生成，保存在 `/opt/apps/huimeng/secrets/`，不回传到仓库或日志。MySQL root 仅用于创建新库和限定账号，不供应用连接。

运行 `python3 provision.py` 前核实 `172.29.46.0/24` 未与已有网络冲突；为绘梦新建 `docker network create --internal --subnet 172.29.46.0/24 huimeng_database`，将现有 `mysql` 容器额外连接到此专用网络，不重启、不改变其原端口和网络。迁移账号只对 `huimeng.*` 有 DML/CREATE/ALTER/INDEX/REFERENCES；运行账号只有 DML，没有全局授权、建库、GRANT、DROP 或其他业务库权限。应用显式 UTC，不修改全局 MySQL 时区。

应用 JAR 放 `/opt/apps/huimeng/target/`，Bun 边界文件放 `executor-boundary/`，前端 dist 内容放 `/var/www/huimeng/`。静态目录须为 755、公开构建文件可读，机密目录继续 700。复制本目录 Compose、Nginx 配置、Dockerfile、`.dockerignore` 和 Python 部署脚本到应用目录；`.dockerignore` 仅允许 JAR 进入构建上下文，排除 secrets。

首次部署的工作目录为服务器 `/opt/apps/huimeng/`；已经存在的部署不能重跑 provision 或重签证书。在首次 provision 和数据库网络建立后，按顺序执行：

```bash
docker build -f Dockerfile -t huimeng-api:m2 .
docker compose -f compose.yml --profile migration config --quiet
docker compose -f compose.yml --profile migration run --rm migration
python3 finalize_database.py
python3 create_tls.py
docker compose -f compose.yml up -d api boundary web
```

迁移进程只挂载 migration.env；正式 API 仅持有运行凭据，启动校验摘要、不执行 DDL。`finalize_database.py` 在迁移后进一步收窄运行账号到业务表 DML，迁移历史只读，审计不允许 UPDATE。不要输出展开后的 Compose（env_file 含凭据）。

TLS 文件放 `secrets/tls/server.crt`、`server.key`。可用已有可信证书替代首次生成器；独立 CA 和叶证书具有严格 Key Usage，SAN 包含 `192.168.1.102`，私钥仅留服务器。用户已确认团队设备安装公用 ca.crt 为可选，不作为里程碑前置。未安装时浏览器可能提示或阻止访问；安装可消除此内部 CA 未受信任的提示，也可在后续改用已有受信任证书。继续保留 HTTPS 和安全 Cookie，不自动关闭证书校验；实际页面功能另行验收。验证用 `curl --cacert <ca.crt>` 或 Python 默认严格 SSL 上下文，不得把跳过证书校验当作验收。首次管理员是 `huimeng-admin`，密码由服务器 secret 文件经受控方式交付，不能粘贴进聊天；接管并更改密码后移除 bootstrap 配置、挂载与密码文件。主密钥须受控备份，丢失将无法解密 Key。

## 验证与恢复

唯一映射为 `192.168.1.102:18443`。Nginx 另按客户端网段拒绝非局域网来源；Spring/Bun 不映射宿主机端口，Bun 只在内部网络。检查 Docker inspect 的端口与网络、`ss -lnt`，从局域网验证 Web、CSRF、Cookie、登录、成员拒绝、MCP/A2A 凭证拒绝；此时协议授权后的 503 表示 M3 尚未接通。

在服务器应用目录执行 `python3 smoke.py` 可重跑真实 HTTPS API 验证，输出只含状态；它读取本机受控初始化文件，不输出凭据。会创建合成账号/项目/Key/凭证并在成功后停用、归档、撤销/移除；不调用模型、市场，不删除工作区或审计。若中途失败，只输出已完成状态和安全错误类型，须检查这些合成对象再决定重试。接管管理员并移除初始化文件后，应改用团队自己的测试账号，不恢复旧 bootstrap 密码。

原有服务没有被替换。停止绘梦使用 `docker compose -f compose.yml stop`，保留所有数据库、工作区及密钥；不要运行 down -v 或删除 /data。后续升级先成套备份独立库、工作区、加密主密钥及部署配置，再验证新 JAR；数据迁移遵守向前兼容，不对已有迁移改摘要。完整备份恢复演练仍在 M4。

镜像运行时须记录实际 image digest。当前版本参考 BaseStructure，依赖和容器的发布许可、安全更新核对仍纳入 M4 发布检查。
