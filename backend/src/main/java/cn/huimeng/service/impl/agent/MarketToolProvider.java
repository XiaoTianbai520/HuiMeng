package cn.huimeng.service.impl.agent;

import cn.huimeng.service.impl.ToolRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 已安装市场工具：定义与执行都走隔离的 JS 执行器，本类只做注册表适配。 */
@Component
@Order(30)
public class MarketToolProvider implements ToolProvider {
  private final ToolRuntime runtime;
  private final ObjectMapper json;

  public MarketToolProvider(ToolRuntime runtime, ObjectMapper json) {
    this.runtime = runtime;
    this.json = json;
  }

  @Override
  public String id() {
    return "market";
  }

  @Override
  public List<Map<String, Object>> definitions(ToolSession session) {
    return runtime.definitions();
  }

  @Override
  public boolean owns(String name) {
    return runtime.owns(name);
  }

  @Override
  public JsonNode invoke(String name, String callId, JsonNode args, ToolSession session) {
    JsonNode result = runtime.execute(name, callId, args);
    return result == null ? json.createObjectNode() : result;
  }
}
