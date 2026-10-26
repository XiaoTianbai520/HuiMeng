package cn.huimeng.dto.req;

import com.fasterxml.jackson.databind.JsonNode;

/** 项目 Agent 文本生成请求；resendFrom/canvas 属于 M3-04，本批不解释执行。 */
public record AgentGenerateReqDTO(
    String directory,
    String prompt,
    String providerId,
    String modelId,
    String thinkingLevel,
    String sessionFile,
    String resendFrom,
    JsonNode mentions,
    JsonNode attachments,
    JsonNode canvas) {}
