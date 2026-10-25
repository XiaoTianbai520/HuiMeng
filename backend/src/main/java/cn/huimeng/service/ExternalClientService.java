package cn.huimeng.service;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;

/** 单项目外部凭证签发、撤销及精确能力校验；每次请求重查有效状态。 */
public interface ExternalClientService {

  /** 管理员签发凭证；明文令牌只在本次响应出现。 */
  IssuedExternalClientRespDTO issue(IssueExternalClientReqDTO input, String requestId);

  /** 管理员撤销凭证。 */
  void revoke(String id, String requestId);

  /** 验证 Bearer 凭证和项目有效状态。 */
  ExternalActor authenticate(String authorization);

  /** 校验项目和显式 MCP/A2A 能力。 */
  void authorize(ExternalActor actor, String projectId, String type, String capability);

  /** 管理员分页读取不含令牌的元数据。 */
  PageRespDTO<ExternalClientRespDTO> list(String cursor, int limit);
}
