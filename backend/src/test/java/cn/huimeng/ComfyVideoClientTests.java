package cn.huimeng;

import static org.junit.jupiter.api.Assertions.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.ComfyVideoClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

/** 合成 ComfyUI：验证五模式的规格、参考图挂载、产物解析与参数拒绝。 */
class ComfyVideoClientTests {
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 0
  };
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private ComfyVideoClient client;
  private final AtomicReference<String> submitted = new AtomicReference<>();
  private final AtomicReference<String> uploads = new AtomicReference<>("0");

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/system_stats", exchange -> respond(exchange, "{\"system\":{}}"));
    server.createContext(
        "/upload/image",
        exchange -> {
          uploads.set(String.valueOf(Integer.parseInt(uploads.get()) + 1));
          exchange.getRequestBody().readAllBytes();
          respond(exchange, "{\"name\":\"ref.png\",\"subfolder\":\"\",\"type\":\"input\"}");
        });
    server.createContext(
        "/prompt",
        exchange -> {
          submitted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respond(exchange, "{\"prompt_id\":\"3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f\"}");
        });
    server.createContext(
        "/history/3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f",
        exchange ->
            respond(
                exchange,
                "{\"3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f\":{\"status\":{\"completed\":true},"
                    + "\"outputs\":{\"14\":{\"images\":[{\"filename\":\"clip.mp4\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}"));
    server.createContext(
        "/view",
        exchange -> {
          byte[] body = new byte[16];
          body[4] = 'f';
          body[5] = 't';
          body[6] = 'y';
          body[7] = 'p';
          respond(exchange, body);
        });
    server.start();
    client =
        new ComfyVideoClient(json, true, "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws IOException {
    respond(exchange, body.getBytes(StandardCharsets.UTF_8));
  }

  private void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body)
      throws IOException {
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  @Test
  void resolvesFiveModsWithFixedGrid() {
    var t2v = ComfyVideoClient.resolve("h3T2v", null, null, null);
    assertEquals("t2v", t2v.mode());
    assertEquals(608, t2v.width());
    assertEquals(352, t2v.height());
    assertEquals(124, t2v.frames());
    assertEquals("5s 352p 16:9 608×352", t2v.display());

    var fl2v = ComfyVideoClient.resolve("h3Fl2v", 10, "544p", "9:16");
    assertEquals("fl2v", fl2v.mode());
    assertEquals(544, fl2v.width());
    assertEquals(960, fl2v.height());
    assertEquals(5, fl2v.frames() % 17);
    assertEquals("10s 544p 9:16 544×960", fl2v.display());

    var square = ComfyVideoClient.resolve("h3R2v", 5, "480p", "1:1");
    assertEquals("r2v", square.mode());
    assertEquals(480, square.height());
    assertEquals(0, square.width() % 32);
  }

  @Test
  void rejectsUnknownDurationResolutionAndRatio() {
    var duration =
        assertThrows(
            ApiException.class,
            () -> ComfyVideoClient.resolve("h3T2v", 7, "352p", "16:9"));
    assertEquals("INVALID_ARGUMENT", duration.code);
    var resolution =
        assertThrows(
            ApiException.class,
            () -> ComfyVideoClient.resolve("h3T2v", 5, "1080p", "16:9"));
    assertEquals("INVALID_ARGUMENT", resolution.code);
    var ratio =
        assertThrows(
            ApiException.class,
            () -> ComfyVideoClient.resolve("h3T2v", 5, "352p", "21:9"));
    assertEquals("INVALID_ARGUMENT", ratio.code);
    assertThrows(ApiException.class, () -> ComfyVideoClient.resolve("h3Unknown", 5, null, null));
  }

  @Test
  void textToVideoSubmitsPromptAndPixelGrid() {
    var spec = ComfyVideoClient.resolve("h3T2v", 5, "352p", "16:9");
    String promptId = client.submit("p1", "run-1", spec, "红色的玩具车向前滚动", List.of());
    assertEquals("3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f", promptId);
    var body = submitted.get();
    assertTrue(body.contains("\"width\":608"), body);
    assertTrue(body.contains("\"height\":352"), body);
    assertTrue(body.contains("\"length\":124"), body);
    assertTrue(body.contains("integrated_multimodal_description"), body);
    assertTrue(body.contains("HuiMeng/p1/run-1"), body);
    assertEquals("0", uploads.get());
    assertNotNull(client.history(promptId));
    byte[] video = client.output(promptId, client.history(promptId));
    assertEquals('f', video[4]);
  }

  @Test
  void referenceModesUploadEveryFrame() {
    var i2v = ComfyVideoClient.resolve("h3I2v", 5, "352p", "16:9");
    client.submit("p1", "run-2", i2v, "首帧动画", List.of(PNG));
    assertEquals("1", uploads.get());
    assertTrue(submitted.get().contains("\"image\":\"ref.png\""), submitted.get());

    uploads.set("0");
    var fl2v = ComfyVideoClient.resolve("h3Fl2v", 5, "352p", "16:9");
    client.submit("p1", "run-3", fl2v, "首尾过渡", List.of(PNG, PNG));
    assertEquals("2", uploads.get());
  }

  @Test
  void referenceToVideoWiresEveryReferenceNode() {
    var r2v = ComfyVideoClient.resolve("h3R2v", 5, "352p", "16:9");
    client.submit("p1", "run-4", r2v, "两个角色同框", List.of(PNG, PNG));
    String body = submitted.get();
    assertEquals("2", uploads.get());
    assertTrue(body.contains("\"ref_images.ref_image_0\""), body);
    assertTrue(body.contains("\"ref_images.ref_image_1\""), body);
    assertTrue(body.contains("subject_definitions:"), body);
  }
}
