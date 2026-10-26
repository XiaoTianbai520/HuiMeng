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
      if (!Set.of("system", "user", "assistant").contains(message.get("role")))
        throw ApiException.invalid();
      var item = messageArray.addObject();
      item.put("role", message.get("role"));
      item.put("content", message.getOrDefault("content", ""));
    }
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve("/chat/completions"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        readError(response.body());
        throw new ApiException(503, "PROVIDER_REQUEST_FAILED", "模型服务请求失败，请稍后重试或联系管理员");
      }
      return new LlmStream(response.body(), json);
    } catch (ApiException ex) {
      throw ex;
    } catch (IOException ex) {
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
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
