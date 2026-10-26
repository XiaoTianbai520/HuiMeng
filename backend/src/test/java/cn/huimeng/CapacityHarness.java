package cn.huimeng;

import cn.huimeng.common.storage.ObjectStorage;
import cn.huimeng.dto.req.*;
import cn.huimeng.security.Actor;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A16 跨入口名额夹具：Web 会话、MCP 外部客户端、A2A 团队任务共用同一项目的五个名额。
 *
 * <p>合成 ComfyUI 按固定延迟出图、合成文本供应商先给工具调用再给文本，全部在本进程内，不连接真实模型或对象存储。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=${hm.capacity.port:18095}",
      "server.address=127.0.0.1",
      "huimeng.public-origin=http://localhost:5187",
      "huimeng.storage.enabled=false",
      "huimeng.comfy.enabled=true",
      "huimeng.llm.enabled=true"
    })
@ActiveProfiles("test")
public class CapacityHarness {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ComfyMock COMFY = ComfyMock.start();
  private static final LlmMock LLM = LlmMock.start();
  static final Path ROOT =
      Path.of("target/validation/capacity-" + UUID.randomUUID()).toAbsolutePath();
  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired ConfigurationService configuration;
  @Autowired ExternalClientService externalClients;

  /** 只替换对象存储，避免夹具依赖真实 MinIO。 */
  @TestConfiguration
  static class MemoryStorage {
    @Bean
    @Primary
    ObjectStorage memoryObjectStorage() {
      return new ObjectStorage() {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        public void put(String key, java.io.InputStream content, long length) {
          try {
            byte[] bytes = content.readAllBytes();
            if (bytes.length != length) throw unavailable();
            objects.put(key, bytes);
          } catch (IOException ex) {
            throw unavailable();
          }
        }

        public StoredObject head(String key) {
          byte[] bytes = objects.get(key);
          if (bytes == null) throw unavailable();
          return new StoredObject(bytes.length, etag(bytes));
        }

        public java.io.InputStream get(String key, String etag, String range) {
          byte[] bytes = objects.get(key);
          if (bytes == null || (etag != null && !etag.equals(etag(bytes)))) throw unavailable();
          return new ByteArrayInputStream(bytes);
        }

        public void delete(String key) {
          objects.remove(key);
        }

        private String etag(byte[] bytes) {
          try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .substring(0, 32);
          } catch (Exception ex) {
            throw unavailable();
          }
        }

        private RuntimeException unavailable() {
          return new cn.huimeng.common.exception.ApiException(
              503, "STORAGE_UNAVAILABLE", "对象存储不可用");
        }
      };
    }
  }

  @DynamicPropertySource
  static void fixture(DynamicPropertyRegistry properties) throws Exception {
    if (!"true".equals(System.getenv("HM_CAPACITY")))
      throw new IllegalStateException("Explicit capacity opt-in required");
    Path config = Files.createDirectories(ROOT.resolve("config"));
    for (String name : List.of("master.key", "internal.key")) {
      byte[] key = new byte[32];
      new java.security.SecureRandom().nextBytes(key);
      Files.writeString(config.resolve(name), Base64.getEncoder().encodeToString(key));
    }
    properties.add("huimeng.workspace-root", () -> ROOT.resolve("workspaces").toString());
    properties.add("huimeng.master-key-file", () -> config.resolve("master.key").toString());
    properties.add("huimeng.internal-key-file", () -> config.resolve("internal.key").toString());
    properties.add("huimeng.comfy.endpoint", COMFY::url);
    properties.add("huimeng.llm.deepseek-endpoint", LLM::url);
    properties.add("huimeng.llm.qwen-endpoint", LLM::url);
  }

  @Test
  void capacityFixtureUntilStopped() throws Exception {
    String password = "M3-capacity-fixture-only";
    users.bootstrap("cap-admin", password);
    Actor admin = users.authenticate("cap-admin", password);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    var member = users.create(new CreateUserReqDTO("cap-member", password, "USER"), "capacity");
    var alpha = projects.create(new CreateProjectReqDTO("跨入口名额验收"), "capacity");
    var beta = projects.create(new CreateProjectReqDTO("独立计数验收"), "capacity");
    for (var project : List.of(alpha, beta)) {
      projects.addMember(project.id(), admin.id(), "capacity");
      projects.addMember(project.id(), member.id(), "capacity");
      projects.workspaceRoot(project.id(), true);
    }
    configuration.setSecret("deepseek", "fixture-llm-key", "capacity");
    configuration.approve(
        new ModelApprovalReqDTO("qwenImage21Local", "qwenImage21Text", true), "capacity");
    configuration.approve(new ModelApprovalReqDTO("deepseek", "deepseek-chat", true), "capacity");
    var alphaClient =
        externalClients.issue(
            new IssueExternalClientReqDTO(
                "capacity-alpha",
                alpha.id(),
                List.of("generateMedia", "getRun", "cancelRun", "listMedia", "listTeams"),
                List.of("capacityTeam")),
            "capacity");
    var betaClient =
        externalClients.issue(
            new IssueExternalClientReqDTO(
                "capacity-beta",
                beta.id(),
                List.of("generateMedia", "getRun", "cancelRun"),
                List.of()),
            "capacity");
    SecurityContextHolder.clearContext();
    Path stop = Path.of("target/capacity.stop");
    Files.deleteIfExists(stop);
    var ready = new LinkedHashMap<String, Object>();
    ready.put("port", System.getProperty("hm.capacity.port", "18095"));
    ready.put("alphaProject", alpha.id());
    ready.put("betaProject", beta.id());
    ready.put("alphaToken", alphaClient.token());
    ready.put("betaToken", betaClient.token());
    ready.put("alphaClientId", alphaClient.id());
    ready.put("adminUser", "cap-admin");
    ready.put("adminPassword", password);
    ready.put("memberUser", "cap-member");
    ready.put("memberPassword", password);
    ready.put("comfyPort", COMFY.port());
    ready.put("llmPort", LLM.port());
    ready.put("fixtureRoot", ROOT.toString());
    Files.writeString(Path.of("target/capacity.ready"), JSON.writeValueAsString(ready));
    long deadline = Instant.now().getEpochSecond() + 7200;
    try {
      while (!Files.exists(stop) && Instant.now().getEpochSecond() < deadline) Thread.sleep(500);
    } finally {
      try (var paths = Files.walk(ROOT)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  /** 合成 ComfyUI：按提交顺序延迟出图，支持取消与队列状态查询。 */
  static final class ComfyMock {
    private static final byte[] PNG =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private final HttpServer server;
    private final Map<String, Instant> due = new ConcurrentHashMap<>();
    private final Map<String, String> project = new ConcurrentHashMap<>();
    private final Map<String, String> run = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<String> completed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger submits = new AtomicInteger();
    private final long delayMillis =
        Long.parseLong(System.getenv().getOrDefault("HM_CAPACITY_DELAY_MS", "9000"));

    private ComfyMock(HttpServer server) {
      this.server = server;
    }

    static ComfyMock start() {
      try {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ComfyMock mock = new ComfyMock(server);
        server.createContext("/system_stats", mock::system);
        server.createContext("/prompt", mock::prompt);
        server.createContext("/history", mock::history);
        server.createContext("/view", mock::view);
        server.createContext("/api/jobs", mock::job);
        server.createContext("/stats", mock::stats);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return mock;
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    int port() {
      return server.getAddress().getPort();
    }

    String url() {
      return "http://127.0.0.1:" + port();
    }

    private void system(HttpExchange exchange) throws IOException {
      respond(exchange, 200, "{\"system\":{\"os\":\"synthetic\"}}");
    }

    private void prompt(HttpExchange exchange) throws IOException {
      JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
      String id = body.path("prompt_id").asText();
      String prefix = body.path("prompt").path("9").path("inputs").path("filename_prefix").asText();
      String[] parts = prefix.split("/");
      if (id.isBlank() || parts.length < 3)
        throw new IllegalArgumentException("unexpected prompt body");
      project.put(id, parts[1]);
      run.put(id, parts[2]);
      due.put(id, Instant.now().plusMillis(delayMillis));
      submits.incrementAndGet();
      respond(exchange, 200, "{\"prompt_id\":\"" + id + "\"}");
    }

    private void history(HttpExchange exchange) throws IOException {
      String id = exchange.getRequestURI().getPath().substring("/history/".length());
      if (cancelled.contains(id)) {
        respond(exchange, 200, "{}");
        return;
      }
      if (!due.containsKey(id) || Instant.now().isBefore(due.get(id))) {
        respond(exchange, 200, "{}");
        return;
      }
      completed.add(id);
      String filename = run.get(id) + "_00001_.png";
      String record =
          "{\""
              + id
              + "\":{\"status\":{\"status_str\":\"success\",\"completed\":true},\"outputs\":{\"9\":{\"images\":[{\"filename\":\""
              + filename
              + "\",\"subfolder\":\"HuiMeng/"
              + project.get(id)
              + "\",\"type\":\"output\"}]}}}}";
      respond(exchange, 200, record);
    }

    private void view(HttpExchange exchange) throws IOException {
      exchange.getResponseHeaders().set("Content-Type", "image/png");
      exchange.sendResponseHeaders(200, PNG.length);
      try (var out = exchange.getResponseBody()) {
        out.write(PNG);
      }
    }

    private void job(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      String[] parts = path.split("/");
      String id = parts.length > 3 ? parts[3] : "";
      if (path.endsWith("/cancel")) {
        cancelled.add(id);
        respond(exchange, 200, "{\"cancelled\":true}");
        return;
      }
      String status =
          cancelled.contains(id) ? "cancelled" : completed.contains(id) ? "completed" : "running";
      respond(exchange, 200, "{\"status\":\"" + status + "\"}");
    }

    private void stats(HttpExchange exchange) throws IOException {
      String active =
          String.join(
              ",",
              due.keySet().stream()
                  .filter(id -> !completed.contains(id) && !cancelled.contains(id))
                  .toList());
      respond(
          exchange,
          200,
          "{\"submits\":"
              + submits.get()
              + ",\"completed\":"
              + completed.size()
              + ",\"cancelled\":"
              + cancelled.size()
              + ",\"active\":\""
              + active
              + "\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  /** 合成文本供应商：首轮给出一次 generateImage 工具调用，收到工具结果后返回文本。 */
  static final class LlmMock {
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();

    private LlmMock(HttpServer server) {
      this.server = server;
    }

    static LlmMock start() {
      try {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        LlmMock mock = new LlmMock(server);
        server.createContext("/chat/completions", mock::completions);
        server.createContext("/stats", mock::stats);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return mock;
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    int port() {
      return server.getAddress().getPort();
    }

    String url() {
      return "http://127.0.0.1:" + port();
    }

    private void completions(HttpExchange exchange) throws IOException {
      JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
      requests.incrementAndGet();
      boolean hasToolResult = false;
      for (JsonNode message : body.path("messages"))
        if ("tool".equals(message.path("role").asText())) hasToolResult = true;
      StringBuilder frames = new StringBuilder();
      if (!hasToolResult) {
        toolCalls.incrementAndGet();
        String arguments = "{\"prompt\":\"A2A 团队任务生成的一张图\",\"size\":\"1K\",\"ratio\":\"1:1\"}";
        ObjectNode call = JSON.createObjectNode();
        call.put("index", 0);
        call.put("id", "call-capacity-1");
        call.put("type", "function");
        call.set(
            "function",
            JSON.createObjectNode().put("name", "generateImage").put("arguments", arguments));
        ObjectNode delta = JSON.createObjectNode();
        delta.putArray("tool_calls").add(call);
        ObjectNode frame = JSON.createObjectNode();
        ((ObjectNode) frame.putArray("choices").addObject()).set("delta", delta);
        frames.append("data: ").append(frame).append("\n\n");
        ObjectNode stop = JSON.createObjectNode();
        ((ObjectNode) stop.putArray("choices").addObject()).put("finish_reason", "tool_calls");
        frames.append("data: ").append(stop).append("\n\n");
      } else {
        ObjectNode delta = JSON.createObjectNode();
        delta.put("content", "已生成图片并保存到项目素材。");
        ObjectNode frame = JSON.createObjectNode();
        ((ObjectNode) frame.putArray("choices").addObject()).set("delta", delta);
        frames.append("data: ").append(frame).append("\n\n");
        ObjectNode stop = JSON.createObjectNode();
        ((ObjectNode) stop.putArray("choices").addObject()).put("finish_reason", "stop");
        frames.append("data: ").append(stop).append("\n\n");
      }
      frames.append("data: [DONE]\n\n");
      byte[] bytes = frames.toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }

    private void stats(HttpExchange exchange) throws IOException {
      ComfyMock.respond(
          exchange,
          200,
          "{\"requests\":" + requests.get() + ",\"toolCalls\":" + toolCalls.get() + "}");
    }
  }
}
