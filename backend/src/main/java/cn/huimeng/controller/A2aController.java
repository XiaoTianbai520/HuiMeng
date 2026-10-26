package cn.huimeng.controller;

import cn.huimeng.service.A2aService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * A2A 协议 1.0 入口：Agent Card 与 SendMessage/GetTask/CancelTask。
 *
 * <p>认证与团队授权由外部边界过滤器在分派前完成；协议帧与状态码由服务层生成，控制器只补响应头。
 */
@RestController
public class A2aController {
  private static final MediaType JSON =
      new MediaType("application", "json", StandardCharsets.UTF_8);
  private final A2aService a2a;

  public A2aController(A2aService a2a) {
    this.a2a = a2a;
  }

  @GetMapping("/a2a/{teamName}")
  public ResponseEntity<JsonNode> card(@PathVariable String teamName, HttpServletRequest request) {
    // 卡片地址必须指向当前入口，不能保留上游 Bun 地址。
    String base =
        ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath(null)
            .replaceQuery(null)
            .build()
            .toUriString();
    return ResponseEntity.ok().contentType(JSON).body(a2a.card(teamName, base));
  }

  @PostMapping("/a2a/{teamName}")
  public ResponseEntity<String> rpc(
      @PathVariable String teamName,
      @RequestHeader(value = "A2A-Version", required = false) String version,
      @RequestBody(required = false) JsonNode body,
      HttpServletRequest request) {
    var reply = a2a.rpc(teamName, body, version, (String) request.getAttribute("requestId"));
    if (reply.status() == HttpStatus.ACCEPTED.value())
      return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    return ResponseEntity.status(reply.status()).contentType(JSON).body(reply.body());
  }
}
