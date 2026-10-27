package cn.huimeng.service.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具提供方。
 *
 * <p>内置画布、提问、内部生图、市场工具各自实现本接口并注册为 Spring Bean；{@link ToolRegistry} 按顺序合并定义、按名字分派执行。 新增一类工具（例如未来的子
 * Agent 委派）只需要加一个实现，不需要改 Agent 主循环。
 */
public interface ToolProvider {

  /** 提供方标识，用于日志与重名诊断。 */
  String id();

  /** 本轮可下发的 OpenAI function 定义；画布未激活时由实现自行返回空列表。 */
  List<Map<String, Object>> definitions(ToolSession session);

  boolean owns(String name);

  JsonNode invoke(String name, String callId, JsonNode args, ToolSession session)
      throws IOException;
}
