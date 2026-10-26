package cn.huimeng.dto.resp;

import com.fasterxml.jackson.databind.JsonNode;

/** Agent 会话消息；parts/attachments/mentions 为可选结构。 */
public record AgentMessageRespDTO(
    String id,
    String entryId,
    String replyTo,
    String role,
    String content,
    JsonNode parts,
    JsonNode attachments,
    JsonNode mentions,
    String error) {}
