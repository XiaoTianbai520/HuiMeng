package cn.huimeng.dto.resp;

/** 当前会话的防伪令牌。 */
public record CsrfRespDTO(String token) {
  @Override
  public String toString() {
    return "CsrfRespDTO[redacted]";
  }
}
