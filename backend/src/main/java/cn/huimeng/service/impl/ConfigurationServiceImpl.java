package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.*;
import cn.huimeng.dao.mapper.*;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 团队共享密钥与模型批准管理；响应只包含安全元数据。 */
@Service
public class ConfigurationServiceImpl implements ConfigurationService {

  private final ConfigurationMapper configurationMapper;
  private final SecretBox box;
  private final AuditService audit;
  private final ObjectMapper json;

  public ConfigurationServiceImpl(
      ConfigurationMapper configurationMapper,
      SecretBox box,
      AuditService audit,
      ObjectMapper json) {
    this.configurationMapper = configurationMapper;
    this.box = box;
    this.audit = audit;
    this.json = json;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void setSecret(String providerId, String key, String requestId) {
    Actor actor = RequestContext.admin();
    if (!providerId.matches("[A-Za-z0-9_-]{1,128}")) throw ApiException.invalid();
    configurationMapper.replaceSecret(
        providerId, box.encrypt(providerId, key), actor.id(), Instant.now());
    audit.record(
        actor.id(),
        null,
        providerId.equals(MARKET) ? "MARKET_SECRET_SET" : "PROVIDER_SECRET_SET",
        providerId,
        "SUCCESS",
        requestId);
  }

  @Override
  public List<ProviderStatusRespDTO> providers() {
    RequestContext.admin();
    return configurationMapper.providersExcept(MARKET).stream()
        .map(r -> new ProviderStatusRespDTO(r.providerId(), true, r.updatedAt()))
        .toList();
  }

  @Override
  public boolean marketConfigured() {
    RequestContext.admin();
    return configurationMapper.secretConfigured(MARKET);
  }

  @Override
  public List<ModelRespDTO> models() {
    RequestContext.actor();
    return modelCatalog(false);
  }

  @Override
  public List<ModelRespDTO> adminModels() {
    RequestContext.admin();
    return modelCatalog(true);
  }

  private List<ModelRespDTO> modelCatalog(boolean includeDisabled) {
    return configurationMapper.models(includeDisabled).stream()
        .map(
            row -> {
              try {
                return new ModelRespDTO(
                    row.providerId(),
                    row.modelId(),
                    row.label(),
                    row.enabled(),
                    json.readTree(row.capabilitiesJson()),
                    json.readTree(row.optionsJson()));
              } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("Model metadata unavailable");
              }
            })
        .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void approve(ModelApprovalReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    if (configurationMapper.approve(input.providerId(), input.modelId(), input.enabled()) != 1)
      throw new ApiException(404, "NOT_FOUND", "模型不可用");
    audit.record(actor.id(), null, "MODEL_APPROVAL", input.providerId(), "SUCCESS", requestId);
  }
}
