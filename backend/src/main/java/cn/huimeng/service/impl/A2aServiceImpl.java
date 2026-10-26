package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.A2aTaskDO;
import cn.huimeng.dao.mapper.A2aTaskMapper;
import cn.huimeng.dto.req.AgentGenerateReqDTO;
import cn.huimeng.dto.resp.ModelRespDTO;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * A2A 任务执行：每个任务在绑定项目内跑一次项目 Agent，文本结果作为 artifact 返回。
 *
 * <p>任务归属同时绑定 client 与 project，查询/取消不因知道 ID 而越权；取消只中断本任务的会话，不影响同项目其他运行。
 */
@Service
public class A2aServiceImpl implements A2aService {
  private static final int MAX_PROMPT = 32000;
  private static final long WAIT_SECONDS = 110;
  private static final Set<String> TERMINAL =
      Set.of(
          "TASK_STATE_COMPLETED",
          "TASK_STATE_CANCELED",
          "TASK_STATE_FAILED",
          "TASK_STATE_REJECTED");
  private final A2aTaskMapper tasks;
  private final AgentService agents;
  private final ConfigurationService configuration;
  private final ObjectMapper json;
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "a2a-worker");
            thread.setDaemon(true);
            return thread;
          });

  public A2aServiceImpl(
      A2aTaskMapper tasks,
      AgentService agents,
      ConfigurationService configuration,
      ObjectMapper json) {
    this.tasks = tasks;
    this.agents = agents;
    this.configuration = configuration;
    this.json = json;
  }

  @Override
  public Reply rpc(String team, JsonNode body, String version, String requestId) {
    if (!"1.0".equals(version))
      return new Reply(400, error(null, -32600, "VERSION_NOT_SUPPORTED", "A2A-Version"));
    if (body == null
        || !body.isObject()
        || !"2.0".equals(body.path("jsonrpc").asText(""))
        || body.path("method").asText("").isBlank())
      return new Reply(400, error(null, -32600, "Invalid Request", null));
    JsonNode id = body.get("id");
    if (id == null || id.isMissingNode()) return new Reply(202, "");
    String method = body.path("method").asText();
    JsonNode params = body.path("params");
    try {
      JsonNode result =
          switch (method) {
            case "SendMessage" -> send(team, params, requestId);
            case "GetTask" -> get(team, taskId(params));
            case "CancelTask" -> cancel(team, taskId(params));
            default -> null;
          };
      if (result == null) return new Reply(200, error(id, -32601, "Method not found", null));
      return new Reply(200, success(id, result));
    } catch (ApiException ex) {
      if (ex.status == 404)
        return new Reply(200, error(id, -32001, "TaskNotFound", "TASK_NOT_FOUND"));
      if (ex.status == 401 || ex.status == 403 || ex.status == 429)
        return new Reply(ex.status, error(id, -32099, ex.getMessage(), ex.code));
      return new Reply(200, error(id, -32602, ex.getMessage(), ex.code));
    }
  }

  private static String taskId(JsonNode params) {
    String id = params.path("id").asText("");
    if (id.isBlank()) throw new ApiException(400, "INVALID_ARGUMENT", "缺少任务 ID");
    return id;
  }

  private String success(JsonNode id, JsonNode result) {
    ObjectNode payload = json.createObjectNode();
    payload.put("jsonrpc", "2.0");
    payload.set("id", id);
    payload.set("result", result);
    return payload.toString();
  }

  private String error(JsonNode id, int code, String message, String dataCode) {
    ObjectNode payload = json.createObjectNode();
    payload.put("jsonrpc", "2.0");
    if (id == null) payload.putNull("id");
    else payload.set("id", id);
    ObjectNode error = payload.putObject("error");
    error.put("code", code);
    error.put("message", message == null ? "请求未获执行" : message);
    if (dataCode != null) error.putObject("data").put("code", dataCode);
    return payload.toString();
  }

  @Override
  public JsonNode card(String team, String baseUrl) {
    ObjectNode card = json.createObjectNode();
    card.put("name", team);
    card.put("description", "绘梦绘画创作团队：在绑定项目内执行创作指令，结果以文本返回。");
    var interfaces = card.putArray("supportedInterfaces");
    interfaces
        .addObject()
        .put("url", baseUrl.replaceAll("/+$", "") + "/a2a/" + team)
        .put("protocolBinding", "JSONRPC")
        .put("protocolVersion", "1.0");
    card.put("version", "1.0.0");
    ObjectNode capabilities = card.putObject("capabilities");
    capabilities.put("streaming", false);
    capabilities.put("pushNotifications", false);
    capabilities.put("extendedAgentCard", false);
    card.putObject("securitySchemes")
        .putObject("bearer")
        .putObject("httpAuthSecurityScheme")
        .put("scheme", "bearer");
    card.putArray("securityRequirements").addObject().putObject("schemes").putObject("bearer");
    card.putArray("defaultInputModes").add("text/plain");
    card.putArray("defaultOutputModes").add("text/plain").add("application/json");
    var skills = card.putArray("skills");
    skills
        .addObject()
        .put("id", team)
        .put("name", team)
        .put("description", "在绑定项目内执行一次创作任务并返回文本结果")
        .putArray("tags")
        .add("toonflow")
        .add("team");
    return card;
  }

  @Override
  public JsonNode send(String team, JsonNode params, String requestId) {
    ExternalActor actor = ExternalCallContext.current();
    if (actor == null) throw new ApiException(401, "CLIENT_TOKEN_INVALID", "凭证无效");
    String prompt = text(params.path("message"));
    if (prompt.isBlank()) throw new ApiException(400, "INVALID_ARGUMENT", "message.parts 缺少文本");
    if (prompt.length() > MAX_PROMPT) throw new ApiException(400, "INVALID_ARGUMENT", "任务文本过长");
    String parentContext = params.path("message").path("contextId").asText("");
    String contextId =
        parentContext.matches("[A-Za-z0-9-]{1,64}") ? parentContext : UUID.randomUUID().toString();
    Instant now = Instant.now();
    var task =
        new A2aTaskDO(
            UUID.randomUUID().toString(),
            actor.projectId(),
            actor.clientId(),
            team,
            contextId,
            null,
            "TASK_STATE_SUBMITTED",
            prompt,
            null,
            null,
            now,
            now);
    tasks.insert(task);
    workers.execute(() -> run(task, requestId));
    boolean immediate = params.path("configuration").path("returnImmediately").asBoolean(false);
    if (!immediate) {
      long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000;
      while (System.currentTimeMillis() < deadline) {
        var current = tasks.find(actor.clientId(), actor.projectId(), task.id());
        if (current != null && TERMINAL.contains(current.state())) return toJson(current);
        try {
          Thread.sleep(500);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    return toJson(tasks.find(actor.clientId(), actor.projectId(), task.id()));
  }

  @Override
  public JsonNode get(String team, String id) {
    ExternalActor actor = ExternalCallContext.current();
    var task = tasks.find(actor.clientId(), actor.projectId(), id);
    if (task == null || !task.team().equals(team))
      throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在");
    return toJson(task);
  }

  @Override
  public JsonNode cancel(String team, String id) {
    ExternalActor actor = ExternalCallContext.current();
    var task = tasks.find(actor.clientId(), actor.projectId(), id);
    if (task == null || !task.team().equals(team))
      throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在");
    if (TERMINAL.contains(task.state())) return toJson(task);
    cancelled.add(task.id());
    if (task.sessionFile() != null) {
      try {
        agents.cancel(task.projectId(), task.sessionFile());
      } catch (RuntimeException ignored) {
        // 会话已结束时取消由任务状态兜底。
      }
    }
    tasks.state(task.id(), "TASK_STATE_CANCELED", null, null);
    return toJson(tasks.find(actor.clientId(), actor.projectId(), task.id()));
  }

  /** 后台执行：只使用本任务绑定的项目与客户端身份，完成后写终态。 */
  private void run(A2aTaskDO task, String requestId) {
    ExternalCallContext.bind(new ExternalActor(task.clientId(), task.projectId()));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new Actor(task.clientId(), "external", "EXTERNAL", "ACTIVE", 0), null, List.of()));
    try {
      if (cancelled.contains(task.id())) {
        tasks.state(task.id(), "TASK_STATE_CANCELED", null, null);
        return;
      }
      ModelRespDTO model = textModel();
      if (model == null) {
        tasks.state(task.id(), "TASK_STATE_REJECTED", null, "团队尚未启用文本模型");
        return;
      }
      String session = agents.create(task.projectId(), requestId).file();
      tasks.session(task.id(), session);
      if (cancelled.contains(task.id())) {
        agents.cancel(task.projectId(), session);
        tasks.state(task.id(), "TASK_STATE_CANCELED", null, null);
        return;
      }
      tasks.state(task.id(), "TASK_STATE_WORKING", null, null);
      var output = new ByteArrayOutputStream();
      agents.generate(
          task.projectId(),
          new AgentGenerateReqDTO(
              task.projectId(),
              task.prompt(),
              model.providerId(),
              model.modelId(),
              null,
              session,
              null,
              null,
              null,
              null),
          output,
          requestId);
      String text = collect(output.toString(StandardCharsets.UTF_8));
      if (cancelled.contains(task.id())) {
        tasks.state(task.id(), "TASK_STATE_CANCELED", null, null);
      } else if (text == null) {
        tasks.state(task.id(), "TASK_STATE_FAILED", null, "任务未返回文本结果");
      } else {
        tasks.state(task.id(), "TASK_STATE_COMPLETED", text, null);
      }
    } catch (ApiException ex) {
      tasks.state(
          task.id(),
          cancelled.contains(task.id()) ? "TASK_STATE_CANCELED" : "TASK_STATE_FAILED",
          null,
          ex.getMessage());
    } catch (RuntimeException ex) {
      tasks.state(task.id(), "TASK_STATE_FAILED", null, "任务执行失败");
    } finally {
      cancelled.remove(task.id());
      ExternalCallContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  /** 汇总 NDJSON 文本增量；出现 error 事件即视为失败。 */
  private String collect(String ndjson) {
    StringBuilder text = new StringBuilder();
    for (String line : ndjson.split("\n")) {
      if (line.isBlank()) continue;
      try {
        JsonNode event = json.readTree(line);
        if ("text".equals(event.path("type").asText()) && event.hasNonNull("delta"))
          text.append(event.path("delta").asText());
        if ("error".equals(event.path("type").asText())) return null;
      } catch (Exception ignored) {
        // 非 JSON 行不属于协议事件。
      }
    }
    return text.toString();
  }

  private ModelRespDTO textModel() {
    for (ModelRespDTO model : configuration.models()) {
      if (!model.enabled()) continue;
      var capabilities = model.capabilities();
      if (capabilities != null
          && (capabilities.toString().contains("\"text\"")
              || capabilities.toString().contains("\"agent\""))) return model;
    }
    return null;
  }

  private static String text(JsonNode message) {
    StringBuilder builder = new StringBuilder();
    for (JsonNode part : message.path("parts")) {
      if (part.hasNonNull("text")) builder.append(part.path("text").asText());
    }
    return builder.toString().trim();
  }

  private ObjectNode toJson(A2aTaskDO task) {
    ObjectNode root = json.createObjectNode();
    root.put("id", task.id());
    root.put("contextId", task.contextId());
    ObjectNode status = root.putObject("status");
    status.put("state", task.state());
    status.put("timestamp", task.updatedAt().toString());
    if (task.error() != null) status.set("message", message(task, "ROLE_AGENT", task.error()));
    else if ("TASK_STATE_COMPLETED".equals(task.state()) && task.resultText() != null)
      status.set("message", message(task, "ROLE_AGENT", task.resultText()));
    if ("TASK_STATE_COMPLETED".equals(task.state()) && task.resultText() != null) {
      root.putArray("artifacts")
          .addObject()
          .put("artifactId", task.id())
          .put("name", "团队结果")
          .putArray("parts")
          .addObject()
          .put("text", task.resultText())
          .put("mediaType", "text/plain");
    }
    var history = root.putArray("history");
    history.add(message(task, "ROLE_USER", task.prompt()));
    if (task.resultText() != null) history.add(message(task, "ROLE_AGENT", task.resultText()));
    return root;
  }

  private ObjectNode message(A2aTaskDO task, String role, String text) {
    ObjectNode message = json.createObjectNode();
    message.put("messageId", UUID.randomUUID().toString());
    message.put("contextId", task.contextId());
    message.put("taskId", task.id());
    message.put("role", role);
    message.putArray("parts").addObject().put("text", text).put("mediaType", "text/plain");
    return message;
  }
}
