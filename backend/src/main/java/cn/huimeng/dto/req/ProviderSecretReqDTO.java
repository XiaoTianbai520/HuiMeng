package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 团队只写密钥；不提供读取接口，文本输出始终脱敏。 */
public record ProviderSecretReqDTO(@NotBlank @Size(max = 16384) String apiKey) {
  @Override
  public String toString() {
    return "ProviderSecretReqDTO[redacted]";
  }
}
