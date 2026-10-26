package cn.huimeng.dto.req;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.List;

/** 只有 prepare/cancel/transcode/concat/extractAudio；参数按固定预设解释，不直接拼进命令行。 */
public record FfmpegOperationDTO(
    @NotBlank @Size(max = 64) String method, @Size(max = 128) List<JsonNode> args) {}
