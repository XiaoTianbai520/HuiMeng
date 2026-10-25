package cn.huimeng.controller;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.FileWriteReqDTO;
import cn.huimeng.dto.resp.WriteResultRespDTO;
import cn.huimeng.service.ProjectFileService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 仅开放冻结的精确 read 路由；快照和原字节下载复用同一成员授权链。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/creative/workspaces/files")
public class ProjectFileController {
  private final ProjectFileService files;

  public ProjectFileController(ProjectFileService files) {
    this.files = files;
  }

  /** 返回快照或原字节；客户端不提供物理目录、下载令牌或自报身份。 */
  @GetMapping("/read")
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

  @PutMapping(value = "/write", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public WriteResultRespDTO write(
      @PathVariable String projectId,
      @RequestParam String path,
      @RequestParam(required = false) String expectedRevision,
      @RequestParam(defaultValue = "false") boolean exclusive,
      @RequestParam(required = false) String encoding,
      HttpServletRequest request)
      throws java.io.IOException {
    if (!Set.of("path", "expectedRevision", "exclusive", "encoding")
            .containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1))
      throw ApiException.invalid();
    return files.write(
        projectId,
        new FileWriteReqDTO(
            path, expectedRevision, exclusive, encoding, request.getContentLengthLong()),
        request.getInputStream(),
        (String) request.getAttribute("requestId"));
  }
}
