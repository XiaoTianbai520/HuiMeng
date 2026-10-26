package cn.huimeng.common.io;

import cn.huimeng.common.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 固定 Qwen 图两模型及管理员配置端点；不接受浏览器工作流/模型文件/URL，不调用全局中断。
 *
 * <p>规格按现用供应商 1.0.0 的像素预算与 32 对齐规则计算，默认值固定在服务端：省略 size/ratio 等价于 1K、1:1。
 */
@Component
public class ComfyImageClient {
  /** 现用供应商声明的分辨率预算，键为对外规格名。 */
  public static final Map<String, Integer> SIZES = Map.of("0.5MP", 736, "1K", 1024, "2K", 2048);

  public static final List<String> RATIOS =
      List.of("1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9");
  public static final String TEXT_MODEL = "qwenImage21Text";
  public static final String EDIT_MODEL = "qwenImage21Edit";
  private static final Pattern IMAGE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,180}\\.png");
  private static final int MAX_REFERENCE = 16 * 1024 * 1024;

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

  /** 已解析的实际提交规格；宽度/高度为 32 对齐后的输出画布。 */
  public record Spec(String size, String ratio, int resolution, int width, int height) {
    public String display() {
      return size + " " + ratio + " " + width + "×" + height;
    }
  }

  public static boolean knownModel(String model) {
    return TEXT_MODEL.equals(model) || EDIT_MODEL.equals(model);
  }

  /** 服务端固定的默认值与校验；未知规格直接拒绝，不做静默降级。 */
  public static Spec resolve(String model, String size, String ratio) {
    if (!knownModel(model)) throw unfamiliarModel();
    String resolvedSize = size == null || size.isBlank() ? "1K" : size;
    String resolvedRatio = ratio == null || ratio.isBlank() ? "1:1" : ratio;
    Integer resolution = SIZES.get(resolvedSize);
    if (resolution == null || !RATIOS.contains(resolvedRatio))
      throw new ApiException(400, "INVALID_ARGUMENT", "该模型不支持所选尺寸或比例");
    String[] parts = resolvedRatio.split(":");
    double rw = Double.parseDouble(parts[0]), rh = Double.parseDouble(parts[1]);
    int width = Math.max(32, (int) (Math.round(resolution * Math.sqrt(rw / rh) / 32) * 32));
    int height = Math.max(32, (int) (Math.round(resolution / Math.sqrt(rw / rh) / 32) * 32));
    return new Spec(resolvedSize, resolvedRatio, resolution, width, height);
  }

  public void requireAvailable() {
    if (!enabled) throw unavailable();
  }

  /** 入场前检查服务可达，避免未启动执行器消耗待核查名额。 */
  public void checkReady() {
    requireAvailable();
    if (!request("GET", "/system_stats", null).path("system").isObject()) throw unavailable();
  }

  /**
   * 提交固定图；返回 ComfyUI 实际任务 ID，调用方须以该 ID 查询/取消，不能假定等于服务端运行 ID。
   *
   * @param reference 图生图参考图 PNG 字节；文生图必须为 null
   */
  public String submit(
      String project, String run, String model, Spec spec, String prompt, byte[] reference) {
    requireAvailable();
    if (!knownModel(model)) throw unfamiliarModel();
    if (EDIT_MODEL.equals(model) == (reference == null)) throw unfamiliarModel();
    String template = EDIT_MODEL.equals(model) ? "/comfy/qwen-edit.json" : "/comfy/qwen-text.json";
    try (var resource = getClass().getResourceAsStream(template)) {
      if (resource == null) throw unavailable();
      var graph = json.readTree(resource);
      var encoder = (com.fasterxml.jackson.databind.node.ObjectNode) graph.path("4").path("inputs");
      encoder.put("prompt", prompt);
      encoder.put("resolution", spec.resolution());
      ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("5").path("inputs"))
          .put("width", spec.width())
          .put("height", spec.height())
          .put("batch_size", 1);
      ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("7").path("inputs"))
          .put("seed", new java.security.SecureRandom().nextLong(1L << 32));
      ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("9").path("inputs"))
          .put("filename_prefix", "HuiMeng/" + project + "/" + run);
      if (reference != null)
        ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("10").path("inputs"))
            .put("image", uploadImage(run, reference));
      var body = json.createObjectNode();
      body.set("prompt", graph);
      body.put("prompt_id", run);
      body.put("client_id", "huimeng");
      var result = request("POST", "/prompt", body);
      String promptId = result.path("prompt_id").asText();
      try {
        return UUID.fromString(promptId).toString();
      } catch (IllegalArgumentException ex) {
        throw unavailable();
      }
    } catch (IOException ex) {
      throw unavailable();
    }
  }

  /** 参考图先进入 ComfyUI 输入目录；文件名由服务端运行 ID 生成，调用方不能指定路径。 */
  private String uploadImage(String run, byte[] bytes) {
    if (bytes.length < 8
        || bytes.length > MAX_REFERENCE
        || !Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      throw new ApiException(400, "INVALID_ARGUMENT", "参考图必须是 16 MiB 以内的 PNG");
    String filename = "huimeng-" + run + ".png";
    String boundary = "----huimeng" + UUID.randomUUID().toString().replace("-", "");
    var head = new ByteArrayOutputStream();
    try {
      head.write(
          ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"")
              .getBytes(StandardCharsets.UTF_8));
      head.write(filename.getBytes(StandardCharsets.UTF_8));
      head.write("\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      head.write(bytes);
      for (String field : List.of("overwrite")) {
        head.write(
            ("\r\n--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\""
                    + field
                    + "\"\r\n\r\ntrue")
                .getBytes(StandardCharsets.UTF_8));
      }
      head.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw unavailable();
    }
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(endpoint.resolve("/upload/image"))
                  .timeout(Duration.ofSeconds(60))
                  .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(head.toByteArray()))
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() != 200) throw unavailable();
        var result = json.readTree(input.readNBytes(64 * 1024));
        String name = result.path("name").asText();
        String subfolder = result.path("subfolder").asText("");
        if (!IMAGE_NAME.matcher(name).matches()) throw unavailable();
        return subfolder.isBlank() ? name : subfolder + "/" + name;
      }
    } catch (IOException ex) {
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  public JsonNode history(String promptId) {
    requireAvailable();
    return request("GET", "/history/" + uuid(promptId), null).get(promptId);
  }

  /** 新版 ComfyUI 以队列自身的原子检查只取消指定 ID；不使用有竞态的全局 interrupt。 */
  public boolean cancel(String promptId) {
    return request("POST", "/api/jobs/" + uuid(promptId) + "/cancel", json.createObjectNode())
        .path("cancelled")
        .asBoolean();
  }

  public boolean active(String promptId) {
    var result = request("GET", "/api/jobs/" + uuid(promptId), null);
    if (result.isEmpty()) return false;
    return !Set.of("completed", "failed", "cancelled", "error", "success")
        .contains(result.path("status").asText());
  }

  public byte[] output(String project, String run, JsonNode record) {
    var image = record.path("outputs").path("9").path("images").path(0);
    String folder = image.path("subfolder").asText().replace('\\', '/'),
        filename = image.path("filename").asText();
    if (!folder.equals("HuiMeng/" + project)
        || !filename.matches(Pattern.quote(run) + "_[0-9]+_?\\.png")
        || !image.path("type").asText().equals("output")) throw unavailable();
    String path =
        "/view?filename="
            + URLEncoder.encode(filename, StandardCharsets.UTF_8)
            + "&subfolder="
            + URLEncoder.encode(folder, StandardCharsets.UTF_8)
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

  public static ApiException unfamiliarModel() {
    return new ApiException(400, "INVALID_ARGUMENT", "未接通该模型或参考图用法不正确");
  }
}
