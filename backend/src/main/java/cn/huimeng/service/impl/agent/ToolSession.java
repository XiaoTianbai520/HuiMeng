package cn.huimeng.service.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * 一次 Agent 运行里工具执行能用到的最小上下文。
 *
 * <p>把「项目/会话、页面回传通道、画布是否激活」收敛在这里，工具提供方不必知道 SSE 输出与会话文件细节；将来引入子 Agent 或远程执行器时，只需要提供另一种实现，不用改工具实现本身。
 */
public interface ToolSession {
  String projectId();

  String sessionFile();

  boolean canvasEnabled();

  /** 发一条需要页面参与的事件并等待回传；超时、取消、页面断开都按失败抛出。 */
  JsonNode pageRequest(String callId, ObjectNode payload, long seconds) throws IOException;
}
