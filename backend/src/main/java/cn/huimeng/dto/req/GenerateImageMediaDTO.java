package cn.huimeng.dto.req;

/** 生成参考图只接受项目内虚拟路径，不接受服务器目录、URL 或物理文件名。 */
public record GenerateImageMediaDTO(String path, String mimeType) {}
