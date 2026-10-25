package cn.huimeng.service.impl;

import cn.huimeng.dao.entity.*;
import cn.huimeng.dao.mapper.*;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** 只记录允许的操作元数据；正文、密钥、认证头及异常内容不属于参数。 */
@Service
public class AuditServiceImpl implements AuditService {

  private final AuditMapper auditMapper;

  public AuditServiceImpl(AuditMapper auditMapper) {
    this.auditMapper = auditMapper;
  }

  @Override
  public void record(
      String actorId,
      String projectId,
      String action,
      String target,
      String result,
      String requestId) {
    auditMapper.insert(
        actorId == null ? "ANONYMOUS" : "USER",
        actorId,
        projectId,
        action,
        target,
        result,
        requestId,
        Instant.now());
  }

  @Override
  public void recordExternal(String clientId, String projectId, String result, String requestId) {
    auditMapper.insert(
        clientId == null ? "ANONYMOUS" : "CLIENT",
        clientId,
        projectId,
        "EXTERNAL_GATE",
        clientId,
        result,
        requestId,
        Instant.now());
  }

  @Override
  public void purgeExpired(int days) {
    if (days < 1) throw new IllegalArgumentException("Audit retention must be positive");
    auditMapper.deleteBefore(Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS));
  }
}
