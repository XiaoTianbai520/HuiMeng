package cn.huimeng.dto.resp;

/** 会话历史条目。 */
public record AgentHistoryRespDTO(String file, String name, String modified, int messageCount) {}
