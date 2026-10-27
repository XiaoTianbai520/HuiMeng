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

/** 画布操作：定义来自固定资源，执行始终转交页面校验并回传。 */
@Component
@Order(0)
public class CanvasToolProvider implements ToolProvider {
  private static final long TIMEOUT_SECONDS = 120;

  private final AgentToolCatalog catalog;
  private final ObjectMapper json;

  public CanvasToolProvider(AgentToolCatalog catalog, ObjectMapper json) {
    this.catalog = catalog;
    this.json = json;
  }

  @Override
  public String id() {
    return "canvas";
  }

  @Override
  public List<Map<String, Object>> definitions(ToolSession session) {
    return session.canvasEnabled() ? catalog.canvasDefinitions() : List.of();
  }

  @Override
  public boolean owns(String name) {
    return catalog.isCanvasTool(name);
  }

  @Override
  public JsonNode invoke(String name, String callId, JsonNode args, ToolSession session)
      throws IOException {
    if (!session.canvasEnabled()) throw new ApiException(400, "CANVAS_UNAVAILABLE", "当前页面没有激活的画布");
    String pageCallId = UUID.randomUUID().toString();
    ObjectNode payload = json.createObjectNode();
    payload.put("type", "canvasCall");
    payload.put("callId", pageCallId);
    payload.put("name", name);
    payload.set("args", args);
    JsonNode result = session.pageRequest(pageCallId, payload, TIMEOUT_SECONDS);
    if (result.path("cancelled").asBoolean())
      throw new ApiException(400, "CANVAS_CANCELLED", "画布操作已取消");
    if (!result.path("ok").asBoolean())
      throw new ApiException(400, "CANVAS_FAILED", result.path("error").asText("画布操作失败"));
    return result.path("result");
  }
}
