package cn.huimeng.service;

import cn.huimeng.security.ExternalActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具目录与执行。目录来自服务端固定定义，浏览器不能注入；每次调用都限定在凭证绑定的项目内。
 *
 * <p>页面类工具经 {@link McpControlService} 转交同项目已连接页面执行；服务端工具复用既有工作区/媒体服务，不新增旁路。
 */
public interface McpToolService {

  /** 已授权给该客户端的工具列表；未授权的工具不出现在 tools/list。 */
  List<Map<String, Object>> tools(ExternalActor actor);

  /** 工具总数，用于状态接口。 */
  int totalTools();

  /** 执行一次工具调用；args 缺省按空对象处理。 */
  JsonNode call(
      ExternalActor actor, String name, JsonNode args, String requestId, String idempotencyKey);
}
