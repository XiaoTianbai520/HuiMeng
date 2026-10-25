package cn.huimeng.service;

import cn.huimeng.dto.resp.ModelRespDTO;

/** 执行器调用前使用的模型授权，不接受前端自报批准状态或服务端端点。 */
public interface ModelAuthorizationService {
  /** 重新检查当前账号项目成员关系与数据库批准状态，返回安全模型描述。 */
  ModelRespDTO requireApproved(String projectId, String providerId, String modelId);
}
