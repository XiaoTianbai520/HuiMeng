package cn.huimeng.security;

import cn.huimeng.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

/** Explicit current-actor access for business services. */
public final class RequestContext {
  private RequestContext() {}

  public static Actor actor() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Actor actor)) {
      throw new ApiException(401, "UNAUTHENTICATED", "请先登录");
    }
    return actor;
  }

  public static Actor admin() {
    Actor actor = actor();
    if (!actor.role().equals("ADMIN")) throw ApiException.forbidden();
    return actor;
  }

  public static String requestId(HttpServletRequest request) {
    return (String) request.getAttribute("requestId");
  }
}
