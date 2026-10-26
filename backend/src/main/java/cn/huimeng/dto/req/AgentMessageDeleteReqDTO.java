package cn.huimeng.dto.req;

/** 删除会话中的指定消息。 */
public record AgentMessageDeleteReqDTO(String directory, String sessionFile, String entryId) {}
