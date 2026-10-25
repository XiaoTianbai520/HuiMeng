package cn.huimeng.controller;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.ExternalClientService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 管理员签发、分页与撤销外部凭证；签发响应禁止缓存。 */
@RestController
@RequestMapping("/api/v1/admin/external-clients")
public class ExternalClientController {
  private final ExternalClientService clients;

  public ExternalClientController(ExternalClientService clients) {
    this.clients = clients;
  }

  /** 签发单项目外部客户端令牌；仅本次响应包含明文。 */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public IssuedExternalClientRespDTO issue(
      @Valid @RequestBody IssueExternalClientReqDTO input, HttpServletRequest request) {
    return clients.issue(input, RequestContext.requestId(request));
  }

  /** 分页读取不含令牌和哈希的客户端视图。 */
  @GetMapping
  public PageRespDTO<ExternalClientRespDTO> list(
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
    return clients.list(cursor, limit);
  }

  /** 撤销客户端凭证并追加审计。 */
  @PostMapping("/{clientId}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable String clientId, HttpServletRequest request) {
    clients.revoke(clientId, RequestContext.requestId(request));
  }
}
