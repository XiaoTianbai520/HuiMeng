package cn.huimeng.controller;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.FfmpegExecuteReqDTO;
import cn.huimeng.dto.resp.ApiRespDTO;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.FfmpegService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 受控转码入口：prepare/cancel 返回 JSON，执行返回 SSE 事件流。 */
@RestController
public class FfmpegController {
  private final FfmpegService ffmpeg;

  public FfmpegController(FfmpegService ffmpeg) {
    this.ffmpeg = ffmpeg;
  }

  @GetMapping("/api/v1/capabilities/ffmpeg/status")
  public ApiRespDTO<Map<String, Object>> status() {
    // 只读状态：不解释任何查询参数，页面可用它判断本机是否具备转码能力。
    return ApiRespDTO.ok(ffmpeg.status());
  }

  @PostMapping("/api/v1/projects/{projectId}/creative/ffmpeg/execute")
  public ResponseEntity<StreamingResponseBody> execute(
      @PathVariable String projectId,
      @Valid @RequestBody FfmpegExecuteReqDTO input,
      HttpServletRequest request) {
    noQuery(request);
    if (input.directory() != null && !input.directory().equals(projectId))
      throw ApiException.invalid();
    String requestId = RequestContext.requestId(request);
    String method = input.operation().method();
    if (method.equals("prepare") || method.equals("cancel")) {
      ffmpeg.execute(projectId, input, requestId, OutputStream.nullOutputStream());
      StreamingResponseBody jsonBody =
          output ->
              output.write(
                  "{\"code\":200,\"data\":null,\"message\":null}".getBytes(StandardCharsets.UTF_8));
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(jsonBody);
    }
    ffmpeg.validate(projectId, input);
    StreamingResponseBody stream = output -> ffmpeg.execute(projectId, input, requestId, output);
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
  }

  private static void noQuery(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw ApiException.invalid();
  }
}
