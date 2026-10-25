package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 创建项目元数据；工作区标识由服务端生成。 */
public record CreateProjectReqDTO(@NotBlank @Size(max = 128) String name) {}
