package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.ComfyImageClient;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

/** 本机 HTTP 合成执行器验证协议和固定图，不连接 ComfyUI 或下载权重。 */
class ComfyImageClientTests {
  final ObjectMapper json = new ObjectMapper();
  final String run = "a4caf54e-6084-489c-94b8-c23a7b10f7a1";
  final AtomicReference<String> path = new AtomicReference<>();
  final AtomicReference<JsonNode> submitted = new AtomicReference<>();
  HttpServer server;
  ComfyImageClient client;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          byte[] body;
          if (path.get().equals("/prompt")) {
            submitted.set(json.readTree(exchange.getRequestBody()));
            body = ("{\"prompt_id\":\"" + run + "\"}").getBytes();
          } else if (path.get().endsWith("/cancel")) body = "{\"cancelled\":true}".getBytes();
          else if (path.get().equals("/view"))
            body = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1};
          else if (path.get().equals("/system_stats")) body = "{\"system\":{}}".getBytes();
          else body = "{\"status\":\"in_progress\"}".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    client = new ComfyImageClient(json, true, "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void bundledGraphExactlyRetainsReviewedQwen21TemplateIncludingVae() throws Exception {
    var original =
        json.readTree(
            Path.of("../docs/validation/fixtures/m2-acceptance/qwen-text-api.json").toFile());
    try (var resource = getClass().getResourceAsStream("/comfy/qwen-text.json")) {
      assertThat(json.readTree(resource)).isEqualTo(original);
    }
  }

  @Test
  void submittedGraphBindsServerOwnedProjectRunAndPrompt() {
    client.submit("synthetic-project", run, "synthetic cup");
    var body = submitted.get();
    assertThat(body.path("prompt_id").asText()).isEqualTo(run);
    assertThat(body.path("prompt").path("4").path("inputs").path("prompt").asText())
        .isEqualTo("synthetic cup");
    assertThat(body.path("prompt").path("9").path("inputs").path("filename_prefix").asText())
        .isEqualTo("HuiMeng/synthetic-project/" + run);
    assertThat(body.path("prompt").path("3").path("inputs").path("vae_name").asText())
        .isEqualTo("qwen_image_2.1_vae_bf16.safetensors");
  }

  @Test
  void cancellationUsesAtomicOwnedJobEndpointAndUnknownStatusesStayActive() {
    assertThat(client.cancel(run)).isTrue();
    assertThat(path.get()).isEqualTo("/api/jobs/" + run + "/cancel");
    assertThat(client.active(run)).isTrue();
  }

  @Test
  void outputMetadataCannotReadAnotherProjectsFiles() throws Exception {
    JsonNode record =
        json.readTree(
            "{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"../private.png\",\"subfolder\":\"other-project\",\"type\":\"output\"}]}}}");
    assertThatThrownBy(() -> client.output("own-project", run, record))
        .isInstanceOf(ApiException.class);
    assertThat(path.get()).isNull();
  }

  @Test
  void disabledClientRefusesExecutionWithoutContactingEndpoint() {
    var disabled =
        new ComfyImageClient(json, false, "http://127.0.0.1:" + server.getAddress().getPort());
    assertThatThrownBy(() -> disabled.submit("synthetic-project", run, "synthetic"))
        .isInstanceOf(ApiException.class);
    assertThat(path.get()).isNull();
  }

  @Test
  void readinessChecksComfyMetadataBeforeAdmission() {
    client.checkReady();
    assertThat(path.get()).isEqualTo("/system_stats");
  }

  @Test
  void windowsOutputSeparatorIsNormalizedWithoutRelaxingProjectBoundary() {
    var image =
        json.createObjectNode()
            .put("filename", run + "_00001_.png")
            .put("subfolder", "HuiMeng\\own-project")
            .put("type", "output");
    var record = json.createObjectNode();
    record.putObject("outputs").putObject("9").putArray("images").add(image);
    assertThat(client.output("own-project", run, record)).hasSize(9);
    image.put("subfolder", "HuiMeng\\..\\other-project");
    assertThatThrownBy(() -> client.output("own-project", run, record))
        .isInstanceOf(ApiException.class);
  }
}
