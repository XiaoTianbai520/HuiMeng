package cn.huimeng.service;

import cn.huimeng.dto.req.A2aSettingsReqDTO;

/** 团队级说明文档与 A2A 设置：按内容摘要做修订，读取只对管理员开放。 */
public interface DocumentService {

  Document read(String name);

  Document save(String name, String content, String expectedRevision, String requestId);

  void remove(String name, String requestId);

  A2aSettings a2aSettings(String baseUrl);

  A2aSettings saveA2aSettings(A2aSettingsReqDTO input, String baseUrl, String requestId);

  record Document(String content, String revision) {}

  record A2aSettings(
      boolean enabled,
      String directory,
      String token,
      String url,
      String providerId,
      String modelId,
      String thinkingLevel) {}
}
