package cn.huimeng.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 协议分派：握手、工具目录与调用。控制器只负责 HTTP 状态与响应头，协议帧在服务层生成。
 *
 * <p>认证与工具名单已由外部边界过滤器校验；这里只把 JSON-RPC 请求转成 SSE 单帧或 notification 的 202。
 */
public interface McpProtocolService {

  /** 状态码 + 已序列化的 SSE 帧；notification 的 body 为空。 */
  record Reply(int status, String body, String sessionId) {}

  Reply handle(JsonNode body, String idempotencyKey, String requestId);
}
