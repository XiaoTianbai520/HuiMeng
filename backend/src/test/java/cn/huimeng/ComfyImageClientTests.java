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
  final AtomicReference<byte[]> uploaded = new AtomicReference<>();
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
          } else if (path.get().equals("/upload/image")) {
            uploaded.set(exchange.getRequestBody().readAllBytes());
            body =
                ("{\"name\":\"huimeng-" + run + ".png\",\"subfolder\":\"\",\"type\":\"input\"}")
                    .getBytes();
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

  private static byte[] png(int size) {
    byte[] bytes = new byte[size];
    byte[] magic = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    System.arraycopy(magic, 0, bytes, 0, magic.length);
    return bytes;
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
  void bundledEditGraphRetainsReviewedReferenceAndVaeConditioning() throws Exception {
    try (var resource = getClass().getResourceAsStream("/comfy/qwen-edit.json")) {
      var graph = json.readTree(resource);
      assertThat(graph.path("4").path("inputs").path("vae").path(0).asText()).isEqualTo("3");
      assertThat(graph.path("4").path("inputs").path("images.image_1").path(0).asText())
          .isEqualTo("10");
      assertThat(graph.path("10").path("class_type").asText()).isEqualTo("LoadImage");
      assertThat(graph.path("9").path("inputs").path("filename_prefix").asText())
          .isEqualTo("QwenImage21Local/imageToImage");
    }
  }

  @Test
  void specDefaultsToOneKiloSquareAndAlignsToThirtyTwo() {
    var value = ComfyImageClient.resolve("qwenImage21Text", null, null);
    assertThat(value.size()).isEqualTo("1K");
    assertThat(value.ratio()).isEqualTo("1:1");
    assertThat(value.resolution()).isEqualTo(1024);
    assertThat(value.width()).isEqualTo(1024);
    assertThat(value.height()).isEqualTo(1024);
    var wide = ComfyImageClient.resolve("qwenImage21Text", "2K", "16:9");
    assertThat(wide.width() % 32).isZero();
    assertThat(wide.height() % 32).isZero();
    assertThat(wide.width()).isGreaterThan(wide.height());
    assertThat(wide.resolution()).isEqualTo(2048);
    var small = ComfyImageClient.resolve("qwenImage21Edit", "0.5MP", "9:16");
    assertThat(small.width()).isLessThan(small.height());
    assertThat(small.resolution()).isEqualTo(736);
  }

  @Test
  void unknownSizeRatioOrModelIsRejectedBeforeAnyRequest() {
    assertThatThrownBy(() -> ComfyImageClient.resolve("qwenImage21Text", "4K", "1:1"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ComfyImageClient.resolve("qwenImage21Text", "1K", "5:4"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ComfyImageClient.resolve("h3T2v", "1K", "1:1"))
        .isInstanceOf(ApiException.class);
    assertThat(path.get()).isNull();
  }

  @Test
  void submittedGraphBindsServerOwnedProjectRunSpecAndPrompt() {
    String promptId =
        client.submit(
            "synthetic-project",
            run,
            "qwenImage21Text",
            ComfyImageClient.resolve("qwenImage21Text", "2K", "16:9"),
            "synthetic cup",
            null);
    assertThat(promptId).isEqualTo(run);
    var body = submitted.get();
    assertThat(body.path("prompt_id").asText()).isEqualTo(run);
    assertThat(body.path("prompt").path("4").path("inputs").path("prompt").asText())
        .isEqualTo("synthetic cup");
    assertThat(body.path("prompt").path("4").path("inputs").path("resolution").asInt())
        .isEqualTo(2048);
    assertThat(body.path("prompt").path("5").path("inputs").path("width").asInt() % 32).isZero();
    assertThat(body.path("prompt").path("9").path("inputs").path("filename_prefix").asText())
        .isEqualTo("HuiMeng/synthetic-project/" + run);
    assertThat(body.path("prompt").path("3").path("inputs").path("vae_name").asText())
        .isEqualTo("qwen_image_2.1_vae_bf16.safetensors");
    assertThat(uploaded.get()).isNull();
  }

  @Test
  void editModelUploadsReferenceAndBindsServerOwnedName() {
    client.submit(
        "synthetic-project",
        run,
        "qwenImage21Edit",
        ComfyImageClient.resolve("qwenImage21Edit", "1K", "1:1"),
        "把杯子改成蓝色",
        png(64));
    assertThat(new String(uploaded.get(), java.nio.charset.StandardCharsets.ISO_8859_1))
        .contains("name=\"image\"; filename=\"huimeng-" + run + ".png\"");
    var graph = submitted.get().path("prompt");
    assertThat(graph.path("10").path("inputs").path("image").asText())
        .isEqualTo("huimeng-" + run + ".png");
    assertThat(graph.path("4").path("inputs").path("images.image_1").path(0).asText())
        .isEqualTo("10");
    assertThat(graph.path("9").path("inputs").path("filename_prefix").asText())
        .isEqualTo("HuiMeng/synthetic-project/" + run);
  }

  @Test
  void editModelRejectsMissingOrForeignReferenceAndTextModelRejectsReference() {
    var spec = ComfyImageClient.resolve("qwenImage21Edit", "1K", "1:1");
    assertThatThrownBy(() -> client.submit("p", run, "qwenImage21Edit", spec, "prompt", null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> client.submit("p", run, "qwenImage21Edit", spec, "prompt", new byte[] {1, 2, 3}))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                client.submit(
                    "p",
                    run,
                    "qwenImage21Text",
                    ComfyImageClient.resolve("qwenImage21Text", "1K", "1:1"),
                    "prompt",
                    png(64)))
        .isInstanceOf(ApiException.class);
    assertThat(path.get()).isNull();
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
    assertThatThrownBy(
            () ->
                disabled.submit(
                    "synthetic-project",
                    run,
                    "qwenImage21Text",
                    ComfyImageClient.resolve("qwenImage21Text", "1K", "1:1"),
                    "synthetic",
                    null))
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
