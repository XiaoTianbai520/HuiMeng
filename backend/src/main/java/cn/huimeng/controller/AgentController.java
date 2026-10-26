package cn.huimeng.controller;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.AgentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 项目 Agent 会话与文本生成；成员权限和模型批准在服务层重新校验。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/creative/agent")
public class AgentController {
  private final AgentService agents;

  public AgentController(AgentService agents) {
    this.agents = agents;
  }

  @PostMapping(produces = "application/x-ndjson")
  public StreamingResponseBody generate(
      @PathVariable String projectId,
      @RequestBody AgentGenerateReqDTO input,
      HttpServletRequest request) {
    noQuery(request);
    String requestId = RequestContext.requestId(request);
    return output -> agents.generate(projectId, input, output, requestId);
  }

  @PostMapping("/cancel")
  public ApiRespDTO<Void> cancel(
      @PathVariable String projectId,
      @RequestBody AgentCancelReqDTO input,
      HttpServletRequest request) {
    noQuery(request);
    requireSameProject(projectId, input.directory());
    agents.cancel(projectId, input.sessionFile());
    return ApiRespDTO.ok(null);
  }

  @PostMapping("/create")
  public ApiRespDTO<AgentConversationRespDTO> create(
      @PathVariable String projectId,
      @RequestBody AgentCreateReqDTO input,
      HttpServletRequest request) {
    noQuery(request);
    requireSameProject(projectId, input.directory());
    return ApiRespDTO.ok(agents.create(projectId, RequestContext.requestId(request)));
  }

  @GetMapping("/get")
  public ApiRespDTO<AgentConversationRespDTO> get(
      @PathVariable String projectId,
      @RequestParam String sessionFile,
      HttpServletRequest request) {
    onlyParams(request, "sessionFile");
    return ApiRespDTO.ok(agents.get(projectId, sessionFile));
  }

  @GetMapping("/list")
  public ApiRespDTO<List<AgentHistoryRespDTO>> list(
      @PathVariable String projectId, HttpServletRequest request) {
    noQuery(request);
    return ApiRespDTO.ok(agents.list(projectId));
  }

  @PatchMapping("/rename")
  public ApiRespDTO<AgentConversationRespDTO> rename(
      @PathVariable String projectId,
      @RequestBody AgentRenameReqDTO input,
      HttpServletRequest request) {
    noQuery(request);
    requireSameProject(projectId, input.directory());
    return ApiRespDTO.ok(
        agents.rename(
            projectId, input.sessionFile(), input.name(), RequestContext.requestId(request)));
  }

  @DeleteMapping("/message")
  public ApiRespDTO<AgentConversationRespDTO> deleteMessage(
      @PathVariable String projectId,
      @RequestBody AgentMessageDeleteReqDTO input,
      HttpServletRequest request) {
    noQuery(request);
    requireSameProject(projectId, input.directory());
    return ApiRespDTO.ok(
        agents.deleteMessage(
            projectId, input.sessionFile(), input.entryId(), RequestContext.requestId(request)));
  }

  private static void noQuery(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw ApiException.invalid();
  }

  private static void onlyParams(HttpServletRequest request, String... allowed) {
    if (!Set.of(allowed).containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1))
      throw ApiException.invalid();
  }

  private static void requireSameProject(String projectId, String directory) {
    if (directory != null && !directory.equals(projectId)) throw ApiException.invalid();
  }
}
