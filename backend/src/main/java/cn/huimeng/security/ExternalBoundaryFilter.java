package cn.huimeng.security;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates before protocol dispatch; M2 never forwards or fabricates protocol success. */
public class ExternalBoundaryFilter extends OncePerRequestFilter {
  private final ExternalClientService clients;
  private final AuditService audit;
  private final ObjectMapper json;

  public ExternalBoundaryFilter(
      ExternalClientService clients, AuditService audit, ObjectMapper json) {
    this.clients = clients;
    this.audit = audit;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Request-Id");
    String requestId =
        supplied != null && supplied.matches("[A-Za-z0-9_-]{1,128}")
            ? supplied
            : UUID.randomUUID().toString();
    response.setHeader("X-Request-Id", requestId);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json;charset=UTF-8");
    int status = 503;
    String code = "EXECUTOR_UNAVAILABLE";
    Object rpcId = null;
    boolean hasId = false;
    ExternalActor actor = null;
    try {
      actor = clients.authenticate(request.getHeader("Authorization"));
    } catch (ApiException ex) {
      status = ex.status;
      code = ex.code;
    }
    // Bounded parsing only preserves RPC correlation on gateway rejections. No SDK dispatch exists
    // yet.
    JsonNode body = null;
    try {
      byte[] bytes = request.getInputStream().readNBytes(65537);
      if (bytes.length > 65536) {
        if (status != 401) {
          status = 400;
          code = "INVALID_ARGUMENT";
        }
      } else if (bytes.length > 0) {
        body = json.readTree(bytes);
        if (body != null
            && body.isObject()
            && body.has("id")
            && (body.get("id").isTextual()
                || body.get("id").isNumber()
                || body.get("id").isNull())) {
          rpcId = body.get("id");
          hasId = true;
        }
      }
    } catch (Exception ignored) {
      if (status != 401) {
        status = 400;
        code = "INVALID_ARGUMENT";
      }
    }
    if (actor != null && status == 503) {
      try {
        if (request.getRequestURI().startsWith("/a2a/")) {
          String[] parts = request.getRequestURI().split("/");
          if (parts.length < 3) throw ApiException.forbidden();
          clients.authorize(actor, actor.projectId(), "A2A", parts[2]);
        } else if (body != null && "tools/call".equals(body.path("method").asText())) {
          clients.authorize(
              actor, actor.projectId(), "MCP", body.path("params").path("name").asText());
        }
      } catch (ApiException ex) {
        status = ex.status;
        code = ex.code;
      }
    }
    response.setStatus(status);
    if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
    Map<String, Object> data = Map.of("code", code, "requestId", requestId);
    if (hasId) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("jsonrpc", "2.0");
      result.put("id", rpcId);
      result.put("error", Map.of("code", -32099, "message", "请求未获执行", "data", data));
      json.writeValue(response.getOutputStream(), result);
    } else json.writeValue(response.getOutputStream(), data);
    audit.recordExternal(
        actor == null ? null : actor.clientId(),
        actor == null ? null : actor.projectId(),
        String.valueOf(status),
        requestId);
  }
}
