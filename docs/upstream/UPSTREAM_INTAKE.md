# Toonflow 上游源码引入记录

> 状态：M0-01 的固定基线与初始依赖扫描记录。M1 已按指定提交选择性引入独立前端并归档当前浏览器产物许可，见[前端引入记录](FRONTEND_INTAKE.md)；Bun 执行器尚未引入。下述初始扫描不是当前发布依赖清单。

## 1. 已核实的基线

| 项 | 结果 |
| --- | --- |
| 本机参考克隆 | `C:\Users\c1338\Desktop\workplace\toonflow\Toonflow-app` |
| 上游仓库 | `https://github.com/HBAI-Ltd/Toonflow-app.git` |
| HEAD | `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d` |
| 提交主题与时间 | `更新README`，2026-10-13 17:59:53 +0800 |
| 根许可证 | `LICENSE` 为 MIT，Copyright (c) 2026 HBAI-Ltd |
| 包管理器 | 根 `package.json` 指定 `bun@1.3.14` |
| 安装锁定文件 | 根 `bun.lock` 存在 |
| 工作区结构 | `apps/`、`packages/`、`patches/`；克隆中有 `node_modules/`，该目录不纳入源码 |

M0 采集时曾核对参考克隆的 `git status`：`apps/server/src/router.ts`、`apps/web/src/types/components.d.ts` 被标为修改，但二者的 `git hash-object` 均与固定提交中的对应 blob 相同，没有内容差异。为隔离工作区状态，M0 运行副本直接由 `git archive HEAD` 导出至绘梦仓库忽略的 `.m0-work/`。后续源码引入也应以指定的固定提交导出，不从参考克隆的工作目录直接复制；接手时重新检查 HEAD 和工作区状态，不能将本节的采集记录当作当前状态。

## 2. 选定的代码引入方式

1. 参考克隆继续放在绘梦仓库外，作为固定提交的只读比对基线；不把上游 `.git`、`node_modules/`、`data/`、构建产物或本机配置复制进绘梦。
2. 完成 M0 路由/功能基线后，**按模块选择性引入**原 Web、Bun 执行服务及其实际依赖的内部包，保留文件来源、原路径和上游提交记录。目标目录遵循本仓库约定：前端代码在 `frontend/`，Spring 与 Bun 服务代码在 `backend/`，共享设计和契约在 `docs/`。
3. 引入时先保持可运行的 Bun 包依赖关系，再由 M1 的前端适配层解除 Web 对 `@toonflow/server` 的直接依赖。不要仅复制 `apps/web` 和 `apps/server` 后假设可以构建。
4. 插件市场及已安装能力管理属于 1.0 现行范围；桌面/移动应用及应用更新服务仍排除。内置及获批第三方节点、工具、技能、扩展、Agent 包属于需进一步核对的动态加载模块，不能只根据静态依赖图删除。
5. 首次引入代码时保留上游 MIT 许可与版权文本，并为改造后的模块建立 README、构建命令和回归记录。具体文件映射须随实际构建结果在 M1 固定。

这是一种选择性迁移方案，不需要在绘梦仓库中再放一份完整的上游 Git 仓库。若引入阶段发现动态加载模块无法安全拆开，应先维持必要的 Bun 工作区依赖结构，再分批移动到目标目录，不以目录整洁牺牲功能基线。

内置节点包（`packages/nodeShared` 的子集与 `packages/nodes/textNode`、`imageNode`）按同一固定提交选择性引入，映射、依赖装配、构建命令与产物摘要在[节点包引入记录](NODE_PACKAGES_INTAKE.md)。

## 3. 依赖扫描结果

扫描依据是固定提交的根及工作区 `package.json`、当前安装的 `node_modules/.bun`。以 Web 与 Server 为起点，递归追踪清单中声明的 `workspace:*` 依赖，得到 **17 个内部工作区包**（含 Web、Server）和 **83 个不同名称的直接第三方依赖**。其中内部包包含 `@toonflow/providers`、`@toonflow/mcp`、`@toonflow/tools-scaffold`、`@toonflow/tool-media-generation`、`@toonflow/file` 等；前端清单还直接依赖 `@toonflow/server`。动态发现的节点、技能和工具尚未全部包含在这一静态闭包中。

[第三方许可证初始台账](third-party-license-inventory.csv)按包名和已安装版本去重，列出本地安装目录中发现的 **900 个包版本**，包含：

- 包名、已安装版本、`package.json` 的声明许可证、包根目录的许可/通知文件名；
- 是否出现在 Web/Server 静态工作区闭包的直接依赖中，以及声明版本范围；
- 相对参考克隆的安装路径，便于复核原件。

这 900 个包版本是**整个上游本机安装环境**的扫描结果，可能包括桌面/移动/更新服务、构建工具和不随 1.0 发布的传递依赖；不能当作最终产品的依赖数量。许可证列是包元数据，不等于已逐项阅读许可证原文或完成合规判断。

当前有 7 个包版本缺少 `package.json` 的许可证字段：`@stream-markdown/code@1.1.0`、`@stream-markdown/core@1.1.0`、`@stream-markdown/math@1.1.0`、`@stream-markdown/mermaid@1.1.0`、`async@0.2.10`、`format@0.2.2`、`khroma@2.1.0`。M1 确定实际发布依赖后，只对保留的包逐项核对许可证原文、NOTICE 和分发要求，并生成随产品交付的归属清单。

## 4. 后续引入前检查

- 实际复制时继续从固定提交导出干净源码，避免本机状态混入；
- 复用已完成的 M0-04 功能/路由台账，并在实际引入时确认动态加载的内置能力和要迁移的文件；
- M1-02/03 的实际构建中验证目录映射、Bun 工作区依赖与前端独立构建；
- M1-06 对最终纳入的第三方包核对许可证原文及必要的 NOTICE/版权保留，并与上游 MIT 文本一起归档。
