package cn.huimeng.dto.req;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

/** 页面执行画布操作后的回执；error 与 result 互斥，由页面决定。 */
public record AgentCanvasResultReqDTO(
    String directory,
    @NotBlank @Size(max = 64) String callId,
    JsonNode result,
    @Size(max = 8000) String error) {}
