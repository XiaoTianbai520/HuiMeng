package cn.huimeng.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 项目页面与 MCP 工具之间的控制桥：外部调用只经同项目已连接页面执行，不直接改画布文件。 */
public interface McpControlService {

  /** 页面建立控制连接；同一项目新连接会替换旧连接。 */
  SseEmitter connect(String projectId, String connectionId);

  /** 页面上报当前工作区状态；revision 用于丢弃乱序旧状态。 */
  void state(String projectId, String connectionId, Integer revision, JsonNode state);

  /** 页面回传调用结果；未知 callId 按已结束处理（404）。 */
  void result(String projectId, String connectionId, String callId, JsonNode result, String error);

  /** 外部工具调用页面执行；没有页面连接或超时都返回明确错误。 */
  JsonNode call(String projectId, String name, JsonNode args, long timeoutSeconds);

  /** 最近一次上报的页面状态；没有连接时返回 null。 */
  JsonNode state(String projectId);

  /** 当前已连接页面数，用于状态接口。 */
  int connections();
}
