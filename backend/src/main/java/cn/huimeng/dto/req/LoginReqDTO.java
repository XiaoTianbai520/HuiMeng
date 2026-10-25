package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 登录输入；密码不参与对象文本输出。 */
public record LoginReqDTO(
    @NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 256) String password) {
  @Override
  public String toString() {
    return "LoginReqDTO[redacted]";
  }
}
