package cn.huimeng.controller;

import cn.huimeng.dto.req.MediaProviderReqDTO;
import cn.huimeng.dto.resp.ApiRespDTO;
import cn.huimeng.dto.resp.MediaProviderDTOs;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.MediaProviderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * 自定义媒体供应商文件的登记面。
 *
 * <p>可以登记、查看声明出来的模型、编辑清单与删除；生成仍只走内置供应商。返回里 {@code executable} 固定为 false， 页面据此禁用生成，避免把「已登记」显示成「可用」。
 */
@RestController
@RequestMapping("/api/v1/admin/providers/media")
public class MediaProviderController {
  private final MediaProviderService providers;

  public MediaProviderController(MediaProviderService providers) {
    this.providers = providers;
  }

  @GetMapping("/list")
  public ApiRespDTO<List<MediaProviderDTOs.Provider>> list() {
    return ApiRespDTO.ok(providers.list());
  }

  @PostMapping("/add")
  public ApiRespDTO<MediaProviderDTOs.Provider> add(
      @RequestBody(required = false) MediaProviderReqDTO input, HttpServletRequest request) {
    return ApiRespDTO.ok(providers.add(input, RequestContext.requestId(request)));
  }

  @PostMapping("/validate")
  public ApiRespDTO<MediaProviderDTOs.Provider> validate(
      @RequestBody(required = false) MediaProviderReqDTO input) {
    return ApiRespDTO.ok(providers.validate(input));
  }

  @PutMapping("/save")
  public ApiRespDTO<MediaProviderDTOs.Provider> save(
      @RequestBody(required = false) MediaProviderReqDTO input, HttpServletRequest request) {
    return ApiRespDTO.ok(providers.save(input, RequestContext.requestId(request)));
  }

  @PostMapping("/models")
  public ApiRespDTO<MediaProviderDTOs.Provider> models(
      @RequestBody(required = false) MediaProviderReqDTO input, HttpServletRequest request) {
    return ApiRespDTO.ok(providers.refreshModels(input, RequestContext.requestId(request)));
  }

  @RequestMapping(
      value = "/delete",
      method = {RequestMethod.POST, RequestMethod.DELETE})
  public ApiRespDTO<String> delete(
      @RequestBody(required = false) MediaProviderReqDTO input, HttpServletRequest request) {
    providers.delete(input, RequestContext.requestId(request));
    return ApiRespDTO.ok("deleted");
  }
}
