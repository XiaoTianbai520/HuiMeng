package cn.huimeng;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.FileDownload;
import cn.huimeng.common.io.LlmClient;
import cn.huimeng.dao.mapper.ConfigurationMapper;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.SecretBox;
import cn.huimeng.service.*;
import cn.huimeng.service.impl.AgentServiceImpl;
import cn.huimeng.service.impl.AgentToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** 内存工作区 + 真实 LlmClient + 合成 SSE；验证会话、流式、错误与并发锁。 */
class AgentServiceImplTests {
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private AgentServiceImpl agents;
  private MemoryFiles files;
  private MediaGenerationService media;
  private cn.huimeng.service.impl.ToolRuntime toolRuntime;
  private final java.util.concurrent.atomic.AtomicInteger rounds =
      new java.util.concurrent.atomic.AtomicInteger();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    ConfigurationMapper configuration = mock(ConfigurationMapper.class);
    when(configuration.encryptedSecret("deepseek")).thenReturn("encrypted");
    SecretBox box = mock(SecretBox.class);
    when(box.decrypt("deepseek", "encrypted")).thenReturn("test-key");
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    LlmClient llm = new LlmClient(json, configuration, box, true, endpoint, endpoint);
    ProjectService projects = mock(ProjectService.class);
    ModelAuthorizationService models = mock(ModelAuthorizationService.class);
    files = new MemoryFiles();
    media = mock(MediaGenerationService.class);
    var catalog = new AgentToolCatalog(json);
    catalog.load();
    // 技能正文由能力登记提供；本类只验证工具循环，按无技能处理。
    var capabilities = mock(CapabilityService.class);
    when(capabilities.skillPrompt()).thenReturn("");
    var toolRuntime = mock(cn.huimeng.service.impl.ToolRuntime.class);
    when(toolRuntime.definitions()).thenReturn(java.util.List.of());
    when(toolRuntime.owns(anyString())).thenReturn(false);
    this.toolRuntime = toolRuntime;
    agents =
        new AgentServiceImpl(
            projects, files, models, llm, media, catalog, capabilities, toolRuntime, json);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void conversationCrudUsesWorkspaceFiles() {
    var created = agents.create("p1", "r1");
    assertEquals("新对话", created.name());
    assertTrue(created.file().endsWith(".jsonl"), created.file());
    assertEquals(0, agents.get("p1", created.file()).messages().size());
    var renamed = agents.rename("p1", created.file(), "剧本讨论", "r2");
    assertEquals("剧本讨论", renamed.name());
    assertEquals(1, agents.list("p1").size());
    assertEquals("剧本讨论", agents.list("p1").get(0).name());
    var error = assertThrows(ApiException.class, () -> agents.get("p1", "missing.json"));
    assertNotNull(error);
    assertThrows(ApiException.class, () -> agents.get("p1", "../escape"));
  }

  @Test
  void generateStreamsTextAndPersistsAssistantMessage() throws Exception {
    respondSse(
        "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
            + "data: [DONE]\n\n");
    var output = new ByteArrayOutputStream();
    agents.generate(
        "p1",
        new AgentGenerateReqDTO(
            "p1", "打个招呼", "deepseek", "deepseek-chat", null, null, null, null, null, null),
        output,
        "r1");
    String ndjson = output.toString(StandardCharsets.UTF_8);
    assertTrue(ndjson.contains("\"type\":\"userMessage\""), ndjson);
    assertTrue(ndjson.contains("\"type\":\"text\""), ndjson);
    assertTrue(ndjson.contains("\"delta\":\"你好\""), ndjson);
    assertTrue(ndjson.contains("\"type\":\"done\""), ndjson);
    String file = ndjsonFile(ndjson);
    var conversation = agents.get("p1", file);
    assertEquals(2, conversation.messages().size());
    assertEquals("你好，世界", conversation.messages().get(1).content());
    assertEquals("user", conversation.messages().get(0).role());
    assertEquals("assistant", conversation.messages().get(1).role());
    assertEquals(1, agents.list("p1").size());
  }

  @Test
  void compactsOlderTurnsButKeepsRecentPromptAndFullHistoryOnDisk() throws Exception {
    respondSse("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: [DONE]\n\n");
    String file = null;
    var prompts = new ArrayList<String>();
    for (int round = 0; round < 8; round++) {
      String prompt = "ROUND" + round + "-" + String.valueOf((char) ('A' + round)).repeat(10_000);
      prompts.add(prompt);
      var output = new ByteArrayOutputStream();
      agents.generate(
          "p1",
          new AgentGenerateReqDTO(
              "p1", prompt, "deepseek", "deepseek-chat", null, file, null, null, null, null),
          output,
          "r" + round);
      if (file == null) file = ndjsonFile(output.toString(StandardCharsets.UTF_8));
    }

    String last;
    synchronized (requestBodies) {
      last = requestBodies.get(requestBodies.size() - 1);
    }
    // 超过阈值后，较早轮次压成要点；最近一轮提问原样保留。
    assertTrue(last.contains("上下文说明"), last.substring(0, Math.min(400, last.length())));
    assertTrue(last.contains("[历史] ROUND0"), last.substring(0, Math.min(600, last.length())));
    assertFalse(last.contains(prompts.get(0)));
    assertTrue(last.contains(prompts.get(7)));

    // 压缩只作用于请求，会话文件仍是完整历史（8 轮 = 16 条）。
    var conversation = agents.get("p1", file);
    assertEquals(16, conversation.messages().size());
    assertEquals(prompts.get(0), conversation.messages().get(0).content());
  }

  @Test
  void generateEmitsSafeErrorWhenProviderFails() {
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.sendResponseHeaders(500, 0);
          exchange.close();
        });
    var output = new ByteArrayOutputStream();
    agents.generate(
        "p1",
        new AgentGenerateReqDTO(
            "p1", "测试", "deepseek", "deepseek-chat", null, null, null, null, null, null),
        output,
        "r1");
    assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"type\":\"error\""));
  }

  @Test
  void generateRejectsConcurrentSession() throws Exception {
    var created = agents.create("p1", "r1");
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            Thread.sleep(1500);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    var output = new ByteArrayOutputStream();
    var first =
        new Thread(
            () ->
                agents.generate(
                    "p1",
                    new AgentGenerateReqDTO(
                        "p1",
                        "第一次",
                        "deepseek",
                        "deepseek-chat",
                        null,
                        created.file(),
                        null,
                        null,
                        null,
                        null),
                    output,
                    "r1"));
    first.start();
    // 第一个生成在慢 SSE 期间持有会话锁；第二次同会话请求必须被拒绝。
    Thread.sleep(300);
    var error =
        assertThrows(
            ApiException.class,
            () ->
                agents.generate(
                    "p1",
                    new AgentGenerateReqDTO(
                        "p1",
                        "第二次",
                        "deepseek",
                        "deepseek-chat",
                        null,
                        created.file(),
                        null,
                        null,
                        null,
                        null),
                    new ByteArrayOutputStream(),
                    "r2"));
    assertEquals(409, error.status);
    assertEquals("AGENT_RUNNING", error.code);
    first.join(10000);
  }

  private void respondSequence(String... bodies) {
    rounds.set(0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          int index = Math.min(rounds.getAndIncrement(), bodies.length - 1);
          byte[] bytes = bodies[index].getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private String toolCallSse(String id, String name, String arguments) {
    String payload =
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\""
            + id
            + "\",\"function\":{\"name\":\""
            + name
            + "\",\"arguments\":"
            + json.valueToTree(arguments).toString()
            + "}}]}}]}\n\n";
    return "data: "
        + payload
        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
        + "data: [DONE]\n\n";
  }

  private String textSse(String text) {
    return "data: {\"choices\":[{\"delta\":{\"content\":"
        + json.valueToTree(text).toString()
        + "}}]}\n\n"
        + "data: [DONE]\n\n";
  }

  private void waitFor(ByteArrayOutputStream output, String needle, long millis) throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      if (output.toString(StandardCharsets.UTF_8).contains(needle)) return;
      Thread.sleep(50);
    }
    fail("未出现 " + needle + "：" + output.toString(StandardCharsets.UTF_8));
  }

  private com.fasterxml.jackson.databind.JsonNode lastEvent(
      ByteArrayOutputStream output, String type) throws IOException {
    com.fasterxml.jackson.databind.JsonNode found = null;
    for (String line : output.toString(StandardCharsets.UTF_8).split("\n")) {
      if (line.isBlank()) continue;
      var event = json.readTree(line);
      if (event.path("type").asText().equals(type)) found = event;
    }
    assertNotNull(found, "缺少事件 " + type);
    return found;
  }

  private final List<String> requestBodies = new ArrayList<>();

  private void respondSse(String body) {
    server.createContext(
        "/chat/completions",
        exchange -> {
          synchronized (requestBodies) {
            requestBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private String ndjsonFile(String ndjson) throws IOException {
    for (String line : ndjson.split("\n")) {
      if (line.isBlank()) continue;
      var event = json.readTree(line);
      if (event.path("type").asText().equals("session")) return event.path("file").asText();
    }
    throw new AssertionError("缺少 session 事件");
  }

  /** 已安装工具包的工具由服务端执行，结果写回会话并交给模型继续。 */
  @Test
  void installedToolCallIsExecutedByServerRuntime() throws Exception {
    when(toolRuntime.owns("echoTool")).thenReturn(true);
    when(toolRuntime.definitions())
        .thenReturn(
            List.of(
                Map.of(
                    "type",
                    "function",
                    "function",
                    Map.of(
                        "name",
                        "echoTool",
                        "description",
                        "回显",
                        "parameters",
                        Map.of("type", "object")))));
    when(toolRuntime.execute(eq("echoTool"), anyString(), any()))
        .thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"echo:hi\"}]}"));
    respondSequence(toolCallSse("call-echo", "echoTool", "{\"text\":\"hi\"}"), textSse("回显完成"));

    var output = new ByteArrayOutputStream();
    var worker =
        new Thread(
            () ->
                agents.generate(
                    "p1",
                    new AgentGenerateReqDTO(
                        "p1",
                        "调用回显工具",
                        "deepseek",
                        "deepseek-chat",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                    output,
                    "r-echo"));
    worker.start();
    worker.join(15000);

    String ndjson = output.toString(StandardCharsets.UTF_8);
    assertTrue(ndjson.contains("\"type\":\"tool\""), ndjson);
    assertTrue(ndjson.contains("\"status\":\"success\""), ndjson);
    assertTrue(ndjson.contains("回显完成"), ndjson);
    verify(toolRuntime).execute(eq("echoTool"), eq("call-echo"), any());
  }

  @Test
  void canvasToolCallIsExecutedByPageAndResultReturnedToModel() throws Exception {
    respondSequence(toolCallSse("call-canvas", "getCanvas", "{}"), textSse("画布里有 3 个节点"));
    var output = new ByteArrayOutputStream();
    var worker =
        new Thread(
            () ->
                agents.generate(
                    "p1",
                    new AgentGenerateReqDTO(
                        "p1",
                        "看看画布",
                        "deepseek",
                        "deepseek-chat",
                        null,
                        null,
                        null,
                        null,
                        null,
                        json.createObjectNode().put("id", "画布1.json")),
                    output,
                    "r1"));
    worker.start();
    waitFor(output, "\"type\":\"canvasCall\"", 5000);
    String callId = lastEvent(output, "canvasCall").path("callId").asText();
    agents.canvasResult(
        "p1", new AgentCanvasResultReqDTO("p1", callId, json.readTree("{\"nodeCount\":3}"), null));
    worker.join(15000);
    String ndjson = output.toString(StandardCharsets.UTF_8);
    assertTrue(ndjson.contains("\"type\":\"tool\""), ndjson);
    assertTrue(ndjson.contains("\"status\":\"success\""), ndjson);
    assertTrue(ndjson.contains("\"type\":\"done\""), ndjson);
    assertTrue(ndjson.contains("画布里有 3 个节点"), ndjson);
    String file = ndjsonFile(ndjson);
    String stored = files.texts.get(".agent/sessions/" + file);
    assertTrue(stored.contains("\"type\":\"session\""), stored);
    assertTrue(stored.contains("\"type\":\"tool\""), stored);
    var conversation = agents.get("p1", file);
    assertEquals(2, conversation.messages().size());
  }

  @Test
  void canvasCallUnknownToProjectIsRejected() throws Exception {
    respondSequence(toolCallSse("call-x", "getCanvas", "{}"), textSse("完成"));
    var output = new ByteArrayOutputStream();
    var worker =
        new Thread(
            () ->
                agents.generate(
                    "p1",
                    new AgentGenerateReqDTO(
                        "p1",
                        "看看画布",
                        "deepseek",
                        "deepseek-chat",
                        null,
                        null,
                        null,
                        null,
                        null,
                        json.createObjectNode().put("id", "画布1.json")),
                    output,
                    "r1"));
    worker.start();
    waitFor(output, "\"type\":\"canvasCall\"", 5000);
    String callId = lastEvent(output, "canvasCall").path("callId").asText();
    var error =
        assertThrows(
            ApiException.class,
            () ->
                agents.canvasResult(
                    "other-project",
                    new AgentCanvasResultReqDTO(
                        "other-project", callId, json.createObjectNode(), null)));
    assertEquals(404, error.status);
    agents.canvasResult(
        "p1", new AgentCanvasResultReqDTO("p1", callId, json.createObjectNode(), null));
    worker.join(15000);
  }

  @Test
  void questionWaitsForAnswerAndPassesItBackAsToolResult() throws Exception {
    respondSequence(
        toolCallSse(
            "call-ask",
            "askQuestion",
            "{\"title\":\"配色\",\"question\":\"杯子要什么颜色？\",\"options\":[\"红色\",\"蓝色\"]}"),
        textSse("已按蓝色继续"));
    var output = new ByteArrayOutputStream();
    var worker =
        new Thread(
            () ->
                agents.generate(
                    "p1",
                    new AgentGenerateReqDTO(
                        "p1",
                        "画个杯子",
                        "deepseek",
                        "deepseek-chat",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                    output,
                    "r1"));
    worker.start();
    waitFor(output, "\"type\":\"question\"", 5000);
    var question = lastEvent(output, "question");
    assertEquals("配色", question.path("title").asText());
    assertEquals("call-ask", question.path("toolCallId").asText());
    agents.answer(
        "p1",
        new AgentAnswerReqDTO("p1", question.path("callId").asText(), false, false, "蓝色", null));
    worker.join(15000);
    String ndjson = output.toString(StandardCharsets.UTF_8);
    assertTrue(ndjson.contains("已按蓝色继续"), ndjson);
    assertTrue(ndjson.contains("\"type\":\"done\""), ndjson);
  }

  @Test
  void internalImageGenerationUsesOneToolCallWithoutExtraCapacity() throws Exception {
    when(media.generateInternal(
            anyString(), any(GenerateImageReqDTO.class), anyString(), anyString()))
        .thenReturn(
            new GeneratedMediaRespDTO(
                ".huimeng-media/abc.png", "image/png", "image", "1K 1:1 1024×1024"));
    respondSequence(
        toolCallSse("call-image", "generateImage", "{\"prompt\":\"白色茶杯\"}"),
        textSse("已生成 .huimeng-media/abc.png"));
    var output = new ByteArrayOutputStream();
    agents.generate(
        "p1",
        new AgentGenerateReqDTO(
            "p1", "生成一张图", "deepseek", "deepseek-chat", null, null, null, null, null, null),
        output,
        "r1");
    String ndjson = output.toString(StandardCharsets.UTF_8);
    assertTrue(ndjson.contains("\"status\":\"success\""), ndjson);
    verify(media, times(1))
        .generateInternal(
            eq("p1"),
            any(GenerateImageReqDTO.class),
            eq("agent-call-image"),
            eq("agent-call-image"));
    verify(media, never())
        .generate(anyString(), any(GenerateImageReqDTO.class), anyString(), anyString());
  }

  @Test
  void cancelWakesPendingCanvasCallAndStopsStream() throws Exception {
    respondSequence(toolCallSse("call-slow", "getCanvas", "{}"), textSse("不应到达"));
    var output = new ByteArrayOutputStream();
    String file = agents.create("p1", "r0").file();
    var worker =
        new Thread(
            () ->
                agents.generate(
                    "p1",
                    new AgentGenerateReqDTO(
                        "p1",
                        "看看画布",
                        "deepseek",
                        "deepseek-chat",
                        null,
                        file,
                        null,
                        null,
                        null,
                        json.createObjectNode().put("id", "画布1.json")),
                    output,
                    "r1"));
    worker.start();
    waitFor(output, "\"type\":\"canvasCall\"", 5000);
    agents.cancel("p1", file);
    worker.join(15000);
    String ndjson = output.toString(StandardCharsets.UTF_8);
    assertTrue(ndjson.contains("已停止生成"), ndjson);
    assertFalse(ndjson.contains("不应到达"), ndjson);
  }

  /** 仅模拟会话所需的安全文件操作。 */
  private static final class MemoryFiles implements ProjectFileService {
    private final Map<String, String> texts = new HashMap<>();
    private final Map<String, String> revisions = new HashMap<>();
    private long counter;

    @Override
    public FileListRespDTO list(String projectId, String path) {
      var entries = new ArrayList<FileListRespDTO.Entry>();
      for (String key : texts.keySet()) {
        if (key.startsWith(path + "/")) {
          String name = key.substring(path.length() + 1);
          if (!name.contains("/")) entries.add(new FileListRespDTO.Entry(name, key, "file"));
        }
      }
      return new FileListRespDTO(
          200, new FileListRespDTO.Data(projectId, entries.isEmpty(), entries), null);
    }

    @Override
    public FileOperationRespDTO mkdir(String projectId, String path, String requestId) {
      throw new ApiException(409, "FILE_ALREADY_EXISTS", "已存在");
    }

    @Override
    public FileOperationRespDTO transfer(
        String projectId, FileTransferReqDTO input, boolean copy, String requestId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileOperationRespDTO remove(String projectId, FileRemoveReqDTO input, String requestId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SnapshotResultRespDTO snapshot(
        String projectId, String path, Long maxBytes, String range) {
      if (!texts.containsKey(path)) throw new ApiException(404, "NOT_FOUND", "文件不存在");
      return new SnapshotResultRespDTO(
          200, new SnapshotRespDTO(texts.get(path), "utf-8", revisions.get(path)), null);
    }

    @Override
    public FileDownload download(String projectId, String path, Long maxBytes, String range) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WriteResultRespDTO write(
        String projectId, FileWriteReqDTO input, InputStream content, String requestId) {
      try {
        String text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
        if (input.exclusive() && texts.containsKey(input.path()))
          throw new ApiException(409, "REVISION_CONFLICT", "已存在");
        if (!input.exclusive()
            && input.expectedRevision() != null
            && !input.expectedRevision().equals(revisions.get(input.path())))
          throw new ApiException(409, "REVISION_CONFLICT", "冲突");
        String revision = "rev-" + (++counter);
        texts.put(input.path(), text);
        revisions.put(input.path(), revision);
        return new WriteResultRespDTO(200, new WriteResultRespDTO.Revision(revision), null);
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
  }
}
