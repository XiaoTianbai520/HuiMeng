package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 新建项目目录的相对路径。 */
public record FilePathReqDTO(@NotBlank @Size(max = 2048) String path) {}
