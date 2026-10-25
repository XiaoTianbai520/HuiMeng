package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 变更项目名称或 ACTIVE/ARCHIVED 状态。 */
public record UpdateProjectReqDTO(
    @Size(min = 1, max = 128) String name, @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {}
