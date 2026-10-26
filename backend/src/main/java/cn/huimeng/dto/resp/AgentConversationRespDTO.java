package cn.huimeng.dto.resp;

import java.util.List;

/** Agent 会话内容。 */
public record AgentConversationRespDTO(
    String file,
    String name,
    List<AgentMessageRespDTO> messages,
    String providerId,
    String modelId,
    String thinkingLevel,
    Boolean running) {}
