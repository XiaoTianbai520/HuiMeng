package cn.huimeng.controller;

import cn.huimeng.dto.req.LoginReqDTO;
import cn.huimeng.dto.resp.*;
import cn.huimeng.service.SessionService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 会话接口；路由、状态码及 JSON 字段沿用冻结契约。 */
@RestController
@RequestMapping("/api/v1/auth")
public class IdentityController {
  private final SessionService sessions;

  public IdentityController(SessionService sessions) {
    this.sessions = sessions;
  }

  /** 获取当前会话防伪令牌。 */
  @GetMapping("/csrf")
  public CsrfRespDTO csrf(CsrfToken token) {
    return new CsrfRespDTO(token.getToken());
  }

  /** 验证账号后创建会话。 */
  @PostMapping("/login")
  public LoginRespDTO login(
      @Valid @RequestBody LoginReqDTO input,
      HttpServletRequest request,
      HttpServletResponse response) {
    return sessions.login(input, request, response);
  }

  /** 获取当前账号安全视图。 */
  @GetMapping("/me")
  public UserRespDTO me() {
    return sessions.currentUser();
  }

  /** 退出并清除当前 Cookie。 */
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    sessions.logout(request, response);
  }
}
