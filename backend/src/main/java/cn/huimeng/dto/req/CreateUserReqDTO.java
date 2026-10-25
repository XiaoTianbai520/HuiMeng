package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 管理员创建账号；角色为空时按普通成员创建。 */
public record CreateUserReqDTO(
    @NotBlank @Size(max = 128) String username,
    @NotNull @Size(min = 12, max = 256) String password,
    @Pattern(regexp = "ADMIN|USER") String role) {
  @Override
  public String toString() {
    return "CreateUserReqDTO[redacted]";
  }
}
