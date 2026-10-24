# 浏览器产物许可归档

M1-06 只核对当前独立前端的实际产物，不把初始 900 个安装版本当作最终发布清单，也不覆盖未来 Bun/Spring、模型、动态安装插件或素材的许可。

[artifact/inventory.json](artifact/inventory.json)记录实际进入浏览器 chunk 的包版本、声明许可、许可文件摘要及补充来源；[artifact/THIRD_PARTY_NOTICES.txt](artifact/THIRD_PARTY_NOTICES.txt)和 `artifact/texts/` 保存原文。构建将同一组原文放入 `dist/licenses/`，并生成 NOTICE 和清单；缺少原文或补充文件摘要不符会使构建失败。

当前为 260 个第三方包版本、11 个工作区包，另有 flag-icons 7.3.2 的静态旗帜素材及其原始 MIT 通知。生产构建后，在 `frontend/` 执行 `bun run licenses:archive`，将本次产物的 295 份许可/NOTICE 文件复制到 artifact 并写入 [SHA-256 清单](artifact/manifest.json)。归档的是当前实际产物；依赖改变后必须重新构建和归档。

仓库 `.gitattributes` 对许可归档及原始验证资料关闭 Git 文本换行转换，以保留原字节和摘要；不要批量格式化许可原文。原文中的行尾空格和换行属于所归档的来源内容。

[supplements.json](supplements.json)记录 npm 包未携带完整许可文件时的官方来源。12 个包从 npm 发布元数据的 `gitHead` 对应源码取得 LICENSE；dockview 的仓库迁移已解析到 `dockview/dockview`，未用当前分支代替发布提交。fastdom 的发布提交已不可取，但 npm 1.0.12 的 README 自带完整 MIT 原文，直接随产物保留。format 0.2.2 的发布 README 明确给出版权和 MIT 引用：原声明保存在 SOURCE-NOTICE.txt，另保留未改写的 SPDX MIT 参考文本，未伪造作者的 LICENSE 文件。补充文本允许末尾换行规范化，存档 SHA-256 与上游 blob 标识分别记录。

声明许可缺失的保留包通过实际许可原文核对：四个 `@stream-markdown/*`、format、khroma；未继续纳入旧 async 0.2.10。所有内部包归属保留 Toonflow MIT 全文；i18n 自带的 Element Plus 通知另行保留。DOMPurify 的双许可原文、elkjs 的 EPL-2.0 原文及其源码地址均保留；源码获取说明见 NOTICE 与 [前端迁入记录](../../docs/upstream/FRONTEND_INTAKE.md)。这不是整个 1.0 发布许可已经完成的声明，后续依赖或产物改变后必须重建清单。
