package cn.huimeng.dto.req;

/** 上传安装：文本插件给 source，技能/Agent 包给 base64；force 用于明确覆盖已有版本。 */
public record PluginInstallReqDTO(String fileName, String source, String base64, boolean force) {}
