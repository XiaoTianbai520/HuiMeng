package cn.huimeng.controller;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.ProjectMediaService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 精确媒体入口；上传只接受原字节和显示文件名，不接受桶、键、路径或远程 URL。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/media")
public class ProjectMediaController {
  private final ProjectMediaService media;

  public ProjectMediaController(ProjectMediaService media) {
    this.media = media;
  }

  @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectMediaRespDTO upload(
      @PathVariable String projectId, @RequestParam String filename, HttpServletRequest request)
      throws IOException {
    if (!request.getParameterMap().keySet().equals(Set.of("filename")))
      throw ApiException.invalid();
    return media.upload(
        projectId,
        filename,
        request.getContentLengthLong(),
        request.getInputStream(),
        RequestContext.requestId(request));
  }

  @GetMapping
  public PageRespDTO<ProjectMediaRespDTO> list(
      @PathVariable String projectId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit,
      HttpServletRequest request) {
    if (!Set.of("cursor", "limit").containsAll(request.getParameterMap().keySet()))
      throw ApiException.invalid();
    return media.list(projectId, cursor, limit);
  }

  @GetMapping("/{mediaId}/content")
  public ResponseEntity<InputStreamResource> download(
      @PathVariable String projectId,
      @PathVariable String mediaId,
      @RequestHeader(value = "Range", required = false) String range,
      HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw ApiException.invalid();
    var result = media.download(projectId, mediaId, range);
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

  @DeleteMapping("/{mediaId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable String projectId, @PathVariable String mediaId, HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw ApiException.invalid();
    media.delete(projectId, mediaId, RequestContext.requestId(request));
  }
}
