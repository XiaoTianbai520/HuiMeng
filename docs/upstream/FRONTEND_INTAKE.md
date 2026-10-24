# M1 独立前端引入记录

2026-10-24。来源是参考克隆的固定提交 `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`，通过 `git archive` 导出；迁入前只读核实 HEAD 与工作区，未复制本地修改、`.git`、`data/`、`node_modules/` 或 `.m0-work/`。没有恢复旧供应商或重跑 M0。

## 1. 实际路径

| 来源 | 绘梦位置 / 处理 |
| --- | --- |
| apps/web/src、index.html | frontend/src、index.html；保留创作组件与视觉结构，新增会话/项目适配 |
| 10 个支持包 | frontend/packages 下的 assets、extScaffold、i18n、modelIcons、nodeScaffold、toolScaffold、providers、ffmpeg、file、tools/canvas |
| server 的 5 个纯函数/类型文件 | frontend/packages/contracts，去除服务包引用；没有迁入服务执行代码 |
| 新 API 边界与平台类型 | frontend/packages/api；contracts/platform.ts 由 OpenAPI 生成 |
| patches/vueFlowCore.patch、MIT | frontend/patches、frontend/LICENSE.Toonflow |

[280 条来源映射](frontend-intake.json)记录原路径、目标路径和固定提交内容的 SHA-256。哈希指原件，目标经过适配后不要求相同。一次性[迁入脚本](../scripts/intake_frontend.py)遇到已有目标会停止；它不是日常构建步骤，不能重跑以覆盖当前源码。

## 2. 可复现构建

工作目录与命令见 [frontend/README](../../frontend/README.md)。实际验证环境为 Windows、Node 24.11.1、Bun 1.4.2、Python 3.11.9。前端固定 Vite 8.2.2、Vue 3.5.42、TypeScript 6.0.3，并生成独立 bun.lock；不使用根锁中的 TypeScript 7。原执行器基线仍为 Bun 1.3.14，未在此阶段升级或迁入它。

独立构建不需要上游克隆、服务模块、历史临时目录或任何供应商凭据；安装依赖时使用锁文件和官方 npm 源。支持包中的构建/类型辅助文件不表示它们全部进入浏览器产物；运行代码已经不从 `@toonflow/server` 引入。开发代理没有默认 Bun 地址，只接受显式指定的 Spring 入口。

## 3. 行为适配与边界

会话 Cookie/CSRF、批准模型、授权项目、公司市场代理和外部客户端使用新契约。项目 ID 在旧组件的 `directory` 字段中暂作兼容标识，统一客户端在网络请求前移除该字段；物理路径及跨项目上下文被拒绝。插件脚本和 EventSource 的资源 URL 也经过映射。

移除桌面更新/原生剪贴板/本机文件定位、设备互联、目录浏览及仅桌面浏览器面板；导出使用浏览器下载。管理员保留市场发现、收藏与已安装管理；成员使用获批能力。公司市场 Key 只写不回显，插件版本/摘要策略见[冻结决策](../api/M1_CONTRACT_REVIEW.md)。Agent 市场安装继续关闭。

M1 的完成门槛是独立构建、统一客户端和契约样例可运行。Spring 未实现；旧公司配置的整包写入已阻断，供应商配置管理与账号/成员管理的完整业务接通留在 M2/M3。画布修订链、五名额、执行器取消确认、插件安装隔离与完整安全回归仍须按 TASKS/ISSUES 实现，不能把此次浏览器桩检查当作 A01–A18 验收。

## 4. 当前产物许可证

当前产物包含 260 个第三方包版本、11 个工作区包，另有 flag-icons 7.3.2 静态旗帜素材及其 MIT 通知；[归档](../../frontend/licenses/README.md)保存清单、295 份许可/NOTICE 文件和来源摘要。不是把所有安装包或服务依赖都当作发布依赖。构建产物自身也包含全部许可文本；每次修改依赖、插件或打包入口后重建并更新归档。

elkjs 0.11.1 使用 EPL-2.0，未修改其模块。npm 发布元数据记录的源码提交为 `572e73323791d05f09b0815ff639af2b67f202ab`，可从[发布源码](https://github.com/kieler/elkjs/tree/572e73323791d05f09b0815ff639af2b67f202ab)取得构建与源文件；其 README/构建文件继续指向 Eclipse ELK 的上游源码。分发时应一并保留 NOTICE 中的源码获取说明，不能只交付压缩 JS。M4 在最终部署产物再次核对，包括 M2/M3 新加入的服务和插件。
