package cn.huimeng.service.impl.agent;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.service.impl.AgentToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 提问：把结构化问题交给页面，用户回答或取消后回到工具循环。 */
@Component
@Order(10)
public class AskToolProvider implements ToolProvider {
  private static final long TIMEOUT_SECONDS = 300;

  private final AgentToolCatalog catalog;
  private final ObjectMapper json;

  public AskToolProvider(AgentToolCatalog catalog, ObjectMapper json) {
    this.catalog = catalog;
    this.json = json;
  }

  @Override
  public String id() {
    return "ask";
  }

  @Override
  public List<Map<String, Object>> definitions(ToolSession session) {
    return catalog.definition(AgentToolCatalog.ASK);
  }

  @Override
  public boolean owns(String name) {
    return AgentToolCatalog.ASK.equals(name);
  }

  @Override
  public JsonNode invoke(String name, String callId, JsonNode args, ToolSession session)
      throws IOException {
    String pageCallId = UUID.randomUUID().toString();
    ObjectNode payload = json.createObjectNode();
    payload.put("type", "question");
    payload.put("callId", pageCallId);
    payload.put("toolCallId", callId);
    payload.put("title", args.path("title").asText("需要确认"));
    payload.put("question", args.path("question").asText(""));
    if (args.has("options")) payload.set("options", args.path("options"));
    if (args.has("fields")) payload.set("fields", args.path("fields"));
    JsonNode result = session.pageRequest(pageCallId, payload, TIMEOUT_SECONDS);
    if (result.path("cancelled").asBoolean() || !result.path("ok").asBoolean())
      throw new ApiException(400, "QUESTION_CANCELLED", "用户取消了本次提问");
    return result;
  }
}
