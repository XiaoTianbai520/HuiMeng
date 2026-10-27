package cn.huimeng.common.io;

import cn.huimeng.common.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地 MiniMax H3（五模式）执行器客户端：模板固定在 classpath，只替换提示词、像素、帧数与参考图。
 *
 * <p>与图片客户端一致：不跟随重定向、不向页面暴露执行器地址、关闭流即取消。
 */
@Component
public class ComfyVideoClient {
  public static final String PROVIDER = "h3Local3060";
  private static final Set<String> MODELS = Set.of("h3T2v", "h3I2v", "h3Fl2v", "h3L2v", "h3R2v");
  private static final Map<String, String> MODE_BY_MODEL =
      Map.of("h3T2v", "t2v", "h3I2v", "i2v", "h3Fl2v", "fl2v", "h3L2v", "l2v", "h3R2v", "r2v");
  private static final Map<String, int[]> SIZES =
      Map.of(
          "352p", new int[] {608, 352}, "480p", new int[] {864, 480}, "544p", new int[] {960, 544});
  private static final Set<Integer> DURATIONS = Set.of(5, 8, 10, 15);
  private static final Set<String> RATIOS = Set.of("16:9", "9:16", "1:1", "4:3", "3:4");
  private static final int FPS = 24;
  private static final String TEMPLATE = "/comfy/h3-";

  private final ObjectMapper json;
  private final boolean enabled;
  private final URI endpoint;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public ComfyVideoClient(
      ObjectMapper json,
      @Value("${huimeng.comfy.enabled:false}") boolean enabled,
      @Value("${huimeng.comfy.endpoint:http://127.0.0.1:8188}") String endpoint) {
    this.json = json;
    this.enabled = enabled;
    this.endpoint = endpoint(endpoint);
  }

  private static URI endpoint(String value) {
    URI uri = URI.create(value);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) throw new IllegalArgumentException("执行器端点配置无效");
    return uri;
  }

  public boolean supports(String modelId) {
    return MODELS.contains(modelId);
  }

  /** 一次视频请求的最终规格；display 与响应 data[].spec 一致。 */
  public record Spec(
      String model,
      String mode,
      int duration,
      String resolution,
      String ratio,
      int width,
      int height,
      int frames,
      String display) {}

  /** 缺省为 5 秒、352p、16:9；未知时长/分辨率/比例直接拒绝，不做静默降级。 */
  public static Spec resolve(String model, Integer duration, String resolution, String ratio) {
    String mode = MODE_BY_MODEL.get(model);
    if (mode == null) throw unfamiliarModel();
    int seconds = duration == null ? 5 : duration;
    if (!DURATIONS.contains(seconds))
      throw new ApiException(400, "INVALID_ARGUMENT", "时长支持 5、8、10、15 秒");
    String size = resolution == null || resolution.isBlank() ? "352p" : resolution;
    int[] base = SIZES.get(size);
    if (base == null) throw new ApiException(400, "INVALID_ARGUMENT", "分辨率支持 352p、480p、544p");
    String aspect = ratio == null || ratio.isBlank() ? "16:9" : ratio;
    if (!RATIOS.contains(aspect))
      throw new ApiException(400, "INVALID_ARGUMENT", "比例支持 16:9、9:16、1:1、4:3、3:4");
    int[] ratioPair = Arrays.stream(aspect.split(":")).mapToInt(Integer::parseInt).toArray();
    int rw = ratioPair[0], rh = ratioPair[1], shortSide = base[1];
    int width =
        aspect.equals("16:9")
            ? base[0]
            : aspect.equals("9:16")
                ? base[1]
                : Math.round((rw >= rh ? shortSide * rw / (float) rh : shortSide) / 32) * 32;
    int height =
        aspect.equals("16:9")
            ? base[1]
            : aspect.equals("9:16")
                ? base[0]
                : Math.round((rh >= rw ? shortSide * rh / (float) rw : shortSide) / 32) * 32;
    int count = Math.round(seconds * (float) FPS);
    int frames = count + (5 - count % 17 + 17) % 17;
    String display = seconds + "s " + size + " " + aspect + " " + width + "×" + height;
    return new Spec(model, mode, seconds, size, aspect, width, height, frames, display);
  }

  public static ApiException unfamiliarModel() {
    return new ApiException(400, "UNSUPPORTED_MODEL", "该模型尚未接入");
  }

  public static ApiException unavailable() {
    return new ApiException(503, "EXECUTOR_UNAVAILABLE", "视频执行器未启用或暂不可用");
  }

  public void requireAvailable() {
    if (!enabled) throw unavailable();
  }

  public void checkReady() {
    requireAvailable();
    var root = request("GET", "/system_stats");
    if (!root.has("system")) throw unavailable();
  }

  /** 提交一次生成。参考图按模式顺序给出：i2v/l2v 一张；fl2v 首帧、尾帧；r2v 为参考图列表。 */
  public String submit(
      String project, String run, Spec spec, String prompt, List<byte[]> references) {
    requireAvailable();
    ObjectNode graph = template(spec);
    ObjectNode condition = (ObjectNode) graph.path("5").path("inputs");
    condition.put("width", spec.width());
    condition.put("height", spec.height());
    condition.put("length", spec.frames());
    condition.put("prompt", formatPrompt(spec.mode(), prompt, spec.frames(), references.size()));
    ((ObjectNode) graph.path("8").path("inputs"))
        .put("noise_seed", new SplittableRandom().nextInt(1, Integer.MAX_VALUE));
    ((ObjectNode) graph.path("14").path("inputs"))
        .put("filename_prefix", "HuiMeng/" + project + "/" + run);
    if (spec.mode().equals("r2v")) {
      graph.remove("15");
      condition.remove("ref_images.ref_image_0");
      for (int index = 0; index < references.size(); index++) {
        String id = String.valueOf(100 + index);
        var loader = graph.putObject(id);
        loader.put("class_type", "LoadImage");
        loader.putObject("inputs").put("image", upload(project, run, index, references.get(index)));
        condition.putArray("ref_images.ref_image_" + index).add(id).add(0);
      }
    } else {
      if (!references.isEmpty() && graph.has("15"))
        ((ObjectNode) graph.path("15").path("inputs"))
            .put("image", upload(project, run, 0, references.get(0)));
      if (references.size() > 1 && graph.has("16"))
        ((ObjectNode) graph.path("16").path("inputs"))
            .put("image", upload(project, run, 1, references.get(1)));
    }
    var body = json.createObjectNode();
    body.set("prompt", graph);
    // 让上游任务 ID 与本地运行 ID 一致：POST 已被接受但响应丢失时，核对任务仍能按运行 ID 查到。
    body.put("prompt_id", run);
    body.put("client_id", "huimeng-" + run);
    var root = request("POST", "/prompt", body.toString());
    String promptId = root.path("prompt_id").asText("");
    if (!promptId.matches("^[a-f0-9-]{36}$")) throw unavailable();
    return promptId;
  }

  private ObjectNode template(Spec spec) {
    String name = TEMPLATE + spec.mode() + ".json";
    try (var input = getClass().getResourceAsStream(name)) {
      if (input == null) throw new IllegalStateException("视频模板缺失：" + name);
      return (ObjectNode) json.readTree(input);
    } catch (IOException ex) {
      throw new IllegalStateException("视频模板不可读：" + name, ex);
    }
  }

  /** 结构化提示词按模式补齐字段；R2V 需要调用方自行给出六段完整描述。 */
  static String formatPrompt(String mode, String text, int frames, int referenceCount) {
    String duration = String.format(Locale.ROOT, "%.2f", frames / (float) FPS);
    StringBuilder body = new StringBuilder(text);
    if (mode.equals("r2v")) {
      if (!text.contains("subject_definitions:")) {
        var sources = new ArrayList<String>();
        for (int index = 0; index < referenceCount; index++)
          sources.add("<Picture " + (index + 1) + "> is reference image " + (index + 1));
        return "subject_definitions: "
            + String.join("; ", sources)
            + ".\n\n"
            + "summary: Generate a "
            + duration
            + "-second video following the description and references.\n\n"
            + "retention_analysis: Preserve the appearance of reference subjects.\n\n"
            + "detailed_description: [Shot 1] "
            + text
            + "\n\n"
            + "overall_soundscape: Natural synchronized sounds appropriate to the described scene.\n\n"
            + "non_diegetic_music: N/A";
      }
      return text;
    }
    if (!body.toString().contains("integrated_multimodal_description:"))
      return "integrated_multimodal_description: [Shot 1] "
          + text
          + "\n\n"
          + "overall_soundscape: Natural synchronized sounds appropriate to the described scene.\n\n"
          + "non_diegetic_music: N/A";
    return text;
  }

  private String upload(String project, String run, int index, byte[] bytes) {
    if (bytes == null || bytes.length == 0)
      throw new ApiException(400, "INVALID_ARGUMENT", "参考图为空");
    String boundary = "huimeng" + UUID.randomUUID().toString().replace("-", "");
    String filename = run + "-" + index + ".png";
    var head =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
            + filename
            + "\"\r\nContent-Type: image/png\r\n\r\n";
    var tail = "\r\n--" + boundary + "--\r\n";
    var out = new ByteArrayOutputStream();
    try {
      out.write(head.getBytes(StandardCharsets.UTF_8));
      out.write(bytes);
      out.write(tail.getBytes(StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
    var response =
        send("/upload/image", out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    String name = response.path("name").asText("");
    if (name.isBlank()) throw unavailable();
    return name;
  }

  /** 读取历史记录；未完成返回 null。 */
  public JsonNode history(String promptId) {
    var root = request("GET", "/history/" + promptId);
    return root.path(promptId).isMissingNode() ? null : root.path(promptId);
  }

  /** 下载产物 MP4，并校验 ftyp 头。 */
  public byte[] output(String promptId, JsonNode record) {
    var files = record.path("outputs").path("14");
    String name = null, folder = "";
    for (String key : List.of("images", "videos", "gifs")) {
      for (var item : files.path(key)) {
        if (item.path("filename").asText("").endsWith(".mp4")) {
          name = item.path("filename").asText();
          folder = item.path("subfolder").asText("");
          break;
        }
      }
      if (name != null) break;
    }
    if (name == null) throw new ApiException(503, "EXECUTOR_FAILED", "执行器未返回视频文件");
    String query = "/view?filename=" + url(name) + "&subfolder=" + url(folder) + "&type=output";
    byte[] bytes = download(query);
    if (bytes.length < 12 || !new String(bytes, 4, 4, StandardCharsets.US_ASCII).equals("ftyp"))
      throw new ApiException(503, "EXECUTOR_FAILED", "执行器返回的视频不可用");
    return bytes;
  }

  public void cancel(String promptId) {
    try {
      request("POST", "/api/jobs/" + promptId + "/cancel", "{}");
    } catch (RuntimeException ignored) {
      // 取消是尽力而为：任务可能已经结束。
    }
  }

  private static String url(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private JsonNode request(String method, String path) {
    return request(method, path, null);
  }

  private JsonNode request(String method, String path, String body) {
    byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    return send(path, payload, body == null ? null : "application/json");
  }

  private byte[] download(String path) {
    var request =
        HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(Duration.ofSeconds(180))
            .GET()
            .build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() / 100 != 2) throw unavailable();
      return response.body();
    } catch (IOException ex) {
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  private JsonNode send(String path, byte[] payload, String contentType) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(Duration.ofSeconds(120))
            .header("Accept", "application/json");
    if (contentType != null) builder.header("Content-Type", contentType);
    builder.method(
        payload == null || payload.length == 0 ? "GET" : "POST",
        payload == null || payload.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(payload));
    try {
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 404 && path.startsWith("/history/"))
        return json.createObjectNode();
      if (response.statusCode() / 100 != 2) throw unavailable();
      return json.readTree(response.body());
    } catch (IOException ex) {
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }
}
