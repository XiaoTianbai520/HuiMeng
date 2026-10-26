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
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/**
 * Agent 会话以 `.jsonl` 存于项目 .agent/sessions，沿用工作区安全文件 API；不新增数据库写入者。
 *
 * <p>工具循环：模型给出工具调用后，画布操作转交页面执行并等待结果，提问等待页面回答，生图在本服务内同步执行且只占一个名额。
 */
@Service
public class AgentServiceImpl implements AgentService {
  private static final String SESSION_DIR = ".agent/sessions";
  private static final long MAX_SESSION_BYTES = 8 * 1024 * 1024;
  private static final int MAX_HISTORY_READ = 100;
  private static final int MAX_PROMPT_CHARS = 32000;
  private static final int MAX_ROUNDS = 6;
  private static final long CANVAS_TIMEOUT_SECONDS = 120;
  private static final long QUESTION_TIMEOUT_SECONDS = 300;
  private static final int MAX_TOOL_RESULT_CHARS = 8000;
  private static final String PROVIDER = "qwenImage21Local";
  private final ProjectService projects;
  private final ProjectFileService files;
  private final ModelAuthorizationService models;
  private final LlmClient llm;
  private final MediaGenerationService media;
  private final AgentToolCatalog catalog;
  private final ObjectMapper json;
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();
  private final Map<String, PendingCall> pending = new ConcurrentHashMap<>();

  public AgentServiceImpl(
      ProjectService projects,
      ProjectFileService files,
      ModelAuthorizationService models,
      LlmClient llm,
      MediaGenerationService media,
      AgentToolCatalog catalog,
      ObjectMapper json) {
    this.projects = projects;
    this.files = files;
    this.models = models;
    this.llm = llm;
    this.media = media;
    this.catalog = catalog;
    this.json = json;
  }

  private record PendingCall(
      String projectId, String sessionFile, CompletableFuture<JsonNode> future) {}

  /** 内部取消信号，不向页面输出堆栈。 */
  private static final class Cancelled extends RuntimeException {}

  @Override
  public AgentConversationRespDTO create(String projectId, String requestId) {
    projects.requireMember(projectId, true);
    String file = UUID.randomUUID() + ".jsonl";
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
    FileListRespDTO listing;
    try {
      listing = files.list(projectId, SESSION_DIR);
    } catch (ApiException ex) {
      // 首次进入项目时还没有会话目录，按空列表处理，不把 404 暴露给页面。
      if (ex.status == 404) return result;
      throw ex;
    }
    int read = 0;
    for (var entry : listing.data().entries()) {
      if (!entry.type().equals("file")) continue;
      if (!entry.name().endsWith(".jsonl") && !entry.name().endsWith(".json")) continue;
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
    String file = sessionFile == null ? UUID.randomUUID() + ".jsonl" : sessionFile;
    String lockKey = projectId + "/" + file;
    if (!running.add(lockKey)) throw new ApiException(409, "AGENT_RUNNING", "该对话正在生成中，请稍后再试");
    cancelRequested.remove(lockKey);
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
      boolean withCanvas =
          input.canvas() != null && !input.canvas().path("id").asText("").isBlank();
      var tools = catalog.definitions(withCanvas);
      var conversation = chatMessages(messages);
      var parts = json.createArrayNode();
      var answerText = new StringBuilder();
      for (int round = 0; round < MAX_ROUNDS; round++) {
        if (cancelled(lockKey)) throw new Cancelled();
        String blockId = UUID.randomUUID().toString();
        var roundText = new StringBuilder();
        LlmClient.Turn turn =
            llm.converse(
                input.providerId(),
                input.modelId(),
                conversation,
                tools,
                delta -> {
                  if (cancelled(lockKey)) throw new Cancelled();
                  roundText.append(delta);
                  try {
                    emitText(output, blockId, delta, false);
                  } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                  }
                });
        emitText(output, blockId, null, true);
        answerText.append(roundText);
        addTextPart(parts, blockId, roundText.toString());
        if (turn.calls().isEmpty()) break;
        conversation.add(assistantToolCalls(roundText.toString(), turn.calls()));
        for (var call : turn.calls()) {
          if (cancelled(lockKey)) throw new Cancelled();
          String partId = UUID.randomUUID().toString();
          var toolPart = toolPart(partId, call, "running", null);
          emitTool(output, partId, toolPart);
          JsonNode result;
          var status = "success";
          try {
            result = runTool(projectId, lockKey, file, input, call, output);
          } catch (Cancelled ex) {
            throw ex;
          } catch (ApiException ex) {
            status = "error";
            result = json.createObjectNode().put("error", ex.getMessage()).put("code", ex.code);
          } catch (RuntimeException ex) {
            status = "error";
            result =
                json.createObjectNode().put("error", "工具执行失败，请稍后重试").put("code", "TOOL_FAILED");
          }
          var finalPart = toolPart(partId, call, status, result);
          emitTool(output, partId, finalPart);
          parts.add(finalPart);
          conversation.add(toolResult(call, result));
        }
      }
      messages.add(
          new AgentMessageRespDTO(
              UUID.randomUUID().toString(),
              UUID.randomUUID().toString(),
              entryId,
              "assistant",
              answerText.toString(),
              parts,
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
      emit(output, json.createObjectNode().put("type", "done"));
    } catch (Cancelled ex) {
      emitError(output, "已停止生成");
    } catch (UncheckedIOException ex) {
      // 页面断开；上游流已随 converse 返回而关闭，不再写会话。
    } catch (ApiException ex) {
      emitError(output, ex.getMessage());
    } catch (IOException ex) {
      // 输出流关闭（前端停止）导致，不视为服务错误。
    } catch (Exception ex) {
      emitError(output, "生成失败，请稍后重试");
    } finally {
      running.remove(lockKey);
      cancelRequested.remove(lockKey);
      pending.values().removeIf(call -> call.sessionFile().equals(file));
    }
  }

  @Override
  public void cancel(String projectId, String sessionFile) {
    projects.requireMember(projectId, true);
    if (sessionFile == null || !sessionFile.matches("[A-Za-z0-9._-]{1,128}"))
      throw ApiException.invalid();
    String lockKey = projectId + "/" + sessionFile;
    cancelRequested.add(lockKey);
    for (var entry : pending.entrySet()) {
      PendingCall call = entry.getValue();
      if (call.projectId().equals(projectId) && call.sessionFile().equals(sessionFile))
        call.future().complete(json.createObjectNode().put("cancelled", true).put("ok", false));
    }
  }

  @Override
  public void answer(String projectId, AgentAnswerReqDTO input) {
    projects.requireMember(projectId, false);
    complete(
        projectId,
        input.callId(),
        json.createObjectNode()
            .put("ok", !Boolean.TRUE.equals(input.cancelled()))
            .put("answer", input.answer())
            .put("cancelled", Boolean.TRUE.equals(input.cancelled()))
            .put("skipped", Boolean.TRUE.equals(input.skipped()))
            .set("values", input.values() == null ? json.createObjectNode() : input.values()));
  }

  @Override
  public void canvasResult(String projectId, AgentCanvasResultReqDTO input) {
    projects.requireMember(projectId, false);
    var payload = json.createObjectNode();
    payload.put("ok", input.error() == null || input.error().isBlank());
    if (input.error() != null && !input.error().isBlank()) payload.put("error", input.error());
    payload.set("result", input.result() == null ? json.createObjectNode() : input.result());
    complete(projectId, input.callId(), payload);
  }

  private void complete(String projectId, String callId, JsonNode payload) {
    PendingCall call = callId == null ? null : pending.get(callId);
    if (call == null || !call.projectId().equals(projectId))
      throw new ApiException(404, "TOOL_CALL_NOT_FOUND", "该工具调用已结束或不属于此项目");
    call.future().complete(payload);
  }

  /** 执行一次工具调用；画布与提问交给页面，生图在本服务内完成。 */
  private JsonNode runTool(
      String projectId,
      String lockKey,
      String sessionFile,
      AgentGenerateReqDTO input,
      LlmClient.ToolCall call,
      OutputStream output)
      throws IOException {
    JsonNode args = arguments(call.arguments());
    if (catalog.isCanvasTool(call.name())) {
      if (input.canvas() == null || input.canvas().path("id").asText("").isBlank())
        throw new ApiException(400, "CANVAS_UNAVAILABLE", "当前页面没有激活的画布");
      String callId = UUID.randomUUID().toString();
      var payload = json.createObjectNode();
      payload.put("type", "canvasCall");
      payload.put("callId", callId);
      payload.put("name", call.name());
      payload.set("args", args);
      JsonNode result =
          request(callId, projectId, sessionFile, output, payload, CANVAS_TIMEOUT_SECONDS);
      if (result.path("cancelled").asBoolean())
        throw new ApiException(400, "CANVAS_CANCELLED", "画布操作已取消");
      if (!result.path("ok").asBoolean())
        throw new ApiException(400, "CANVAS_FAILED", result.path("error").asText("画布操作失败"));
      return result.path("result");
    }
    if (AgentToolCatalog.ASK.equals(call.name())) {
      String callId = UUID.randomUUID().toString();
      var payload = json.createObjectNode();
      payload.put("type", "question");
      payload.put("callId", callId);
      payload.put("toolCallId", call.id());
      payload.put("title", args.path("title").asText("需要确认"));
      payload.put("question", args.path("question").asText(""));
      if (args.has("options")) payload.set("options", args.path("options"));
      if (args.has("fields")) payload.set("fields", args.path("fields"));
      JsonNode result =
          request(callId, projectId, sessionFile, output, payload, QUESTION_TIMEOUT_SECONDS);
      if (result.path("cancelled").asBoolean() || !result.path("ok").asBoolean())
        throw new ApiException(400, "QUESTION_CANCELLED", "用户取消了本次提问");
      return result;
    }
    if (AgentToolCatalog.GENERATE_IMAGE.equals(call.name())) {
      var generated =
          media.generateInternal(
              projectId, imageRequest(args), "agent-" + call.id(), "agent-" + call.id());
      var result = json.createObjectNode();
      result.put("path", generated.path());
      result.put("mimeType", generated.mimeType());
      result.put("spec", generated.spec());
      return result;
    }
    throw new ApiException(400, "TOOL_UNKNOWN", "未接通该工具：" + call.name());
  }

  private GenerateImageReqDTO imageRequest(JsonNode args) {
    String prompt = args.path("prompt").asText("");
    if (prompt.isBlank() || prompt.length() > 8000) throw ApiException.invalid();
    boolean edit = "edit".equals(args.path("model").asText("text"));
    String reference = args.path("referencePath").asText("");
    List<GenerateImageMediaDTO> images = null;
    if (edit) {
      if (!reference.matches("^\\.huimeng-media/[a-f0-9-]{36}\\.png$"))
        throw new ApiException(400, "INVALID_ARGUMENT", "单图编辑必须给出项目内的 PNG 参考图路径");
      images = List.of(new GenerateImageMediaDTO(reference, "image/png"));
    }
    return new GenerateImageReqDTO(
        PROVIDER,
        edit ? "qwenImage21Edit" : "qwenImage21Text",
        "image",
        prompt,
        args.path("size").asText(null),
        args.path("ratio").asText(null),
        images);
  }

  /** 发出需要页面参与的事件并等待回传；超时、取消、页面断开都按失败处理。 */
  private JsonNode request(
      String callId,
      String projectId,
      String sessionFile,
      OutputStream output,
      ObjectNode payload,
      long seconds)
      throws IOException {
    var future = new CompletableFuture<JsonNode>();
    pending.put(callId, new PendingCall(projectId, sessionFile, future));
    try {
      emit(output, payload);
      Instant deadline = Instant.now().plusSeconds(seconds);
      String lockKey = projectId + "/" + sessionFile;
      while (Instant.now().isBefore(deadline)) {
        if (cancelled(lockKey)) throw new Cancelled();
        try {
          return future.get(200, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
          // 继续等待页面回传。
        }
      }
      throw new ApiException(504, "TOOL_TIMEOUT", "等待页面响应超时");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new Cancelled();
    } catch (ExecutionException ex) {
      throw new ApiException(400, "TOOL_FAILED", "工具执行失败");
    } finally {
      pending.remove(callId);
    }
  }

  private boolean cancelled(String lockKey) {
    return cancelRequested.contains(lockKey);
  }

  private JsonNode arguments(String raw) {
    if (raw == null || raw.isBlank()) return json.createObjectNode();
    try {
      return json.readTree(raw);
    } catch (IOException ex) {
      throw new ApiException(400, "TOOL_ARGUMENTS_INVALID", "模型给出的工具参数不是合法 JSON");
    }
  }

  private List<Map<String, Object>> chatMessages(List<AgentMessageRespDTO> messages) {
    var result = new ArrayList<Map<String, Object>>();
    result.add(Map.of("role", "system", "content", systemPrompt()));
    for (var message : messages) {
      if (message.parts() != null && message.parts().isArray()) {
        var calls = new ArrayList<Map<String, Object>>();
        for (var part : message.parts()) {
          if (!"tool".equals(part.path("type").asText())) continue;
          var tool = part.path("tool");
          var call = new LinkedHashMap<String, Object>();
          call.put("id", tool.path("id").asText());
          call.put("type", "function");
          call.put(
              "function",
              Map.of(
                  "name",
                  tool.path("name").asText(),
                  "arguments",
                  tool.path("args").isMissingNode() ? "{}" : tool.path("args").toString()));
          calls.add(call);
        }
        if (!calls.isEmpty()) {
          var assistant = new LinkedHashMap<String, Object>();
          assistant.put("role", "assistant");
          assistant.put("content", message.content());
          assistant.put("tool_calls", calls);
          result.add(assistant);
          for (var part : message.parts()) {
            if (!"tool".equals(part.path("type").asText())) continue;
            var tool = part.path("tool");
            var reply = new LinkedHashMap<String, Object>();
            reply.put("role", "tool");
            reply.put("tool_call_id", tool.path("id").asText());
            reply.put(
                "content",
                truncate(tool.path("result").isMissingNode() ? "" : tool.path("result").asText()));
            result.add(reply);
          }
          continue;
        }
      }
      if (message.content() == null || message.content().isBlank()) continue;
      result.add(Map.of("role", message.role(), "content", message.content()));
    }
    return result;
  }

  private String systemPrompt() {
    return "你是绘梦创作助手，使用简体中文回答。只有在确实需要画布状态或执行画布操作时才调用画布工具，"
        + "修改画布后先用 getCanvas 确认结果。缺少关键信息时用 askQuestion 询问用户，不要自行编造设定。"
        + "需要图片时调用 generateImage，成功后在回复中给出保存到项目素材的路径。";
  }

  private Map<String, Object> assistantToolCalls(String content, List<LlmClient.ToolCall> calls) {
    var list = new ArrayList<Map<String, Object>>();
    for (var call : calls) {
      list.add(
          Map.of(
              "id",
              call.id(),
              "type",
              "function",
              "function",
              Map.of("name", call.name(), "arguments", call.arguments())));
    }
    var message = new LinkedHashMap<String, Object>();
    message.put("role", "assistant");
    message.put("content", content == null || content.isBlank() ? null : content);
    message.put("tool_calls", list);
    return message;
  }

  private Map<String, Object> toolResult(LlmClient.ToolCall call, JsonNode result) {
    var message = new LinkedHashMap<String, Object>();
    message.put("role", "tool");
    message.put("tool_call_id", call.id());
    message.put("content", truncate(result.toString()));
    return message;
  }

  private String truncate(String value) {
    if (value == null) return "";
    return value.length() <= MAX_TOOL_RESULT_CHARS
        ? value
        : value.substring(0, MAX_TOOL_RESULT_CHARS) + "…（已截断）";
  }

  private ObjectNode toolPart(
      String partId, LlmClient.ToolCall call, String status, JsonNode result) {
    var part = json.createObjectNode();
    part.put("id", partId);
    part.put("type", "tool");
    var tool = part.putObject("tool");
    tool.put("id", call.id());
    tool.put("name", call.name());
    tool.put("status", status);
    try {
      tool.set("args", arguments(call.arguments()));
    } catch (ApiException ex) {
      tool.put("args", call.arguments());
    }
    if (result != null) tool.put("result", truncate(result.toString()));
    return part;
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

  private void emitTool(OutputStream output, String blockId, ObjectNode part) throws IOException {
    var event = json.createObjectNode();
    event.put("type", "tool");
    event.put("blockId", blockId);
    event.set("tool", part.path("tool"));
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

  private void addTextPart(
      com.fasterxml.jackson.databind.node.ArrayNode parts, String id, String content) {
    if (content == null || content.isEmpty()) return;
    var part = parts.addObject();
    part.put("id", id);
    part.put("type", "text");
    part.put("content", content);
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

  /** 兼容两种会话文件：`.jsonl` 每行一个条目，旧 `.json` 为单对象。 */
  private SessionState readSession(String projectId, String sessionFile) {
    if (sessionFile == null || !sessionFile.matches("[A-Za-z0-9._-]{1,128}"))
      throw ApiException.invalid();
    var snapshot =
        files.snapshot(projectId, SESSION_DIR + "/" + sessionFile, MAX_SESSION_BYTES, null);
    String text = snapshot.data().text();
    try {
      return sessionFile.endsWith(".jsonl")
          ? readJsonl(projectId, text, snapshot.data().revision())
          : readLegacy(projectId, text, snapshot.data().revision());
    } catch (IOException ex) {
      throw new ApiException(503, "SESSION_READ_FAILED", "会话文件读取失败");
    }
  }

  private SessionState readLegacy(String projectId, String text, String revision)
      throws IOException {
    JsonNode root = json.readTree(text);
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
        revision);
  }

  private SessionState readJsonl(String projectId, String text, String revision)
      throws IOException {
    String name = "新对话", modified = Instant.now().toString();
    String providerId = null, modelId = null, thinkingLevel = null;
    var messages = new ArrayList<AgentMessageRespDTO>();
    for (String line : text.split("\n")) {
      if (line.isBlank()) continue;
      JsonNode entry = json.readTree(line);
      String type = entry.path("type").asText();
      if (type.equals("session")) {
        name = entry.path("name").asText(name);
        providerId = entry.path("providerId").asText(null);
        modelId = entry.path("modelId").asText(null);
        thinkingLevel = entry.path("thinkingLevel").asText(null);
        modified = entry.path("updatedAt").asText(modified);
      } else if (type.equals("message")) {
        messages.add(json.treeToValue(entry.path("message"), AgentMessageRespDTO.class));
      }
    }
    return new SessionState(
        projectId, name, modified, providerId, modelId, thinkingLevel, messages, revision);
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
    String modified = Instant.now().toString();
    byte[] content;
    try {
      if (file.endsWith(".jsonl")) {
        var builder = new StringBuilder();
        var header = json.createObjectNode();
        header.put("type", "session");
        header.put("version", 1);
        header.put("file", file);
        header.put("name", name);
        header.put("providerId", providerId);
        header.put("modelId", modelId);
        header.put("thinkingLevel", thinkingLevel);
        header.put("updatedAt", modified);
        builder.append(json.writeValueAsString(header)).append('\n');
        for (var message : messages) {
          var entry = json.createObjectNode();
          entry.put("type", "message");
          entry.set("message", json.valueToTree(message));
          builder.append(json.writeValueAsString(entry)).append('\n');
        }
        content = builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      } else {
        var root = json.createObjectNode();
        root.put("file", file);
        root.put("name", name);
        root.put("modified", modified);
        if (providerId != null) root.put("providerId", providerId);
        if (modelId != null) root.put("modelId", modelId);
        if (thinkingLevel != null) root.put("thinkingLevel", thinkingLevel);
        root.set("messages", json.valueToTree(messages));
        content = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
      }
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
