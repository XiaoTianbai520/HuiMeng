package cn.huimeng.dto.req;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

/** 页面回答 Agent 提问；取消或跳过也要回传，避免后端一直等待。 */
public record AgentAnswerReqDTO(
    String directory,
    @NotBlank @Size(max = 64) String callId,
    Boolean cancelled,
    Boolean skipped,
    @Size(max = 8000) String answer,
    JsonNode values) {}
