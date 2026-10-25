package cn.huimeng.dto.resp;

/** 兼容层使用项目内虚拟相对路径，真实对象定位只保存在服务端。 */
public record GeneratedMediaRespDTO(String path, String mimeType, String mediaType) {}
