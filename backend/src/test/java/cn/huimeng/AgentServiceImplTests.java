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
    agents = new AgentServiceImpl(projects, files, models, llm, json);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void conversationCrudUsesWorkspaceFiles() {
    var created = agents.create("p1", "r1");
    assertEquals("新对话", created.name());
    assertTrue(created.file().endsWith(".json"));
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

  private void respondSse(String body) {
    server.createContext(
        "/chat/completions",
        exchange -> {
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
