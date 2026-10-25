package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 当前最小批次仅接通现用 Qwen 文生图；不接受任意工作流、端点或物理路径。 */
public record GenerateImageReqDTO(
    @NotBlank @Size(max = 128) String providerId,
    @NotBlank @Size(max = 128) String modelId,
    @NotBlank String mediaType,
    @NotBlank @Size(max = 8000) String prompt,
    String size,
    String ratio) {}
