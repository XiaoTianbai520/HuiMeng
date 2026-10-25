package cn.huimeng.service.impl;

import cn.huimeng.dto.req.LoginReqDTO;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import jakarta.servlet.http.*;
import java.time.Instant;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;

/** 账号登录、退出与会话轮换；不接收浏览器自报身份。 */
@Service
public class SessionServiceImpl implements SessionService {
  private final UserService users;
  private final LoginLimiter limiter;
  private final AuditService audit;
  private final HttpSessionCsrfTokenRepository csrf;

  public SessionServiceImpl(
      UserService users,
      LoginLimiter limiter,
      AuditService audit,
      HttpSessionCsrfTokenRepository csrf) {
    this.users = users;
    this.limiter = limiter;
    this.audit = audit;
    this.csrf = csrf;
  }

  @Override
  public LoginRespDTO login(
      LoginReqDTO input, HttpServletRequest request, HttpServletResponse response) {
    limiter.check(request.getRemoteAddr(), input.username());
    Actor actor = users.authenticate(input.username(), input.password());
    limiter.success(input.username());
    var old = request.getSession(false);
    if (old != null) old.invalidate();
    var session = request.getSession(true);
    session.setAttribute("userId", actor.id());
    session.setAttribute("authVersion", actor.authVersion());
    session.setAttribute("born", Instant.now().getEpochSecond());
    csrf.saveToken(null, request, response);
    audit.record(
        actor.id(), null, "LOGIN", actor.id(), "SUCCESS", RequestContext.requestId(request));
    return new LoginRespDTO(users.get(actor.id()));
  }

  @Override
  public UserRespDTO currentUser() {
    return users.get(RequestContext.actor().id());
  }

  @Override
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    Actor actor = RequestContext.actor();
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    response.addHeader(
        "Set-Cookie", "__Host-huimeng-session=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
    audit.record(
        actor.id(), null, "LOGOUT", actor.id(), "SUCCESS", RequestContext.requestId(request));
  }
}
