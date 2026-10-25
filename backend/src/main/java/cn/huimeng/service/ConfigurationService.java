package cn.huimeng.service;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import java.util.List;

/** 团队共享密钥与模型批准管理；响应只包含安全元数据。 */
public interface ConfigurationService {

  String MARKET = "tf-router-market";

  /** 加密保存团队密钥，不提供明文读回。 */
  void setSecret(String providerId, String key, String requestId);

  /** 管理员读取供应商配置状态。 */
  List<ProviderStatusRespDTO> providers();

  /** 管理员读取市场配置状态。 */
  boolean marketConfigured();

  /** 成员读取已批准模型。 */
  List<ModelRespDTO> models();

  /** 管理员读取所有已登记模型。 */
  List<ModelRespDTO> adminModels();

  /** 管理员批准或停用模型。 */
  void approve(ModelApprovalReqDTO input, String requestId);

  /** 管理员导出安全状态，不加载或序列化密钥与任意模型配置。 */
  ConfigurationExportRespDTO exportConfiguration(String requestId);
}
