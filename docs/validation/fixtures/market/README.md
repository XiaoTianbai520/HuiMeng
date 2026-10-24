# 插件市场合成夹具

这些文件仅用于 M0-06 的回声工具生命周期验证，不含业务逻辑、网络访问或文件操作。

| 文件 | 含义 |
| --- | --- |
| `m0MarketProbe.tool.js` | 1.0.0 初始版本 |
| `m0MarketProbeUpdate.tool.js` | 1.1.0 升级输入 |
| `m0MarketProbeExport.tool.js` | 1.1.0 导出原字节，已与安装输入对比 |
| `m0MarketProbeUi.tool.js` | 页面上传更新后的版本；执行时读取页面配置，实际 Agent 回声结果已归档 |

元数据标识均为 `m0MarketProbe`。用于安装时文件名必须是 `m0MarketProbe.tool.js`，文件名中的 Update/Export/Ui 仅区分归档用途。

节点/扩展分类验证由原构建产物生成隔离副本，未向此目录复制完整上游产物；扩展的兼容调整与原文件失败分别记录。官方市场技能仅保留下载摘要、目录元数据及截图，没有将包内指令作为本次测试指令执行。

参见 [M0-06 报告](../../../archive/m0/M0_PLUGIN_MARKET.md)及[后端复验脚本](../../scripts/README.md)。
