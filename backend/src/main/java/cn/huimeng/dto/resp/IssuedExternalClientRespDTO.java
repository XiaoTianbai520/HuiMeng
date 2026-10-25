package cn.huimeng.dto.resp;

import cn.huimeng.dto.req.IssueExternalClientReqDTO;

/** 签发时唯一一次返回完整令牌；调试器和日志不得展开该敏感值。 */
public record IssuedExternalClientRespDTO(
    String id, String token, IssueExternalClientReqDTO scope) {
  @Override
  public String toString() {
    return "IssuedExternalClientRespDTO[redacted]";
  }
}
