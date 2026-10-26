package cn.huimeng.dto.req;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.List;

/** 沿用冻结契约：requestId 绑定发起人+项目，operation 只接受固定方法。 */
public record FfmpegExecuteReqDTO(
    @NotBlank @Size(max = 128) String requestId,
    String directory,
    JsonNode options,
    @Size(max = 2048) List<JsonNode> calls,
    @NotNull FfmpegOperationDTO operation) {}
