package cn.huimeng.controller;

import cn.huimeng.service.McpProtocolService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * MCP 协议入口（Streamable HTTP 子集）。
 *
 * <p>认证、项目与工具名单已在边界过滤器完成；这里只把服务层生成的协议帧写成 SSE 或 notification 的 202。
 */
@RestController
public class McpController {
  private static final String SESSION_HEADER = "Mcp-Session-Id";
  private static final MediaType EVENT_STREAM =
      new MediaType("text", "event-stream", StandardCharsets.UTF_8);
  private final McpProtocolService protocol;

  public McpController(McpProtocolService protocol) {
    this.protocol = protocol;
  }

  @PostMapping("/mcp")
  public ResponseEntity<String> rpc(
      @RequestBody(required = false) JsonNode body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    var reply = protocol.handle(body, idempotencyKey, (String) request.getAttribute("requestId"));
    if (reply.status() == HttpStatus.ACCEPTED.value())
      return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(reply.status()).contentType(EVENT_STREAM);
    if (reply.sessionId() != null) builder.header(SESSION_HEADER, reply.sessionId());
    return builder.body(reply.body());
  }

  /** 不提供服务端主动推送流；客户端只需 POST。 */
  @GetMapping("/mcp")
  public ResponseEntity<Void> stream() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .allow(HttpMethod.POST, HttpMethod.DELETE)
        .build();
  }

  @DeleteMapping("/mcp")
  public ResponseEntity<Void> close() {
    return ResponseEntity.noContent().build();
  }
}
