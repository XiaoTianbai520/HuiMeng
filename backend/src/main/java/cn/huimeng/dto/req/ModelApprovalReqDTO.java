package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 批准或停用已登记的供应商模型。 */
public record ModelApprovalReqDTO(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String providerId,
    @NotBlank @Size(max = 128) String modelId,
    @NotNull Boolean enabled) {}
