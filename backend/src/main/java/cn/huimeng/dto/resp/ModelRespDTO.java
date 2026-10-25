package cn.huimeng.dto.resp;

import com.fasterxml.jackson.databind.JsonNode;

/** 登记模型的安全元数据及批准状态。 */
public record ModelRespDTO(
    String providerId,
    String modelId,
    String label,
    boolean enabled,
    JsonNode capabilities,
    JsonNode options) {}
