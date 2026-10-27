# 文档维护与检查

工作目录：绘梦仓库根目录。以下文档检查仅需 Python 3.10+ 标准库，不联网、不写文件、不调用模型或测试服务。

```powershell
python docs/scripts/check_docs.py
```

退出码 0 表示当前 Markdown 相对链接/章节锚点、文档 JSON 及 Python 验证脚本语法检查通过。检查器不访问外部链接，不验证远端服务、图片视觉、YAML/OpenAPI 语义、程序功能或密钥配置，不能代替业务验收。

## 更新规则

1. 业务范围改动更新 PRD；技术方案更新 DESIGN；接口变化更新 CONTRACTS、M1 产出的 OpenAPI 与受影响的路由记录；实施进度更新 TASKS。
2. 里程碑变化同步[交接入口](../handoff/README.md)、根 README 和文档索引中的阶段/下一步，不能把“取证完成”改写成“新平台已实现”。
3. 新证据附对应供应商版本、摘要、实际请求/响应和限制；更新问题与 A01–A18 索引。原始证据不为迎合新结论而重写。
4. 被替代的需求从现行正文移除；需要追溯的过程记录放入 `docs/archive/` 并标明历史。旧供应商快照保留取证用途，不进入当前安装说明。
5. 移动文档同时修正入链、出链、章节锚点和脚本说明，运行本检查器；交接前检查 Git 未提交/未跟踪内容是否已完整交付。

## M1 契约生成与检查

这些脚本使用 [requirements-contracts.txt](requirements-contracts.txt) 中固定版本的 PyYAML、openapi-spec-validator 和 jsonschema。先在自行准备的隔离 Python 环境安装；安装需要网络，后续检查离线运行，不访问业务服务或供应商。本次实际环境为 Python 3.11.9，依赖安装在忽略的 `.m1-work/python`，检查时将其加入 PYTHONPATH；该缓存不是交付依赖。

```powershell
python -m pip install -r docs/scripts/requirements-contracts.txt
python docs/scripts/check_contracts.py
python docs/scripts/sync_frontend_contracts.py --check
python -m unittest discover -s docs/scripts -p test_compare_contracts.py
```

- [build_openapi.py](build_openapi.py) 是 M1/M2 基线生成器，不是当前契约的事实源。默认把基线写到 `docs/api/generated/`（已忽略），只有显式 `--write` 才会覆盖 `docs/api/openapi.yaml` 与 `docs/api/route-map.json`；后者会丢失 M3 之后手工补充的平台路径，仅用于有意重建基线。
- M2 已实现操作由[后端清单](../../backend/api-implementation.json)驱动 x-implemented；不得用此标记放行源路由。127 条源路由与 1 条平台新增（R128）仍 publicEnabled=false，业务验收见[M2 报告](../validation/M2_REPORT.md)。
- [check_contracts.py](check_contracts.py) 校验 OpenAPI 3.1 标准、角色/CSRF/项目/幂等、来源与 A/FR、127 条源路由全覆盖、平台新增显式标记及请求正反例；不代表后端实现通过。
- [sync_frontend_contracts.py](sync_frontend_contracts.py) 从 OpenAPI/route-map 生成前端 DTO 和最小路由元数据；不带 `--check` 时写文件，带该参数时只检查一致性。
- [compare_contracts.py](compare_contracts.py) 对已录制的 JSON 严格比对；允许逐一声明动态字段 JSON Pointer，保留事件顺序、错误和文件摘要。用法见[离线回归](../validation/contracts/README.md)。
- [intake_frontend.py](intake_frontend.py) 是已完成的一次性固定提交迁入工具，会拒绝已有目标；不属于日常生成或构建，不得重跑覆盖适配后的源码。

独立应用安装、检查、构建与许可归档命令见[frontend/README](../../frontend/README.md)及[许可说明](../../frontend/licenses/README.md)。历史 M0 取证脚本保持独立，不从临时缓存复制启动命令，也不为交接检查重新调用模型。
