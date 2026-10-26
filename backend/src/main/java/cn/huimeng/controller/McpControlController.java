package cn.huimeng.controller;

import cn.huimeng.dto.resp.ApiRespDTO;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 项目页面的 MCP 控制通道：页面主动连接并上报状态，外部工具调用经此转交页面执行。
 *
 * <p>连接、状态与结果都绑定当前会话与路径中的项目，不接受请求体自报身份；同一项目只保留最近一条连接。
 */
@RestController
public class McpControlController {
  private final McpControlService control;
  private final ProjectService projects;

  public McpControlController(McpControlService control, ProjectService projects) {
    this.control = control;
    this.projects = projects;
  }

  @GetMapping(
      path = "/api/v1/projects/{projectId}/creative/mcp/control/events",
      produces = "text/event-stream")
  public SseEmitter events(@PathVariable String projectId, @RequestParam String connectionId) {
    projects.requireMember(projectId, false);
    return control.connect(projectId, connectionId);
  }

  @PostMapping("/api/v1/projects/{projectId}/creative/mcp/control/state")
  public ApiRespDTO<Void> state(@PathVariable String projectId, @RequestBody StateReq input) {
    projects.requireMember(projectId, false);
    control.state(projectId, input.connectionId(), input.revision(), input.state());
    return ApiRespDTO.ok(null);
  }

  @PostMapping("/api/v1/projects/{projectId}/creative/mcp/control/result")
  public ApiRespDTO<Void> result(@PathVariable String projectId, @RequestBody ResultReq input) {
    projects.requireMember(projectId, false);
    control.result(projectId, input.connectionId(), input.callId(), input.result(), input.error());
    return ApiRespDTO.ok(null);
  }

  public record StateReq(String connectionId, Integer revision, JsonNode state) {}

  public record ResultReq(String connectionId, String callId, JsonNode result, String error) {}
}
