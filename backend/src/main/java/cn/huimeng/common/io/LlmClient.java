package cn.huimeng.common.io;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.mapper.ConfigurationMapper;
import cn.huimeng.security.SecretBox;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容文本供应商（deepseek、qwen）的受控客户端。 只接受数据库批准的供应商/模型，端点来自服务端配置；密钥仅在内存使用，不进入日志。 不跟随重定向，不接受调用方提供的
 * URL；通过关闭上游流支持取消。
 */
@Component
public class LlmClient {
  private static final int MAX_ERROR_BYTES = 16 * 1024;
  private final boolean enabled;
  private final Map<String, URI> endpoints;
  private final ObjectMapper json;
  private final ConfigurationMapper configuration;
  private final SecretBox box;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public LlmClient(
      ObjectMapper json,
      ConfigurationMapper configuration,
      SecretBox box,
      @Value("${huimeng.llm.enabled:false}") boolean enabled,
      @Value("${huimeng.llm.deepseek-endpoint:https://api.deepseek.com}") String deepseekEndpoint,
      @Value("${huimeng.llm.qwen-endpoint:https://dashscope.aliyuncs.com/compatible-mode}")
          String qwenEndpoint) {
    this.json = json;
    this.configuration = configuration;
    this.box = box;
    this.enabled = enabled;
    this.endpoints = Map.of("deepseek", endpoint(deepseekEndpoint), "qwen", endpoint(qwenEndpoint));
  }

  private static URI endpoint(String value) {
    URI uri = URI.create(value);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) throw new IllegalArgumentException("文本供应商端点配置无效");
    return uri;
  }

  /** 发起流式对话；调用方负责关闭返回流以取消。 */
  public LlmStream chat(String providerId, String modelId, List<Map<String, String>> messages) {
    var plain = new ArrayList<Map<String, Object>>();
    for (var message : messages) plain.add(new LinkedHashMap<>(message));
    return new LlmStream(open(providerId, modelId, plain, List.of()), json);
  }

  /** 一次工具调用；arguments 为模型给出的 JSON 文本，由调用方解析。 */
  public record ToolCall(String id, String name, String arguments) {}

  /** 一轮 assistant 输出：文本增量已通过回调送出，这里返回完整文本与工具调用。 */
  public record Turn(String text, List<ToolCall> calls) {}

  /** 发起一轮带工具的流式对话。文本增量实时交给 onText（可为 null），工具调用在流结束后一次性返回； 关闭上游流即可取消，调用方负责在回调中抛出以提前终止。 */
  public Turn converse(
      String providerId,
      String modelId,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      java.util.function.Consumer<String> onText) {
    try (InputStream input = open(providerId, modelId, messages, tools)) {
      return readTurn(input, onText);
    } catch (ApiException ex) {
      throw ex;
    } catch (IOException ex) {
      throw unavailable();
    }
  }

  /** 打开 OpenAI 兼容流式响应；端点、密钥与消息结构都在服务端固定。 */
  private InputStream open(
      String providerId,
      String modelId,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools) {
    requireEnabled();
    URI base = endpoints.get(providerId);
    if (base == null) throw new ApiException(501, "PROVIDER_NOT_IMPLEMENTED", "该文本供应商尚未接入");
    String encrypted = configuration.encryptedSecret(providerId);
    if (encrypted == null) throw new ApiException(503, "PROVIDER_NOT_CONFIGURED", "团队尚未配置该供应商密钥");
    String key = box.decrypt(providerId, encrypted);
    var body = json.createObjectNode();
    body.put("model", modelId);
    body.put("stream", true);
    var messageArray = body.putArray("messages");
    for (var message : messages) {
      String role = String.valueOf(message.get("role"));
      if (!Set.of("system", "user", "assistant", "tool").contains(role))
        throw ApiException.invalid();
      var item = messageArray.addObject();
      item.put("role", role);
      Object content = message.get("content");
      if (content == null) item.putNull("content");
      else item.set("content", json.valueToTree(content));
      for (String field : List.of("tool_calls", "tool_call_id")) {
        Object value = message.get(field);
        if (value != null) item.set(field, json.valueToTree(value));
      }
    }
    if (tools != null && !tools.isEmpty()) body.set("tools", json.valueToTree(tools));
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve("/chat/completions"))
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
    return send(request);
  }

  private InputStream send(HttpRequest request) {
    try {
      HttpResponse<InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        readError(response.body());
        throw new ApiException(503, "PROVIDER_REQUEST_FAILED", "模型服务请求失败，请稍后重试或联系管理员");
      }
      return response.body();
    } catch (ApiException ex) {
      throw ex;
    } catch (IOException ex) {
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  /** 逐条读取 SSE：content 交给回调，tool_calls 按 index 合并片段。 */
  private Turn readTurn(InputStream input, java.util.function.Consumer<String> onText)
      throws IOException {
    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    var text = new StringBuilder();
    var calls = new TreeMap<Integer, String[]>();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank() || line.startsWith(":")) continue;
      if (!line.startsWith("data:")) continue;
      String data = line.substring(5).trim();
      if (data.equals("[DONE]")) break;
      JsonNode event = json.readTree(data);
      for (var choice : event.path("choices")) {
        JsonNode delta = choice.path("delta");
        String content = delta.path("content").asText(null);
        if (content != null && !content.isEmpty()) {
          text.append(content);
          if (onText != null) onText.accept(content);
        }
        for (var call : delta.path("tool_calls")) {
          int index = call.path("index").asInt(calls.size());
          String[] slot = calls.computeIfAbsent(index, ignored -> new String[] {null, "", ""});
          if (call.hasNonNull("id")) slot[0] = call.path("id").asText();
          var function = call.path("function");
          if (function.hasNonNull("name")) slot[1] = function.path("name").asText();
          if (function.hasNonNull("arguments"))
            slot[2] = slot[2] + function.path("arguments").asText();
        }
        if (choice.path("finish_reason").asText(null) != null) break;
      }
    }
    var result = new ArrayList<ToolCall>();
    for (var slot : calls.values()) {
      if (slot[1] == null || slot[1].isBlank()) continue;
      result.add(
          new ToolCall(slot[0] == null ? UUID.randomUUID().toString() : slot[0], slot[1], slot[2]));
    }
    return new Turn(text.toString(), List.copyOf(result));
  }

  public void requireEnabled() {
    if (!enabled) throw unavailable();
  }

  private ApiException unavailable() {
    return new ApiException(503, "PROVIDER_UNAVAILABLE", "文本生成服务未启用或暂不可用");
  }

  private static String readError(InputStream input) {
    try (input) {
      return new String(input.readNBytes(MAX_ERROR_BYTES), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      return "";
    }
  }

  /** 解析 OpenAI 兼容 SSE 的文本增量。 */
  public static final class LlmStream implements AutoCloseable {
    private final InputStream input;
    private final BufferedReader reader;
    private final ObjectMapper json;
    private boolean finished;

    private LlmStream(InputStream input, ObjectMapper json) {
      this.input = input;
      this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      this.json = json;
    }

    /** 返回下一段文本增量；流结束返回 null。 */
    public String nextDelta() throws IOException {
      if (finished) return null;
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith(":")) continue;
        if (!line.startsWith("data:")) continue;
        String data = line.substring(5).trim();
        if (data.equals("[DONE]")) {
          finished = true;
          return null;
        }
        JsonNode event = json.readTree(data);
        for (var choice : event.path("choices")) {
          JsonNode delta = choice.path("delta");
          String content = delta.path("content").asText(null);
          if (content != null && !content.isEmpty()) return content;
          if (choice.path("finish_reason").asText(null) != null) {
            finished = true;
            return null;
          }
        }
      }
      finished = true;
      return null;
    }

    @Override
    public void close() {
      try {
        input.close();
      } catch (IOException ignored) {
      }
    }
  }
}
