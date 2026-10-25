package cn.huimeng.controller;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.service.ProjectFileService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 仅开放冻结的精确 read 路由；快照和原字节下载复用同一成员授权链。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/creative/workspaces/files/read")
public class ProjectFileController {
  private final ProjectFileService files;

  public ProjectFileController(ProjectFileService files) {
    this.files = files;
  }

  /** 返回快照或原字节；客户端不提供物理目录、下载令牌或自报身份。 */
  @GetMapping
  public ResponseEntity<?> read(
      @PathVariable String projectId,
      @RequestParam String path,
      @RequestParam(defaultValue = "false") boolean snapshot,
      @RequestParam(required = false) Long maxBytes,
      @RequestHeader(value = "Range", required = false) String range,
      HttpServletRequest request) {
    if (!Set.of("path", "snapshot", "maxBytes").containsAll(request.getParameterMap().keySet()))
      throw ApiException.invalid();
    if (snapshot)
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(files.snapshot(projectId, path, maxBytes, range));
    var result = files.download(projectId, path, maxBytes, range);
    var response =
        ResponseEntity.status(result.status())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(result.length())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(result.filename(), StandardCharsets.UTF_8)
                    .build()
                    .toString());
    if (result.contentRange() != null)
      response.header(HttpHeaders.CONTENT_RANGE, result.contentRange());
    return response.body(new InputStreamResource(result.content()));
  }
}
