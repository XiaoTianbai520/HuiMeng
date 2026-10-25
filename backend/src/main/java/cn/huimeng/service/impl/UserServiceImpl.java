package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.*;
import cn.huimeng.dao.mapper.*;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号生命周期与可信身份查询；事务内维护最后一名有效管理员约束。 */
@Service
public class UserServiceImpl implements UserService {

  private final UserMapper userMapper;
  private final PasswordEncoder passwords;
  private final AuditService audit;
  private final String dummyHash;

  public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwords, AuditService audit) {
    this.userMapper = userMapper;
    this.passwords = passwords;
    this.audit = audit;
    dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  private UserRespDTO toResponse(UserDO row) {
    return new UserRespDTO(row.id(), row.username(), row.platformRole(), row.status());
  }

  private UserDO requireUser(String id) {
    UserDO row = userMapper.findById(id);
    if (row == null) throw new ApiException(404, "NOT_FOUND", "账号不存在");
    return row;
  }

  private Actor toActor(UserDO row) {
    return new Actor(row.id(), row.username(), row.platformRole(), row.status(), row.authVersion());
  }

  @Override
  public boolean initialized() {
    return userMapper.count() > 0;
  }

  @Override
  public UserRespDTO get(String id) {
    return toResponse(requireUser(id));
  }

  @Override
  public Actor findActor(String id) {
    UserDO row = userMapper.findById(id);
    return row == null ? null : toActor(row);
  }

  @Override
  public Actor authenticate(String username, String password) {
    UserDO row = userMapper.findByUsername(username);
    boolean matches = passwords.matches(password, row == null ? dummyHash : row.passwordHash());
    if (!matches || row == null || !row.status().equals("ACTIVE"))
      throw new ApiException(401, "UNAUTHENTICATED", "用户名或密码无效");
    return toActor(row);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void bootstrap(String username, String password) {
    userMapper.lockAdministration();
    if (userMapper.count() != 0) return;
    if (username == null
        || username.isBlank()
        || username.length() > 128
        || password == null
        || password.length() < 12
        || password.length() > 256) throw new IllegalStateException("Invalid bootstrap input");
    String id = insert(username, password, "ADMIN");
    audit.record(id, null, "ADMIN_BOOTSTRAP", id, "SUCCESS", "bootstrap");
  }

  private String insert(String username, String password, String role) {
    String id = UUID.randomUUID().toString();
    userMapper.insert(id, username, passwords.encode(password), role, Instant.now());
    return id;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public UserRespDTO create(CreateUserReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    String id =
        insert(input.username(), input.password(), input.role() == null ? "USER" : input.role());
    audit.record(actor.id(), null, "USER_CREATE", id, "SUCCESS", requestId);
    return get(id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public UserRespDTO update(String id, UpdateUserReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    if (input.status() == null && input.role() == null && input.password() == null)
      throw ApiException.invalid();
    userMapper.lockAdministration();
    UserDO old = requireUser(id);
    String status = input.status() == null ? old.status() : input.status();
    String role = input.role() == null ? old.platformRole() : input.role();
    if (old.platformRole().equals("ADMIN")
        && old.status().equals("ACTIVE")
        && (!role.equals("ADMIN") || !status.equals("ACTIVE"))
        && userMapper.countActiveAdministrators() <= 1) throw ApiException.invalid();
    userMapper.updateIdentity(id, status, role);
    if (input.password() != null) userMapper.updatePassword(id, passwords.encode(input.password()));
    audit.record(actor.id(), null, "USER_UPDATE", id, "SUCCESS", requestId);
    return get(id);
  }

  @Override
  public PageRespDTO<UserRespDTO> list(String cursor, int limit) {
    RequestContext.admin();
    var page = userMapper.list(cursor, limit);
    return new PageRespDTO<>(
        page.items().stream().map(this::toResponse).toList(), page.nextCursor());
  }
}
