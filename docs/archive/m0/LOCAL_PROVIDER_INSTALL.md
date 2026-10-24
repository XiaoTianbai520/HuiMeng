# 原版 Toonflow 本地媒体供应商安装记录

> 历史取证归档：端口、文件状态和安装步骤仅代表采集当时；当前需求与接手步骤以[交接入口](../../handoff/README.md)为准，当前验证结论见[M0 最终收尾](../../validation/M0_FINAL.md)。本页不作为现用供应商安装指引。

> 2026-10-19。安装目标为用户克隆的原版 Toonflow 数据目录；不是绘梦 1.0 后端实现。

> **现用清单已更新（2026-10-22）**：用户已移除旧 `localMiniMaxH3.ts` 和 `localQwenImage21.ts`，现用 `h3Local3060.ts` 2.0.0（5 模型）及 `qwenImage21Local.ts` 1.0.0（2 模型），加载正常；现用 Qwen 两模型已补齐实际生成和刷新预览。详见[当前供应商清单](../../validation/LOCAL_PROVIDERS.md)及[M0 最终收尾](../../validation/M0_FINAL.md)。以下安装、哈希和修复段落为旧版本历史记录，不作为当前安装步骤。

## 历史安装记录

安装目录：`C:\Users\c1338\Desktop\workplace\toonflow\Toonflow-app\data\providers`。安装前已有内置供应商文件，两个目标文件均不存在。本次只新增以下文件，没有修改其他供应商或文本模型配置。

| 供应商文件 | 模型 | SHA-256 |
| --- | --- | --- |
| `localQwenImage21.ts` | `qwen-image-2.1`，文生图 | `ac3ea981843aa3fe612e98e562a61f31228c8e4634cc5bc201327fa268ac081e` |
| `localMiniMaxH3.ts` | `minimax-h3-local`，5 秒 480p 视频，含原生声音 | `3792203b64c1782ab3c7a836cb72e6f954fd95d815ea5a690c4c9e1fd9e61aba` |

两份源码来自[Qwen 适配器](../../validation/fixtures/localQwenImage21.ts)和[H3 适配器](../../validation/fixtures/localMiniMaxH3.ts)。安装前原版供应商校验接口均返回 HTTP 200、`code:200`；复制后 SHA-256 与源码一致。随后用固定提交的 Toonflow 运行时代码从**原版数据目录**加载两份源码，两个 runtime ID 和模型 ID 均正确，`listMediaProviders()` 的 `loadError` 为空。

生成时两个供应商都请求本机 `127.0.0.1:8188` 的 ComfyUI。它们参考用户本地工作流构造 API 图，不在运行时读取工作流 JSON。本次安装未重复执行图片或视频生成；此前的真实出图、出片记录分别见[Qwen 验证](QWEN_IMAGE_21_PROVIDER.md)与[H3 验证](MINIMAX_H3_PROVIDER.md)。H3 首帧和首尾帧模式仍未真实出片。

收尾时 ComfyUI `GET http://127.0.0.1:8188/system_stats` 返回 HTTP 200，版本 `0.37.0`，识别到 RTX 3060；Toonflow `3000` 尚未监听。要在 Web 页面生成，还需启动 Toonflow。本机克隆目录当时也没有 `build/web/index.html`，应以正常的开发/构建命令启动 Web，不能将供应商安装成功等同于完整平台可用。

## 2026-10-22 Qwen 兼容修正

当时发现旧 localQwenImage21 0.1.1 编辑分支依赖沙箱缺失的 atob/FormData。隔离复测通过后曾备份并安装 0.1.2，文生图与单图编辑链路通过；该供应商现已由用户替换，历史结论不转移给新 Qwen 1.0.0；现用版本后续已独立完成两模型生成，见[最终收尾](../../validation/M0_FINAL.md)。未修改 H3。[修正及测试](M0_CLOSEOUT.md#9-当前-qwen-图片与兼容修正)、[安装和备份路径](../../validation/evidence/qwen-sandbox-fix-install.json)。
