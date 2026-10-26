package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.*;
import cn.huimeng.dao.mapper.*;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单项目外部凭证签发、撤销及精确能力校验；每次请求重查有效状态。 */
@Service
public class ExternalClientServiceImpl implements ExternalClientService {

  private final ExternalClientMapper clientMapper;
  private final ProjectMapper projectMapper;
  private final AuditService audit;

  public ExternalClientServiceImpl(
      ExternalClientMapper clientMapper, ProjectMapper projectMapper, AuditService audit) {
    this.clientMapper = clientMapper;
    this.projectMapper = projectMapper;
    this.audit = audit;
  }

  private static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Token hashing unavailable");
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public IssuedExternalClientRespDTO issue(IssueExternalClientReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    if (!projectMapper.active(input.projectId())) throw ApiException.invalid();
    if (input.mcpTools().stream().anyMatch(n -> !n.matches("[A-Za-z0-9_.:/-]{1,128}"))
        || input.a2aTeams().stream().anyMatch(n -> !n.matches("[A-Za-z0-9_-]{1,128}")))
      throw ApiException.invalid();
    String id = UUID.randomUUID().toString();
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    String token =
        "hm1." + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    clientMapper.insert(
        id, input.name(), input.projectId(), hash(token), actor.id(), Instant.now());
    for (String tool : new HashSet<>(input.mcpTools()))
      clientMapper.grant(id, input.projectId(), "MCP", tool);
    for (String team : new HashSet<>(input.a2aTeams()))
      clientMapper.grant(id, input.projectId(), "A2A", team);
    audit.record(actor.id(), input.projectId(), "CLIENT_ISSUE", id, "SUCCESS", requestId);
    return new IssuedExternalClientRespDTO(id, token, input);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void revoke(String id, String requestId) {
    Actor actor = RequestContext.admin();
    String projectId = clientMapper.projectId(id);
    if (projectId == null) throw new ApiException(404, "NOT_FOUND", "客户端不存在");
    clientMapper.revoke(id, Instant.now());
    audit.record(actor.id(), projectId, "CLIENT_REVOKE", id, "SUCCESS", requestId);
  }

  @Override
  public ExternalActor authenticate(String authorization) {
    if (authorization == null
        || !authorization.matches("Bearer hm1\\.[A-Za-z0-9-]{36}\\.[A-Za-z0-9_-]{43}"))
      throw invalid();
    ExternalClientDO row = clientMapper.findActiveByHash(hash(authorization.substring(7)));
    if (row == null) throw invalid();
    return new ExternalActor(row.id(), row.projectId());
  }

  @Override
  public void authorize(ExternalActor actor, String projectId, String type, String capability) {
    if (!actor.projectId().equals(projectId)
        || !clientMapper.granted(actor.clientId(), projectId, type, capability))
      throw ApiException.forbidden();
  }

  @Override
  public List<String> abilities(ExternalActor actor, String type) {
    if (!"MCP".equals(type) && !"A2A".equals(type)) throw ApiException.invalid();
    return clientMapper.capabilities(actor.clientId(), type);
  }

  @Override
  public PageRespDTO<ExternalClientRespDTO> list(String cursor, int limit) {
    RequestContext.admin();
    var page = clientMapper.list(cursor, limit);
    return new PageRespDTO<>(
        page.items().stream()
            .map(
                r ->
                    new ExternalClientRespDTO(
                        r.id(),
                        r.name(),
                        r.projectId(),
                        r.createdAt(),
                        r.revokedAt(),
                        clientMapper.capabilities(r.id(), "MCP"),
                        clientMapper.capabilities(r.id(), "A2A")))
            .toList(),
        page.nextCursor());
  }

  private ApiException invalid() {
    return new ApiException(401, "CLIENT_TOKEN_INVALID", "客户端凭证无效");
  }
}
