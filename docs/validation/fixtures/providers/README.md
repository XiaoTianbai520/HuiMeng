# 供应商取证快照

当前实际安装以[现用清单](../../LOCAL_PROVIDERS.md)及[只读核对记录](../../evidence/current-local-providers.json)为准。本目录不作为自动安装或发布清单，不用旧源码覆盖用户替换后的供应商。

| 文件 | 状态 |
| --- | --- |
| h3Local3060.ts | M0 验证时的 2.0.0 快照；最新现用文件与此逐字节相同，原三模式证据仍可引用，不代表五模式全验收 |
| qwenImage21Local.ts | 现用 1.0.0 实测快照；文生图和单图编辑在 1K、1:1 真实生成、预览与刷新恢复通过 |
| localQwenImage21.ts | 旧 0.1.2 修复版，仅供历史追溯，已被 qwenImage21Local.ts 1.0.0 替换 |
| localQwenImage21-before-base64-fix.ts | 修正前 0.1.1，仅用于追溯旧 atob/FormData 错误 |

现用 Qwen 标识为 qwenImage21Local，模型为 qwenImage21Text 和 qwenImage21Edit；本轮独立归档源码、SHA-256 和实际生成证据，见[M0 最终收尾](../../M0_FINAL.md)。目录外的旧 localMiniMaxH3.ts/localQwenImage21.ts 同样只是历史夹具。历史证据与哈希见[M0 收尾](../../../archive/m0/M0_CLOSEOUT.md)。
