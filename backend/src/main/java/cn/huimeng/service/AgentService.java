package cn.huimeng.service;

import cn.huimeng.dto.req.AgentAnswerReqDTO;
import cn.huimeng.dto.req.AgentCanvasResultReqDTO;
import cn.huimeng.dto.req.AgentGenerateReqDTO;
import cn.huimeng.dto.resp.AgentConversationRespDTO;
import cn.huimeng.dto.resp.AgentHistoryRespDTO;
import java.io.OutputStream;
import java.util.List;

/** 项目 Agent 会话与文本生成；仅项目成员可用，模型执行前重新校验批准状态。 */
public interface AgentService {
  AgentConversationRespDTO create(String projectId, String requestId);

  AgentConversationRespDTO get(String projectId, String sessionFile);

  List<AgentHistoryRespDTO> list(String projectId);

  AgentConversationRespDTO rename(
      String projectId, String sessionFile, String name, String requestId);

  AgentConversationRespDTO deleteMessage(
      String projectId, String sessionFile, String entryId, String requestId);

  /** 以 NDJSON 输出 userMessage/text/done/error 事件；输出关闭即取消上游调用。 */
  void generate(String projectId, AgentGenerateReqDTO input, OutputStream output, String requestId);

  /** 取消指定会话的当前生成；同时唤醒仍在等待页面的画布调用与提问。 */
  void cancel(String projectId, String sessionFile);

  /** 页面回答提问；callId 必须属于本项目正在等待的工具调用。 */
  void answer(String projectId, AgentAnswerReqDTO input);

  /** 页面回传画布操作结果；callId 必须属于本项目正在等待的工具调用。 */
  void canvasResult(String projectId, AgentCanvasResultReqDTO input);
}
