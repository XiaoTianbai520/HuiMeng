package cn.huimeng.security;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 外部协议入口的认证与能力边界：凭证、项目、工具/团队名单都在分派前校验，拒绝时保留 JSON-RPC 关联 id。
 *
 * <p>通过后把客户端身份绑定为 {@code EXTERNAL} 主体，并只在本次请求内暴露其单项目范围；控制器不信任请求体里的项目或身份字段。
 */
public class ExternalBoundaryFilter extends OncePerRequestFilter {
  private static final int MAX_BODY = 65536;
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
    request.setAttribute("requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    response.setHeader("Cache-Control", "no-store");

    JsonNode body = null;
    boolean hasId = false;
    Object rpcId = null;
    byte[] bytes = readBody(request);
    if (bytes.length > MAX_BODY) {
      reject(request, response, 400, "INVALID_ARGUMENT", null, false, requestId);
      return;
    }
    if (bytes.length > 0) {
      try {
        body = json.readTree(bytes);
      } catch (Exception ex) {
        reject(request, response, 400, "INVALID_ARGUMENT", null, false, requestId);
        return;
      }
      if (body != null && body.isObject() && body.has("id") && scalar(body.get("id"))) {
        rpcId = body.get("id");
        hasId = true;
      }
    }

    ExternalActor actor;
    try {
      actor = clients.authenticate(request.getHeader("Authorization"));
      // 名单在分派前判断：tools/list 只裁剪列表，tools/call 与 A2A 团队路径必须精确授权。
      if (request.getRequestURI().startsWith("/a2a/")) {
        String team = teamOf(request.getRequestURI());
        if (team == null) throw ApiException.forbidden();
        clients.authorize(actor, actor.projectId(), "A2A", team);
      } else if (body != null && "tools/call".equals(body.path("method").asText())) {
        String name = body.path("params").path("name").asText();
        if (name.isBlank()) throw new ApiException(400, "INVALID_ARGUMENT", "缺少工具名称");
        clients.authorize(actor, actor.projectId(), "MCP", name);
      }
    } catch (ApiException ex) {
      reject(request, response, ex.status, ex.code, rpcId, hasId, requestId);
      return;
    }

    ExternalCallContext.bind(actor);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new Actor(actor.clientId(), "external", "EXTERNAL", "ACTIVE", 0), null, List.of()));
    boolean failed = true;
    try {
      chain.doFilter(new CachedBodyRequest(request, bytes), response);
      failed = false;
    } finally {
      ExternalCallContext.clear();
      SecurityContextHolder.clearContext();
      int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
      audit.recordExternal(actor.clientId(), actor.projectId(), String.valueOf(status), requestId);
    }
  }

  private byte[] readBody(HttpServletRequest request) throws IOException {
    return request.getInputStream().readNBytes(MAX_BODY + 1);
  }

  private static boolean scalar(JsonNode node) {
    return node.isTextual() || node.isNumber() || node.isNull();
  }

  private static String teamOf(String uri) {
    String[] parts = uri.split("/");
    if (parts.length < 3) return null;
    String team = parts[2];
    return team.matches("[A-Za-z0-9_-]{1,128}") ? team : null;
  }

  /** 拒绝发生在协议分派之前：401/403/429 用 HTTP 状态，能解析 id 时按 JSON-RPC 错误回显。 */
  private void reject(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      Object rpcId,
      boolean hasId,
      String requestId)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", code);
    data.put("requestId", requestId);
    Object payload;
    if (hasId) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("jsonrpc", "2.0");
      result.put("id", rpcId);
      result.put(
          "error",
          Map.of(
              "code",
              -32099,
              "message",
              status == 401 ? "凭证无效或已撤销" : status == 403 ? "该客户端无权调用此能力" : "请求未获执行",
              "data",
              data));
      payload = result;
    } else payload = data;
    json.writeValue(response.getOutputStream(), payload);
    audit.recordExternal(null, null, String.valueOf(status), requestId);
  }

  /** 缓存请求体供过滤器解析一次、控制器再读一次；上限在读取时已校验。 */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var stream = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return stream.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          // 同步读取；MCP/A2A 不使用异步 Servlet 输入。
        }

        @Override
        public int read() {
          return stream.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return stream.read(buffer, offset, length);
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
  }
}
