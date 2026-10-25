package cn.huimeng.dto.resp;

/** 可导出的模型批准记录；不导出任意 options、服务端端点或运行凭据。 */
public record ExportedModelRespDTO(String providerId, String modelId, boolean enabled) {}
