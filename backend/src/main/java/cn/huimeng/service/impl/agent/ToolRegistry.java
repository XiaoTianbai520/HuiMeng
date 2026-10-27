package cn.huimeng.service.impl.agent;

import cn.huimeng.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 工具注册表：合并各提供方的定义并按名字分派。
 *
 * <p>顺序由提供方的 {@code @Order} 决定（画布 → 提问 → 生图 → 市场）。重名时先注册者优先并记录日志，避免两个来源同时声称同一个工具名。
 * 当前只有内置提供方与市场工具；将来接入子 Agent 或远程执行器时新增一个提供方即可，主循环与协议不变。
 */
@Component
public class ToolRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  private final List<ToolProvider> providers;

  public ToolRegistry(List<ToolProvider> providers) {
    this.providers = List.copyOf(providers);
  }

  /** 本轮下发的工具定义；重名只保留第一个。 */
  public List<Map<String, Object>> definitions(ToolSession session) {
    var result = new ArrayList<Map<String, Object>>();
    var taken = new HashSet<String>();
    for (ToolProvider provider : providers) {
      for (Map<String, Object> definition : provider.definitions(session)) {
        String name = nameOf(definition);
        if (!taken.add(name)) {
          LOG.warn("工具名重复，已跳过：{}（来源 {}）", name, provider.id());
          continue;
        }
        result.add(definition);
      }
    }
    return result;
  }

  /** 找到第一个声称拥有该工具的提供方并执行；没有则按未知工具拒绝。 */
  public JsonNode invoke(String name, String callId, JsonNode args, ToolSession session)
      throws IOException {
    for (ToolProvider provider : providers)
      if (provider.owns(name)) return provider.invoke(name, callId, args, session);
    throw new ApiException(400, "TOOL_UNKNOWN", "未接通该工具：" + name);
  }

  public List<String> providerIds() {
    return providers.stream().map(ToolProvider::id).toList();
  }

  private static String nameOf(Map<String, Object> definition) {
    Object function = definition.get("function");
    if (function instanceof Map<?, ?> map && map.get("name") != null)
      return String.valueOf(map.get("name"));
    return String.valueOf(definition.get("name"));
  }
}
