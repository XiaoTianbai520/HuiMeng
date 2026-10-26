package cn.huimeng.dto.req;

/** 取消指定会话的当前生成。 */
public record AgentCancelReqDTO(String directory, String sessionFile) {}
