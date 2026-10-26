package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;
import java.util.List;

/** 受控媒体生成：图片走 Qwen 两模型，视频走本地 H3 五模式；不接受任意工作流、端点或物理路径。 */
public record GenerateImageReqDTO(
    @NotBlank @Size(max = 128) String providerId,
    @NotBlank @Size(max = 128) String modelId,
    @NotBlank String mediaType,
    @NotBlank @Size(max = 8000) String prompt,
    @Size(max = 16) String size,
    @Size(max = 8) String ratio,
    @Size(max = 9) List<GenerateImageMediaDTO> images,
    @Min(1) @Max(60) Integer duration) {}
