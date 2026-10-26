package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.LlmClient;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Agent 会话存于项目 .agent/sessions，沿用工作区安全文件 API；不新增数据库写入者。 */
@Service
public class AgentServiceImpl implements AgentService {
  private static final String SESSION_DIR = ".agent/sessions";
  private static final long MAX_SESSION_BYTES = 8 * 1024 * 1024;
  private static final int MAX_HISTORY_READ = 100;
  private static final int MAX_PROMPT_CHARS = 32000;
  private final ProjectService projects;
  private final ProjectFileService files;
  private final ModelAuthorizationService models;
  private final LlmClient llm;
  private final ObjectMapper json;
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Set<String> cancelling = ConcurrentHashMap.newKeySet();

  public AgentServiceImpl(
      ProjectService projects,
      ProjectFileService files,
      ModelAuthorizationService models,
      LlmClient llm,
      ObjectMapper json) {
    this.projects = projects;
    this.files = files;
    this.models = models;
    this.llm = llm;
    this.json = json;
  }

  @Override
  public AgentConversationRespDTO create(String projectId, String requestId) {
    projects.requireMember(projectId, true);
    String file = UUID.randomUUID() + ".json";
    saveSession(projectId, file, "新对话", List.of(), null, null, null, null, requestId);
    return new AgentConversationRespDTO(file, "新对话", List.of(), null, null, null, false);
  }

  @Override
  public AgentConversationRespDTO get(String projectId, String sessionFile) {
    projects.requireMember(projectId, true);
    SessionState session = readSession(projectId, sessionFile);
    return conversation(sessionFile, session);
  }

  @Override
  public List<AgentHistoryRespDTO> list(String projectId) {
    projects.requireMember(projectId, true);
    var result = new ArrayList<AgentHistoryRespDTO>();
    var listing = files.list(projectId, SESSION_DIR);
    int read = 0;
    for (var entry : listing.data().entries()) {
      if (!entry.type().equals("file") || !entry.name().endsWith(".json")) continue;
      if (++read > MAX_HISTORY_READ) break;
      try {
        SessionState session = readSession(projectId, entry.name());
        result.add(
            new AgentHistoryRespDTO(
                entry.name(), session.name, session.modified, session.messages.size()));
      } catch (ApiException ignored) {
        // 忽略损坏会话，不让单个文件阻断整个历史列表。
      }
    }
    result.sort(Comparator.comparing(AgentHistoryRespDTO::modified).reversed());
    return result;
  }

  @Override
  public AgentConversationRespDTO rename(
      String projectId, String sessionFile, String name, String requestId) {
    projects.requireMember(projectId, true);
    if (name == null || name.isBlank() || name.length() > 80) throw ApiException.invalid();
    SessionState session = readSession(projectId, sessionFile);
    saveSession(
        projectId,
        sessionFile,
        name.trim(),
        session.messages,
        session.providerId,
        session.modelId,
        session.thinkingLevel,
        session.revision,
        requestId);
    return new AgentConversationRespDTO(
        sessionFile,
        name.trim(),
        session.messages,
        session.providerId,
        session.modelId,
        session.thinkingLevel,
        running.contains(projectId + "/" + sessionFile));
  }

  @Override
  public AgentConversationRespDTO deleteMessage(
      String projectId, String sessionFile, String entryId, String requestId) {
    projects.requireMember(projectId, true);
    if (entryId == null || entryId.isBlank()) throw ApiException.invalid();
    SessionState session = readSession(projectId, sessionFile);
    var messages =
        session.messages.stream()
            .filter(
                message -> !entryId.equals(message.entryId()) && !entryId.equals(message.replyTo()))
            .toList();
    saveSession(
        projectId,
        sessionFile,
        session.name,
        messages,
        session.providerId,
        session.modelId,
        session.thinkingLevel,
        session.revision,
        requestId);
    return new AgentConversationRespDTO(
        sessionFile,
        session.name,
        messages,
        session.providerId,
        session.modelId,
        session.thinkingLevel,
        running.contains(projectId + "/" + sessionFile));
  }

  @Override
  public void generate(
      String projectId, AgentGenerateReqDTO input, OutputStream output, String requestId) {
    projects.requireMember(projectId, true);
    if (input.prompt() == null
        || input.prompt().isBlank()
        || input.prompt().length() > MAX_PROMPT_CHARS) throw ApiException.invalid();
    if (input.providerId() == null || input.modelId() == null) throw ApiException.invalid();
    models.requireApproved(projectId, input.providerId(), input.modelId());
    String sessionFile = input.sessionFile();
    if (sessionFile != null && !sessionFile.matches("[A-Za-z0-9._-]{1,128}"))
      throw ApiException.invalid();
    String file = sessionFile == null ? UUID.randomUUID() + ".json" : sessionFile;
    String lockKey = projectId + "/" + file;
    if (!running.add(lockKey)) throw new ApiException(409, "AGENT_RUNNING", "该对话正在生成中，请稍后再试");
    try {
      SessionState existing = sessionFile == null ? null : readSession(projectId, sessionFile);
      String name = existing == null ? "新对话" : existing.name;
      var messages = new ArrayList<AgentMessageRespDTO>();
      if (existing != null) messages.addAll(existing.messages);
      String entryId = UUID.randomUUID().toString();
      messages.add(
          new AgentMessageRespDTO(
              entryId,
              entryId,
              null,
              "user",
              input.prompt(),
              null,
              input.attachments(),
              input.mentions(),
              null));
      String revision =
          saveSession(
              projectId,
              file,
              name,
              messages,
              input.providerId(),
              input.modelId(),
              input.thinkingLevel(),
              existing == null ? null : existing.revision,
              requestId);
      if (existing == null) {
        var sessionEvent = json.createObjectNode();
        sessionEvent.put("type", "session");
        sessionEvent.put("file", file);
        emit(output, sessionEvent);
      }
      emit(output, userMessage(entryId, input));
      String blockId = UUID.randomUUID().toString();
      var answer = new StringBuilder();
      boolean cancelled = false;
      try (var stream = llm.chat(input.providerId(), input.modelId(), history(messages))) {
        String delta;
        while ((delta = stream.nextDelta()) != null) {
          // 取消标志在下一个增量前生效；同时关闭上游流，不依赖断线事件。
          if (cancelling.remove(lockKey)) {
            cancelled = true;
            break;
          }
          answer.append(delta);
          emitText(output, blockId, delta, false);
        }
      }
      emitText(output, blockId, null, true);
      if (answer.length() > 0 || !cancelled) {
        messages.add(
            new AgentMessageRespDTO(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                entryId,
                "assistant",
                answer.toString(),
                textParts(blockId, answer.toString()),
                null,
                null,
                null));
        saveSession(
            projectId,
            file,
            name,
            messages,
            input.providerId(),
            input.modelId(),
            input.thinkingLevel(),
            revision,
            requestId);
      }
      emit(output, json.createObjectNode().put("type", "done"));
    } catch (ApiException ex) {
      emitError(output, ex.getMessage());
    } catch (IOException ex) {
      // 输出流关闭（前端停止）会走到这里，上游流已在 try-with-resources 中关闭。
    } catch (Exception ex) {
      emitError(output, "生成失败，请稍后重试");
    } finally {
      running.remove(lockKey);
      cancelling.remove(lockKey);
    }
  }

  @Override
  public void cancel(String projectId, String sessionFile) {
    projects.requireMember(projectId, true);
    if (sessionFile == null || !sessionFile.matches("[A-Za-z0-9._-]{1,128}"))
      throw ApiException.invalid();
    cancelling.add(projectId + "/" + sessionFile);
  }

  private List<Map<String, String>> history(List<AgentMessageRespDTO> messages) {
    var result = new ArrayList<Map<String, String>>();
    result.add(Map.of("role", "system", "content", "你是绘梦创作助手，使用简体中文回答。"));
    for (var message : messages) {
      if (message.content() == null || message.content().isBlank()) continue;
      result.add(Map.of("role", message.role(), "content", message.content()));
    }
    return result;
  }

  private ObjectNode userMessage(String entryId, AgentGenerateReqDTO input) {
    var event = json.createObjectNode();
    event.put("type", "userMessage");
    event.put("id", entryId);
    event.put("content", input.prompt());
    if (input.attachments() != null) event.set("attachments", input.attachments());
    if (input.mentions() != null) event.set("mentions", input.mentions());
    return event;
  }

  private void emitText(OutputStream output, String blockId, String delta, boolean done)
      throws IOException {
    var event = json.createObjectNode();
    event.put("type", "text");
    event.put("blockId", blockId);
    if (delta != null) event.put("delta", delta);
    if (done) event.put("done", true);
    emit(output, event);
  }

  private void emitError(OutputStream output, String message) {
    try {
      var event = json.createObjectNode();
      event.put("type", "error");
      event.put("message", message);
      emit(output, event);
    } catch (IOException ignored) {
    }
  }

  private void emit(OutputStream output, ObjectNode event) throws IOException {
    output.write(json.writeValueAsBytes(event));
    output.write('\n');
    output.flush();
  }

  private JsonNode textParts(String blockId, String content) {
    var parts = json.createArrayNode();
    var part = parts.addObject();
    part.put("id", blockId);
    part.put("type", "text");
    part.put("content", content);
    return parts;
  }

  private AgentConversationRespDTO conversation(String file, SessionState session) {
    return new AgentConversationRespDTO(
        file,
        session.name,
        session.messages,
        session.providerId,
        session.modelId,
        session.thinkingLevel,
        running.contains(session.projectId + "/" + file));
  }

  private SessionState readSession(String projectId, String sessionFile) {
    if (sessionFile == null || !sessionFile.matches("[A-Za-z0-9._-]{1,128}"))
      throw ApiException.invalid();
    var snapshot =
        files.snapshot(projectId, SESSION_DIR + "/" + sessionFile, MAX_SESSION_BYTES, null);
    try {
      JsonNode root = json.readTree(snapshot.data().text());
      var messages = new ArrayList<AgentMessageRespDTO>();
      for (var item : root.path("messages"))
        messages.add(json.treeToValue(item, AgentMessageRespDTO.class));
      return new SessionState(
          projectId,
          root.path("name").asText("新对话"),
          root.path("modified").asText(Instant.now().toString()),
          root.path("providerId").asText(null),
          root.path("modelId").asText(null),
          root.path("thinkingLevel").asText(null),
          messages,
          snapshot.data().revision());
    } catch (IOException ex) {
      throw new ApiException(503, "SESSION_READ_FAILED", "会话文件读取失败");
    }
  }

  private String saveSession(
      String projectId,
      String file,
      String name,
      List<AgentMessageRespDTO> messages,
      String providerId,
      String modelId,
      String thinkingLevel,
      String revision,
      String requestId) {
    mkdir(projectId, requestId);
    var root = json.createObjectNode();
    root.put("file", file);
    root.put("name", name);
    root.put("modified", Instant.now().toString());
    if (providerId != null) root.put("providerId", providerId);
    if (modelId != null) root.put("modelId", modelId);
    if (thinkingLevel != null) root.put("thinkingLevel", thinkingLevel);
    root.set("messages", json.valueToTree(messages));
    byte[] content;
    try {
      content = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
    var result =
        files.write(
            projectId,
            new FileWriteReqDTO(
                SESSION_DIR + "/" + file, revision, revision == null, "utf-8", content.length),
            new ByteArrayInputStream(content),
            requestId);
    return result.data().revision();
  }

  private void mkdir(String projectId, String requestId) {
    // 逐级创建会话目录；已存在时忽略，其他错误不吞掉。
    for (String path : new String[] {".agent", SESSION_DIR}) {
      try {
        files.mkdir(projectId, path, requestId);
      } catch (ApiException ex) {
        if (!ex.code.equals("FILE_ALREADY_EXISTS")) throw ex;
      }
    }
  }

  private static final class SessionState {
    private final String projectId;
    private final String name;
    private final String modified;
    private final String providerId;
    private final String modelId;
    private final String thinkingLevel;
    private final List<AgentMessageRespDTO> messages;
    private final String revision;

    private SessionState(
        String projectId,
        String name,
        String modified,
        String providerId,
        String modelId,
        String thinkingLevel,
        List<AgentMessageRespDTO> messages,
        String revision) {
      this.projectId = projectId;
      this.name = name;
      this.modified = modified;
      this.providerId = providerId;
      this.modelId = modelId;
      this.thinkingLevel = thinkingLevel;
      this.messages = messages;
      this.revision = revision;
    }
  }
}
