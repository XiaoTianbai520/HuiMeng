# 受控 Qwen 2.1 图

qwen-text.json 复用当前确认的 qwenImage21Local 1.0.0 模板，原图及来源在 docs/validation/fixtures/m2-acceptance/。运行时只替换提示词、随机种子和服务端项目/运行输出前缀；保持 1K、1:1、25 步及 2.1 VAE 配对。不下载模型、不更新节点，不接受客户端工作流或文件名。

ComfyImageClientTests 将此图与已确认夹具逐字段比较，并核对 Windows 结果路径和精确目标取消。后续模型/规格迁移须单独更新契约、模板和测试；原 Qwen 2K 元数据继续保留。
