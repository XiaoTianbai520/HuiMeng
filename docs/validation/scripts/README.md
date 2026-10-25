# 验证脚本

## M2 Linux 文件边界

`m2FileBoundary.py` 只面向新建的独立 Spring/H2 验收实例，合成账号固定为测试夹具，不得指向团队/开发库或已有工作区。以 [backend LinuxFileAcceptanceHarness](../../../backend/README.md#m2-读取导出与模型验收) 启动和运行；脚本要求显式 base、fixture-root、report 与 isolated-h2-fixture 参数，检查真实原字节/快照/Range、授权及链接竞态，不管理服务或 MySQL。结果含检查名称，无 Cookie、CSRF 或密码。

## M0 协议取证

这些脚本针对重新准备的原版隔离实例，不针对绘梦尚未实现的公共 API，也不复用已清理的临时清单。重跑会调用实际模型或市场服务；文档交接检查仅需运行[文档检查器](../../scripts/README.md)。

`m0Closeout.py` 与 `m0Market.py` 必须显式传入新实例的 `--runtime`，不会回退到 `.m0-work/` 历史清单。现有取证脚本沿用固定输出文件名；需要重跑时在独立验证副本执行并另存新证据，不覆盖已归档结果。M1-04 再将它们整理为目标接口回归夹具。

`m0Closeout.py` 使用 Python 标准库，记录真实上游 HTTP、SSE 和 MCP/A2A 响应。工作目录为绘梦仓库根目录。

```powershell
python docs/validation/scripts/m0Closeout.py --help
python docs/validation/scripts/m0TextAgent.py --help
python docs/validation/scripts/m0Closeout.py ffmpeg --runtime <临时实例清单.json>
python docs/validation/scripts/m0Closeout.py mcp --runtime <临时实例清单.json>
python docs/validation/scripts/m0Closeout.py a2a --runtime <临时实例清单.json>
```

运行前按 [M0 报告](../../archive/m0/M0_CLOSEOUT.md)准备固定提交独立实例。清单包含 runtime、data、project、port、providerId、modelId；runtime 是名为 `huimeng-m0-*` 的临时目录，data 和 project 位于其内部，清单不存凭证。实例的数据目录单独保存测试模型设置。MCP 读取 project/m0Synthetic.txt；A2A 使用 [m0Verifier 合成团队](../fixtures/teams/m0Verifier/)；FFmpeg 输入使用已归档合成 MP4。

FFmpeg 子命令通过原版接口下载程序并验证转码、进度、取消和输出下载。MCP 子命令临时启用该独立实例的 MCP，并在结束时恢复设置。A2A 子命令使用已启用的临时团队和模型，发送合成文本并取消测试任务。记录写入 `docs/validation/evidence/`。

本轮临时实例在取证后清理；重放时重新准备实例和清单。新平台的身份、名额和完整业务契约测试属于 M1–M4。

## M0-06 市场与插件生命周期

工作目录：绘梦仓库根目录。脚本使用 Python 标准库；不需要安装业务测试框架。

```powershell
python docs/validation/scripts/m0Market.py --help
python docs/validation/scripts/m0Market.py catalog --runtime <临时实例清单.json>
python docs/validation/scripts/m0Market.py plugins --runtime <临时实例清单.json>
```

必须先准备固定提交的独立实例和 `huimeng-m0-market-*` 临时目录，清单包含 `runtime/data/project/port`，路径位于此临时根下。实例 settings 中配置测试市场 Key；catalog 阶段会临时切换一个官方技能的收藏，查询实际状态后恢复，再下载到临时目录；不自动重试收藏切换。plugins 阶段会安装、更新、配置和卸载合成工具，以及只在临时实例重装/导出/卸载选定官方文档技能。脚本拒绝未命名为临时实例的数据根，不能对业务实例使用。

在线条目/版本会变化；脚本记录摘要比较，不将算法未明确的 fileHash 直接认定为 SHA-256。catalog 先于 plugins，下载条目保存在临时目录内。原已完成环境已清理，需重新准备，不能复用历史清单中的端口/PID。页面、分类扩展兼容和日常实例安装记录见 [M0-06 报告](../../archive/m0/M0_PLUGIN_MARKET.md)，不能把后端脚本当作所有页面测试的替代。

## MinIO 正常应用联调

仓库根执行 `python docs/validation/scripts/m2MinioDev.py`。需先按[后端脚本说明](../../../backend/scripts/README.md)准备受限开发配置、V4 和按表授权；脚本只起回环 18088 的自有进程，连接独立 huimeng_dev 和开发桶，以合成账号/项目验证并清理。普通文档检查和 Maven verify 不连接真实存储。结果及限制见[接入报告](../M2_MINIO.md)。


## M2 最终浏览器夹具边界

仓库根执行 `python docs/validation/scripts/m2AcceptanceBoundary.py` 前，须按[后端 README](../../../backend/README.md#m2-最终受控生成与重跑)显式开启独立 Spring/H2 浏览器夹具，完成真实页面上传和固定 Qwen 生成。脚本固定只访问回环 18086，以公开合成账号验证 32 项允许/拒绝，记录 PNG/摘要；末尾移除合成成员，便于浏览器检查清空状态。它不是正常 MySQL 应用测试，不得改端口/账号指向真实业务环境。

模型会消耗本机 GPU，须先核对队列和现用模型；不启动 ComfyUI、不下载权重、不读取远程 Key。测试完主动 stop 夹具，由夹具删除自己在专用桶建立的对象并销毁 H2，停止临时 Vite。证据及实际范围见[M2 最终验收](../M2_ACCEPTANCE.md)。


## M3-01 原格式保存边界

m3FileWriteBoundary.py 仅面向 M3FileAcceptanceHarness 新建的 Linux/H2 夹具、固定回环 18090，不得用于正常应用或既有工作区。按[后端 README](../../../backend/README.md#m3-01-原格式保存)显式启动工具后调用；要求 --fixture-root、--report 和 --isolated-h2-fixture，验证真实字节/编码/冲突、成员/CSRF/链接与撤权。合成账号仅属于此夹具，结束清理自己新建的随机目录。46 项结果和限制见[M3 记录](../M3_FILE_WRITE.md)。

## M3-01 目录操作与页面

仓库根运行 `python docs/validation/scripts/m3WorkspaceBoundary.py` 前，按[后端 README](../../../backend/README.md#m3-01-目录操作与文档浏览器验收)在 Linux 显式启动 WorkspaceBrowserAcceptanceHarness，并仅转发服务器回环 18091 到本机回环 18086。脚本固定该回环端口、Origin localhost:5187 及公开合成账号；不修改为正常应用或真实数据库。检查在本次 H2 另建项目中进行，涵盖复制/重命名/递归删除/根目录保护/撤权等 39 项，末尾撤权并归档自己的项目。不得将合成账号密码替换为团队凭据。

浏览器从 localhost:5187 操作独立文档区域，文档新建/编辑/冲突另存与撤权证据见[本批报告](../M3_WORKSPACE.md)。实际删除由 HTTP 测试执行，浏览器只验证删除确认与取消。测试完主动停止夹具、转发和 Vite，不访问真实存储/模型；测试工具不进入运行 JAR。
