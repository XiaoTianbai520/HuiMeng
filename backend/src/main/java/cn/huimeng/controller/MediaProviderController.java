package cn.huimeng.controller;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.MediaProviderReqDTO;
import cn.huimeng.security.RequestContext;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * 自定义媒体供应商文件：当前版本只支持内置供应商，列表为空而不是伪造条目。
 *
 * <p>写入入口显式拒绝，避免页面把"保存成功"误当成能力已生效。
 */
@RestController
@RequestMapping("/api/v1/admin/providers/media")
public class MediaProviderController {

  @GetMapping("/list")
  public MediaProviders list() {
    RequestContext.admin();
    return new MediaProviders(List.of());
  }

  @PostMapping({"/add", "/save"})
  public MediaProviders unsupported(@RequestBody(required = false) MediaProviderReqDTO input) {
    RequestContext.admin();
    throw new ApiException(409, "MEDIA_PROVIDER_UNSUPPORTED", "自定义媒体供应商文件暂未开放，请使用内置模型");
  }

  @PostMapping("/delete")
  public MediaProviders delete() {
    RequestContext.admin();
    throw new ApiException(409, "MEDIA_PROVIDER_UNSUPPORTED", "自定义媒体供应商文件暂未开放");
  }

  @PostMapping("/models")
  public MediaProviders models() {
    RequestContext.admin();
    throw new ApiException(409, "MEDIA_PROVIDER_UNSUPPORTED", "自定义媒体供应商文件暂未开放");
  }

  public record MediaProviders(List<?> data) {}
}
