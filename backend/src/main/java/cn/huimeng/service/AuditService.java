package cn.huimeng.service;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;

/** 只记录允许的操作元数据；正文、密钥、认证头及异常内容不属于参数。 */
public interface AuditService {

  /** 追加内部账号或匿名操作审计。 */
  void record(
      String actorId,
      String projectId,
      String action,
      String target,
      String result,
      String requestId);

  /** 追加外部客户端入口审计。 */
  void recordExternal(String clientId, String projectId, String result, String requestId);

  /** 按已配置保留期清理过期元数据。 */
  void purgeExpired(int days);
}
