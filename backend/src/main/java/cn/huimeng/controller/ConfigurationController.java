package cn.huimeng.controller;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.ConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 团队密钥只写接口与登记模型目录；不提供密钥读取或导出。 */
@RestController
public class ConfigurationController {
  private final ConfigurationService configuration;

  public ConfigurationController(ConfigurationService configuration) {
    this.configuration = configuration;
  }

  /** 读取当前账号可用的获批模型。 */
  @GetMapping("/api/v1/models")
  public ItemsRespDTO<ModelRespDTO> models() {
    return new ItemsRespDTO<>(configuration.models());
  }

  /** 管理员读取团队供应商密钥配置状态。 */
  @GetMapping("/api/v1/admin/providers")
  public ItemsRespDTO<ProviderStatusRespDTO> providers() {
    return new ItemsRespDTO<>(configuration.providers());
  }

  /** 管理员读取完整登记模型目录。 */
  @GetMapping("/api/v1/admin/models")
  public ItemsRespDTO<ModelRespDTO> adminModels() {
    return new ItemsRespDTO<>(configuration.adminModels());
  }

  /** 更新供应商只写密钥。 */
  @PutMapping("/api/v1/admin/providers/{providerId}/secret")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void secret(
      @PathVariable String providerId,
      @Valid @RequestBody ProviderSecretReqDTO input,
      HttpServletRequest request) {
    configuration.setSecret(providerId, input.apiKey(), RequestContext.requestId(request));
  }

  /** 更新团队市场只写密钥。 */
  @PutMapping("/api/v1/admin/plugin-market/secret")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void marketSecret(
      @Valid @RequestBody ProviderSecretReqDTO input, HttpServletRequest request) {
    configuration.setSecret(
        ConfigurationService.MARKET, input.apiKey(), RequestContext.requestId(request));
  }

  /** 读取市场是否已配置。 */
  @GetMapping("/api/v1/admin/plugin-market/status")
  public ConfiguredRespDTO marketStatus() {
    return new ConfiguredRespDTO(configuration.marketConfigured());
  }

  /** 批准或停用已登记模型。 */
  @PutMapping("/api/v1/admin/models/approval")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void approve(@Valid @RequestBody ModelApprovalReqDTO input, HttpServletRequest request) {
    configuration.approve(input, RequestContext.requestId(request));
  }
}
