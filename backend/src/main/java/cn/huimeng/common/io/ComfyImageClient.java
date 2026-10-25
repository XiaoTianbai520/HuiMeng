package cn.huimeng.common.io;

import cn.huimeng.common.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 固定 Qwen 图及管理员配置端点；不接受浏览器工作流/模型文件/URL，不调用全局中断。 */
@Component
public class ComfyImageClient {
  private final boolean enabled;
  private final URI endpoint;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public ComfyImageClient(
      ObjectMapper json,
      @Value("${huimeng.comfy.enabled:false}") boolean enabled,
      @Value("${huimeng.comfy.endpoint:http://127.0.0.1:8188}") String endpoint) {
    this.json = json;
    this.enabled = enabled;
    this.endpoint = URI.create(endpoint);
    if (!Set.of("http", "https").contains(this.endpoint.getScheme())
        || this.endpoint.getHost() == null
        || this.endpoint.getUserInfo() != null
        || this.endpoint.getQuery() != null
        || this.endpoint.getFragment() != null
        || !Set.of("", "/").contains(this.endpoint.getPath()))
      throw new IllegalArgumentException("模型执行端点配置无效");
  }

  public void requireAvailable() {
    if (!enabled) throw unavailable();
  }

  /** 入场前检查服务可达，避免未启动执行器消耗待核查名额。 */
  public void checkReady() {
    requireAvailable();
    if (!request("GET", "/system_stats", null).path("system").isObject()) throw unavailable();
  }

  public void submit(String project, String run, String prompt) {
    requireAvailable();
    try (var resource = getClass().getResourceAsStream("/comfy/qwen-text.json")) {
      if (resource == null) throw unavailable();
      var graph = json.readTree(resource);
      ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("4").path("inputs"))
          .put("prompt", prompt);
      ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("7").path("inputs"))
          .put("seed", new java.security.SecureRandom().nextLong(1L << 48));
      ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("9").path("inputs"))
          .put("filename_prefix", "HuiMeng/" + project + "/" + run);
      var body = json.createObjectNode();
      body.set("prompt", graph);
      body.put("prompt_id", run);
      body.put("client_id", "huimeng");
      var result = request("POST", "/prompt", body);
      if (!result.path("prompt_id").asText().equals(run)) throw unavailable();
    } catch (IOException ex) {
      throw unavailable();
    }
  }

  public JsonNode history(String run) {
    requireAvailable();
    return request("GET", "/history/" + uuid(run), null).get(run);
  }

  /** 新版 ComfyUI 以队列自身的原子检查只取消指定 ID；不使用有竞态的全局 interrupt。 */
  public boolean cancel(String run) {
    return request("POST", "/api/jobs/" + uuid(run) + "/cancel", json.createObjectNode())
        .path("cancelled")
        .asBoolean();
  }

  public boolean active(String run) {
    var result = request("GET", "/api/jobs/" + uuid(run), null);
    if (result.isEmpty()) return false;
    return !Set.of("completed", "failed", "cancelled", "error", "success")
        .contains(result.path("status").asText());
  }

  public byte[] output(String project, String run, JsonNode record) {
    var image = record.path("outputs").path("9").path("images").path(0);
    String folder = image.path("subfolder").asText().replace('\\', '/'),
        filename = image.path("filename").asText();
    if (!folder.equals("HuiMeng/" + project)
        || !filename.matches(java.util.regex.Pattern.quote(run) + "_[0-9]+_?\\.png")
        || !image.path("type").asText().equals("output")) throw unavailable();
    String path =
        "/view?filename="
            + URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
            + "&subfolder="
            + URLEncoder.encode(folder, java.nio.charset.StandardCharsets.UTF_8)
            + "&type=output";
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(endpoint.resolve(path))
                  .timeout(Duration.ofSeconds(60))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() != 200) throw unavailable();
        byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
        if (bytes.length > 16 * 1024 * 1024
            || bytes.length < 8
            || !Arrays.equals(
                Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
          throw unavailable();
        return bytes;
      }
    } catch (IOException ex) {
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  private String uuid(String value) {
    try {
      return UUID.fromString(value).toString();
    } catch (IllegalArgumentException ex) {
      throw unavailable();
    }
  }

  private JsonNode request(String method, String path, JsonNode body) {
    try {
      var builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(20));
      if (method.equals("POST"))
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      else builder.GET();
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() == 404 && path.startsWith("/api/jobs/"))
          return json.createObjectNode();
        if (response.statusCode() != 200) throw unavailable();
        byte[] bytes = input.readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw unavailable();
        return json.readTree(bytes);
      }
    } catch (IOException ex) {
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  public static ApiException unavailable() {
    return new ApiException(503, "EXECUTOR_UNAVAILABLE", "本地模型执行服务暂不可用");
  }
}
