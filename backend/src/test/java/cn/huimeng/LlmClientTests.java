package cn.huimeng;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.LlmClient;
import cn.huimeng.dao.mapper.ConfigurationMapper;
import cn.huimeng.security.SecretBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

/** 本地合成 OpenAI 兼容 SSE 服务；不访问真实供应商。 */
class LlmClientTests {
  private HttpServer server;
  private LlmClient client;
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    ConfigurationMapper configuration = mock(ConfigurationMapper.class);
    when(configuration.encryptedSecret("deepseek")).thenReturn("encrypted");
    SecretBox box = mock(SecretBox.class);
    when(box.decrypt("deepseek", "encrypted")).thenReturn("test-key");
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    client = new LlmClient(json, configuration, box, true, endpoint, endpoint);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void streamsDeltasAndValidatesRequest() throws Exception {
    server.createContext(
        "/chat/completions",
        exchange -> {
          assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
          var body = json.readTree(exchange.getRequestBody());
          assertEquals("deepseek-chat", body.path("model").asText());
          assertTrue(body.path("stream").asBoolean());
          assertEquals("user", body.path("messages").path(0).path("role").asText());
          String response =
              "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                  + "data: {\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}\n\n"
                  + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                  + "data: [DONE]\n\n";
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
          exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    try (var stream =
        client.chat(
            "deepseek", "deepseek-chat", List.of(Map.of("role", "user", "content", "hi")))) {
      assertEquals("你好", stream.nextDelta());
      assertEquals("世界", stream.nextDelta());
      assertNull(stream.nextDelta());
      assertNull(stream.nextDelta());
    }
  }

  @Test
  void propagatesProviderFailureAsSafeError() {
    server.createContext(
        "/chat/completions",
        exchange -> {
          byte[] body = "{\"error\":\"upstream\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    var error =
        assertThrows(
            ApiException.class,
            () ->
                client.chat(
                    "deepseek", "deepseek-chat", List.of(Map.of("role", "user", "content", "hi"))));
    assertEquals(503, error.status);
    assertEquals("PROVIDER_REQUEST_FAILED", error.code);
  }

  @Test
  void rejectsMissingSecretAndUnsupportedProvider() {
    ConfigurationMapper configuration = mock(ConfigurationMapper.class);
    SecretBox box = mock(SecretBox.class);
    LlmClient unconfigured =
        new LlmClient(json, configuration, box, true, "http://127.0.0.1:1", "http://127.0.0.1:1");
    var missing =
        assertThrows(
            ApiException.class,
            () ->
                unconfigured.chat(
                    "deepseek", "deepseek-chat", List.of(Map.of("role", "user", "content", "hi"))));
    assertEquals("PROVIDER_NOT_CONFIGURED", missing.code);
    var unsupported =
        assertThrows(
            ApiException.class,
            () -> client.chat("other", "model", List.of(Map.of("role", "user", "content", "hi"))));
    assertEquals(501, unsupported.status);
    assertEquals("PROVIDER_NOT_IMPLEMENTED", unsupported.code);
  }

  @Test
  void disabledClientFailsClosed() {
    LlmClient disabled =
        new LlmClient(
            json,
            mock(ConfigurationMapper.class),
            mock(SecretBox.class),
            false,
            "http://127.0.0.1:1",
            "http://127.0.0.1:1");
    assertThrows(
        ApiException.class,
        () ->
            disabled.chat(
                "deepseek", "deepseek-chat", List.of(Map.of("role", "user", "content", "hi"))));
  }
}
