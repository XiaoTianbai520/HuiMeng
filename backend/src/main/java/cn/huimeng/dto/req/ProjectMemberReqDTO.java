package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 管理员指定成员账号；不能据此绕过服务端项目授权。 */
public record ProjectMemberReqDTO(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String userId) {}
