package cn.huimeng.security;

import cn.huimeng.common.exception.GlobalExceptionHandler;
import cn.huimeng.service.AuditService;
import cn.huimeng.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revalidates sessions and enforces fixed-origin writes before CSRF checks. */
public class BoundaryFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(BoundaryFilter.class);
  private final UserService users;
  private final AuditService audit;
  private final String origin;
  private final OperationLimiter writes = new OperationLimiter();

  public BoundaryFilter(UserService users, AuditService audit, String origin) {
    this.users = users;
    this.audit = audit;
    this.origin = origin;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String input = request.getHeader("X-Request-Id");
    String requestId =
        input != null && input.matches("[A-Za-z0-9_-]{1,128}")
            ? input
            : UUID.randomUUID().toString();
    request.setAttribute("requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    response.setHeader("Cache-Control", "no-store");
    long start = System.nanoTime();
    try {
      var session = request.getSession(false);
      if (session != null && session.getAttribute("userId") instanceof String id) {
        Actor actor = users.findActor(id);
        boolean valid =
            actor != null
                && actor.status().equals("ACTIVE")
                && Long.valueOf(actor.authVersion()).equals(session.getAttribute("authVersion"))
                && session.getAttribute("born") instanceof Long born
                && Instant.now().getEpochSecond() - born < 86400;
        if (!valid) session.invalidate();
        else
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new UsernamePasswordAuthenticationToken(
                      actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))));
      }
      var origins = java.util.Collections.list(request.getHeaders("Origin"));
      if (!List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
          && (origins.size() != 1 || !origin.equals(origins.get(0)))) {
        GlobalExceptionHandler.write(request, response, 403, "CSRF_INVALID", "请求来源或防伪令牌无效");
        return;
      }
      if (!List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
          && SecurityContextHolder.getContext().getAuthentication() != null) {
        try {
          writes.check(RequestContext.actor().id(), Instant.now().getEpochSecond());
        } catch (cn.huimeng.common.exception.ApiException ex) {
          GlobalExceptionHandler.write(request, response, ex.status, ex.code, ex.getMessage());
          return;
        }
      }
      chain.doFilter(request, response);
    } finally {
      Object principal =
          SecurityContextHolder.getContext().getAuthentication() == null
              ? null
              : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
      String actorId = principal instanceof Actor actor ? actor.id() : null;
      if (response.getStatus() == 401 || response.getStatus() == 403)
        audit.record(
            actorId, null, "ACCESS_DENIED", null, String.valueOf(response.getStatus()), requestId);
      LOG.info(
          "requestId={} actorId={} route={} status={} durationMs={}",
          requestId,
          actorId,
          request.getAttribute(
              org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
          response.getStatus(),
          (System.nanoTime() - start) / 1_000_000);
      SecurityContextHolder.clearContext();
    }
  }
}
