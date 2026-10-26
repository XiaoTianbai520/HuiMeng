# M3-05 H3 五模式视频验收

> 2026-10-26。范围：本地 MiniMax H3 五个模式接入服务端媒体生成（t2v/i2v/fl2v/l2v/r2v）、时长与分辨率参数、参考图挂载、名额与取消。协议与编排用合成 ComfyUI（`m3M05H3Mock.py`）验证；另用仓库模板直连本机真实 ComfyUI 跑通一次 T2V 出片。

## 1. 结论

| 项 | 结果 |
| --- | --- |
| 五模式 | 通过：`h3T2v/h3I2v/h3Fl2v/h3L2v/h3R2v` 各自命中正确模板（前四者 `MiniMaxH3ImageToVideo`，R2V 为 `MiniMaxH3ReferenceToVideo`） |
| 规格 | 通过：`5s 352p 16:9 608×352`、`8s 480p 16:9 864×480`、`5s 352p 9:16 352×608`、`10s 544p 1:1 544×544`，帧数按 17k+5 网格（5 秒=124 帧） |
| 参考图 | 通过：I2V/L2V 各挂 1 张（首帧/尾帧节点），FL2V 同时挂首尾帧，R2V 逐张生成 `ref_images.ref_image_N` 节点；正文按模式补齐结构化提示词 |
| 产物 | 通过：每次生成落盘 `.huimeng-media/<id>.mp4`，媒体接口下载 200 且 `ftyp` 头正确 |
| 名额 | 通过：5 个慢任务占满后第 6 个 429，取消后 5 个名额全部释放 |
| 参数边界 | 通过：7 秒、1080p、21:9、I2V 缺帧、T2V 多帧均在开流前 400，且未触达执行器 |
| 真实出片（T2V） | 通过：仓库模板直连本机 ComfyUI，5 秒 352p 608×352、124 帧、turbo8，209.9 秒产出 5.17 秒 MP4（175,620 字节，`ftyp`，SHA-256 `38381ab5…`） |

81 项检查全部通过，见 [m3-m05-h3.json](evidence/m3-m05-h3.json)。

## 2. 实现要点

- **模板随代码分发**：从供应商定义导出 `h3-t2v/i2v/fl2v/l2v/r2v.json` 五个图模板（turbo8，R2V 用 turbo4），固定 `MiniMaxH3ImageToVideo`/`MiniMaxH3ReferenceToVideo` 与 `SaveVideo`；浏览器无法提供工作流或节点参数。
- **规格算法**：`352p/480p/544p` 三档基线（608×352、864×480、960×544），`16:9/9:16/1:1/4:3/3:4` 五种比例，除 16:9、9:16 直接取基线外其余按短边换算并 32 对齐；帧数 `round(秒×24)` 后补齐到 17k+5。
- **提示词结构**：T2V/I2V/FL2V/L2V 自动补 `integrated_multimodal_description`/`overall_soundscape`/`non_diegetic_music` 三段；R2V 补 `subject_definitions` 六段并枚举 `<Picture N>`。
- **名额与取消复用**：与图片同一条 `admit`/`execute` 通道，`mediaType=video` 走 `ComfyVideoClient`；取消时向执行器发 `/api/jobs/<id>/cancel` 并收敛为 `CANCELLED`。
- **前端**：模型下拉按已获批模型合并图片与视频；选中视频模型时切换为分辨率（352p/480p/544p）、比例（5 种）与时长（5/8/10/15 秒），按模式显示首帧/尾帧/参考图选择，按钮改为「生成视频」。

## 3. 真实出片（T2V）

本机 ComfyUI（`127.0.0.1:8188`）已装齐 `minimax_h3_fl2va/ref2va`、`qwen3vl_32b_minimax_h3`、视频/音频 VAE 与 turbo8/turbo4 LoRA，`MiniMaxH3ImageToVideo` 等自定义节点也在。用 `docs/validation/scripts/m3M05H3Real.py` 把仓库模板直接提交给执行器（不经后端）：

| 项 | 结果 |
| --- | --- |
| 规格 | 5s 352p 16:9 → 608×352、124 帧、turbo8（8 步） |
| 耗时 | 209.9 秒（RTX 3060） |
| 产物 | `evidence/m3-m05-h3-real.mp4`，175,620 字节，时长 5.17 秒（=124/24），`ftyp` 头正确 |
| 校验 | SHA-256 `38381ab527725234a36b05c65aee11f1e25ddde6e832d22dfdac35c566977cd0` |

## 4. 未覆盖

- **其余四模式的真实出片**：I2V/FL2V/L2V/R2V 只有合成执行器验证，未在真实权重上各跑一次（参考图上传与首尾帧挂载的真实行为待验）。
- **原生音频轨**：H3 会联合生成音频，本批只校验 MP4 容器与时长，未单独核听音轨内容。
- **参考视频/音频**：R2V 仅接通参考图（1–9 张），供应商支持的参考视频（≤3 段）与参考音频（≤3 段）未接入。
- **加速档位**：只提供 turbo8（R2V 为 turbo4），official/balanced/sparse 未暴露。

## 5. 环境清理

夹具容器、合成执行器、静态代理与转发均已停止；生成的 mp4 仅存在于夹具内存存储中。
