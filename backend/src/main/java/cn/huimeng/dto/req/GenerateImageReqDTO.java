package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;
import java.util.List;

/** 现用 Qwen 两模型的受控图片生成；不接受任意工作流、端点或物理路径。 */
public record GenerateImageReqDTO(
    @NotBlank @Size(max = 128) String providerId,
    @NotBlank @Size(max = 128) String modelId,
    @NotBlank String mediaType,
    @NotBlank @Size(max = 8000) String prompt,
    @Size(max = 16) String size,
    @Size(max = 8) String ratio,
    @Size(max = 1) List<GenerateImageMediaDTO> images) {}
