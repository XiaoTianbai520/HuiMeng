package cn.huimeng.controller;

import cn.huimeng.dto.req.GenerateImageReqDTO;
import cn.huimeng.dto.resp.RunRespDTO;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.MediaGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 仅精确媒体生成和本人运行查询/取消；其余创作路由继续默认拒绝。 */
@RestController
public class MediaGenerationController {
  private final MediaGenerationService generation;

  public MediaGenerationController(MediaGenerationService generation) {
    this.generation = generation;
  }

  @PostMapping("/api/v1/projects/{projectId}/creative/ai/media/generate")
  public ResponseEntity<?> generate(
      @PathVariable String projectId,
      @Valid @RequestBody GenerateImageReqDTO input,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest request) {
    var result = generation.generate(projectId, input, key, RequestContext.requestId(request));
    return ResponseEntity.status(result.replay() ? 202 : 200)
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Run-Id", result.run().id())
        .header("Location", "/api/v1/projects/" + projectId + "/runs/" + result.run().id())
        .body(result.replay() ? result.run() : result.stream());
  }

  @GetMapping("/api/v1/projects/{projectId}/runs/{runId}")
  public RunRespDTO get(@PathVariable String projectId, @PathVariable String runId) {
    return generation.get(projectId, runId);
  }

  @PostMapping("/api/v1/projects/{projectId}/runs/{runId}/cancel")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public RunRespDTO cancel(@PathVariable String projectId, @PathVariable String runId) {
    return generation.cancel(projectId, runId);
  }
}
