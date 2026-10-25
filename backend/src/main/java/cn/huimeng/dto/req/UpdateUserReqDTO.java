package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 管理员变更账号；未传字段保持不变，密码不参与文本输出。 */
public record UpdateUserReqDTO(
    @Pattern(regexp = "ACTIVE|DISABLED") String status,
    @Pattern(regexp = "ADMIN|USER") String role,
    @Size(min = 12, max = 256) String password) {
  @Override
  public String toString() {
    return "UpdateUserReqDTO[redacted]";
  }
}
