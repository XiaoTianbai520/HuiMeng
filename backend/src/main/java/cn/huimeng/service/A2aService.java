package cn.huimeng.service;

import com.fasterxml.jackson.databind.JsonNode;

/** A2A 团队任务：协议 1.0 的提交/查询/取消，任务绑定 client 与 project。 */
public interface A2aService {

  /** 状态码 + 已序列化的 JSON-RPC 响应。 */
  record Reply(int status, String body) {}

  /** JSON-RPC 分派：SendMessage/GetTask/CancelTask，含版本与归属错误。 */
  Reply rpc(String team, JsonNode body, String version, String requestId);

  /** Agent Card；baseUrl 为当前对外地址，不保留上游 Bun 地址。 */
  JsonNode card(String team, String baseUrl);

  /** SendMessage：创建任务并按 returnImmediately 决定是否等待终态。 */
  JsonNode send(String team, JsonNode params, String requestId);

  /** GetTask：归属不匹配时与不存在同样返回 TaskNotFound。 */
  JsonNode get(String team, String id);

  /** CancelTask：立即标记取消，并中断对应项目 Agent 运行。 */
  JsonNode cancel(String team, String id);
}
