package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.security.ExternalActor;
import cn.huimeng.security.ExternalCallContext;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** MCP JSON-RPC 分派；协议错误保留本次关联 id，工具业务错误按 isError 结果返回。 */
@Service
public class McpProtocolServiceImpl implements McpProtocolService {
  private static final String PROTOCOL_VERSION = "2025-06-18";
  private final McpToolService tools;
  private final ObjectMapper json;

  public McpProtocolServiceImpl(McpToolService tools, ObjectMapper json) {
    this.tools = tools;
    this.json = json;
  }

  @Override
  public Reply handle(JsonNode body, String idempotencyKey, String requestId) {
    if (body == null
        || !body.isObject()
        || !"2.0".equals(body.path("jsonrpc").asText(""))
        || body.path("method").asText("").isBlank())
      return reply(400, error(null, -32600, "Invalid Request", null));
    JsonNode id = body.get("id");
    if (id == null || id.isMissingNode()) return new Reply(202, "", null);
    String method = body.path("method").asText();
    try {
      return switch (method) {
        case "initialize" -> reply(200, success(id, initialize()), UUID.randomUUID().toString());
        case "ping" -> reply(200, success(id, json.createObjectNode()), null);
        case "tools/list" -> reply(200, success(id, toolsList()), null);
        case "tools/call" -> toolCall(id, body.path("params"), idempotencyKey, requestId);
        case "resources/list" -> reply(
            200,
            success(id, json.createObjectNode().set("resources", json.createArrayNode())),
            null);
        case "prompts/list" -> reply(
            200, success(id, json.createObjectNode().set("prompts", json.createArrayNode())), null);
        default -> reply(200, error(id, -32601, "Method not found", null));
      };
    } catch (ApiException ex) {
      if (ex.status == 401 || ex.status == 403 || ex.status == 429)
        return reply(ex.status, error(id, -32099, ex.getMessage(), ex.code));
      return reply(200, error(id, -32603, ex.getMessage(), ex.code));
    }
  }

  private ObjectNode initialize() {
    ObjectNode result = json.createObjectNode();
    result.put("protocolVersion", PROTOCOL_VERSION);
    ObjectNode capabilities = result.putObject("capabilities");
    capabilities.putObject("tools").put("listChanged", false);
    capabilities.putObject("resources").put("subscribe", false).put("listChanged", false);
    result.putObject("serverInfo").put("name", "huimeng").put("version", "1.0.0");
    result.put(
        "instructions",
        "工具限定在凭证绑定的单个项目内。先用 getAppState 读取页面连接与画布；画布与文档操作由同项目页面执行，页面未连接时明确失败。生成类工具返回运行 ID，用 getRun 查询、cancelRun 取消。");
    return result;
  }

  private ObjectNode toolsList() {
    ObjectNode result = json.createObjectNode();
    result.set("tools", json.valueToTree(tools.tools(actor())));
    return result;
  }

  private Reply toolCall(JsonNode id, JsonNode params, String idempotencyKey, String requestId) {
    String name = params.path("name").asText("");
    if (name.isBlank()) return reply(200, toolError(id, "缺少工具名称"));
    JsonNode args = params.path("arguments");
    JsonNode value;
    try {
      value =
          tools.call(
              actor(),
              name,
              args.isMissingNode() ? json.createObjectNode() : args,
              requestId,
              idempotencyKey);
    } catch (ApiException ex) {
      if (ex.status == 401 || ex.status == 403 || ex.status == 429)
        return reply(ex.status, error(id, -32099, ex.getMessage(), ex.code));
      return reply(200, toolError(id, ex.getMessage()));
    }
    ObjectNode result = json.createObjectNode();
    result
        .putArray("content")
        .addObject()
        .put("type", "text")
        .put("text", value == null ? "null" : value.toString());
    result.set("structuredContent", value == null ? json.createObjectNode() : value);
    result.put("isError", false);
    return reply(200, success(id, result), null);
  }

  private String toolError(JsonNode id, String message) {
    ObjectNode result = json.createObjectNode();
    result
        .putArray("content")
        .addObject()
        .put("type", "text")
        .put("text", message == null ? "工具执行失败" : message);
    result.put("isError", true);
    return success(id, result);
  }

  private ExternalActor actor() {
    ExternalActor actor = ExternalCallContext.current();
    if (actor == null) throw new ApiException(401, "CLIENT_TOKEN_INVALID", "凭证无效");
    return actor;
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

  private Reply reply(int status, String payload) {
    return reply(status, payload, null);
  }

  private Reply reply(int status, String payload, String sessionId) {
    return new Reply(status, "data: " + payload + "\n\n", sessionId);
  }
}
