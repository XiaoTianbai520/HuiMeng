package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.resp.ModelRespDTO;
import cn.huimeng.service.*;
import org.springframework.stereotype.Service;

/** 每次执行重新校验；管理员角色不替代项目成员，不缓存已撤销的批准结果。 */
@Service
public class ModelAuthorizationServiceImpl implements ModelAuthorizationService {
  private final ProjectService projects;
  private final ConfigurationService configuration;

  public ModelAuthorizationServiceImpl(
      ProjectService projects, ConfigurationService configuration) {
    this.projects = projects;
    this.configuration = configuration;
  }

  @Override
  public ModelRespDTO requireApproved(String projectId, String providerId, String modelId) {
    projects.requireMember(projectId, true);
    return configuration.models().stream()
        .filter(model -> model.providerId().equals(providerId) && model.modelId().equals(modelId))
        .findFirst()
        .orElseThrow(ApiException::forbidden);
  }
}
