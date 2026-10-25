package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 文件/目录的项目内目标路径，不能覆盖已有目标。 */
public record FileTransferReqDTO(
    @NotBlank @Size(max = 2048) String path, @NotBlank @Size(max = 2048) String target) {}
