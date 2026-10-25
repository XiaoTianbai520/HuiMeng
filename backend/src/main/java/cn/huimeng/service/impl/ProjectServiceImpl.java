package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.*;
import cn.huimeng.dao.mapper.*;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 项目元数据、成员授权与工作区映射；管理员元数据权限不自动授予创作权限。 */
@Service
public class ProjectServiceImpl implements ProjectService {

  private final ProjectMapper projectMapper;
  private final UserMapper userMapper;
  private final AuditService audit;
  private final Path workspaceRoot;

  public ProjectServiceImpl(
      ProjectMapper projectMapper,
      UserMapper userMapper,
      AuditService audit,
      @Value("${huimeng.workspace-root}") String root) {
    this.projectMapper = projectMapper;
    this.userMapper = userMapper;
    this.audit = audit;
    workspaceRoot = Path.of(root).toAbsolutePath().normalize();
  }

  private ProjectRespDTO toResponse(ProjectDO row) {
    return new ProjectRespDTO(row.id(), row.name(), row.status());
  }

  private ProjectDO requireProject(String id) {
    ProjectDO row = projectMapper.findById(id);
    if (row == null) throw new ApiException(404, "NOT_FOUND", "项目不可用");
    return row;
  }

  @Override
  public boolean member(String projectId, String userId) {
    return projectMapper.member(projectId, userId);
  }

  @Override
  public void requireMember(String id, boolean write) {
    Actor actor = RequestContext.actor();
    if (!member(id, actor.id())) throw new ApiException(403, "PROJECT_FORBIDDEN", "无权访问该项目");
    if (write && !requireProject(id).status().equals("ACTIVE")) throw ApiException.forbidden();
  }

  @Override
  public ProjectRespDTO get(String id) {
    Actor actor = RequestContext.actor();
    if (!actor.role().equals("ADMIN") && !member(id, actor.id()))
      throw new ApiException(403, "PROJECT_FORBIDDEN", "无权访问该项目");
    return toResponse(requireProject(id));
  }

  @Override
  public PageRespDTO<ProjectRespDTO> list(String cursor, int limit) {
    Actor actor = RequestContext.actor();
    var page = projectMapper.list(actor.id(), actor.role().equals("ADMIN"), cursor, limit);
    return new PageRespDTO<>(
        page.items().stream().map(this::toResponse).toList(), page.nextCursor());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ProjectRespDTO create(CreateProjectReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    String id = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
    try {
      Files.createDirectories(workspaceRoot);
      if (Files.isSymbolicLink(workspaceRoot)) throw new IOException();
      Files.createDirectory(workspaceRoot.resolve(key));
    } catch (IOException ex) {
      throw new ApiException(503, "EXECUTOR_UNAVAILABLE", "项目工作区暂不可用");
    }
    // 文件系统不参与数据库事务；保留原行为，不能把此事务声称为文件与数据库原子提交。
    projectMapper.insert(id, input.name(), key, actor.id(), Instant.now());
    audit.record(actor.id(), id, "PROJECT_CREATE", id, "SUCCESS", requestId);
    return get(id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ProjectRespDTO update(String id, UpdateProjectReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    if (input.name() == null && input.status() == null
        || input.name() != null && input.name().isBlank()) throw ApiException.invalid();
    ProjectDO old = requireProject(id);
    projectMapper.update(
        id,
        input.name() == null ? old.name() : input.name(),
        input.status() == null ? old.status() : input.status());
    audit.record(actor.id(), id, "PROJECT_UPDATE", id, "SUCCESS", requestId);
    return get(id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ProjectMemberRespDTO addMember(String id, String userId, String requestId) {
    Actor actor = RequestContext.admin();
    requireProject(id);
    if (!userMapper.active(userId)) throw ApiException.invalid();
    projectMapper.addMember(id, userId, Instant.now());
    audit.record(actor.id(), id, "MEMBER_ADD", userId, "SUCCESS", requestId);
    return new ProjectMemberRespDTO(userId, id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeMember(String id, String userId, String requestId) {
    Actor actor = RequestContext.admin();
    requireProject(id);
    projectMapper.removeMember(id, userId);
    audit.record(actor.id(), id, "MEMBER_REMOVE", userId, "SUCCESS", requestId);
  }

  @Override
  public PageRespDTO<ProjectMemberRespDTO> members(String id, String cursor, int limit) {
    RequestContext.admin();
    requireProject(id);
    var page = projectMapper.members(id, cursor, limit);
    return new PageRespDTO<>(
        page.items().stream()
            .map(r -> new ProjectMemberRespDTO(r.userId(), r.projectId()))
            .toList(),
        page.nextCursor());
  }

  /** 校验成员关系与项目路径；未来文件执行仍须使用隔离或原子 I/O。 */
  @Override
  public Path workspaceRoot(String id, boolean write) {
    requireMember(id, write);
    String key = requireProject(id).workspaceKey();
    if (!key.matches("[A-Za-z0-9_-]{1,128}")) throw pathDenied();
    Path base = workspaceRoot.resolve(key);
    if (Files.isSymbolicLink(workspaceRoot)
        || Files.isSymbolicLink(base)
        || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) throw pathDenied();
    return base;
  }

  @Override
  public Path resolve(String id, String relative, boolean write) {
    Path base = workspaceRoot(id, write);
    if (relative == null
        || relative.isEmpty()
        || relative.length() > 4096
        || relative.startsWith("/")
        || relative.contains("\\")
        || relative.contains(":")
        || relative.contains("%")
        || relative.chars().anyMatch(c -> c < 32)
        || Arrays.stream(relative.split("/", -1))
            .anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) throw pathDenied();
    try {
      if (Files.isSymbolicLink(workspaceRoot)) throw pathDenied();
      // 保留受控根的词法路径，让实际打开还能拒绝根目录祖先被替换为符号链接。
      Path root = workspaceRoot;
      if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS))
        throw pathDenied();
      Path result = base.resolve(relative).normalize();
      if (!result.startsWith(base)) throw pathDenied();
      Path current = root;
      for (Path part : root.relativize(result)) {
        current = current.resolve(part);
        if (Files.isSymbolicLink(current)) throw pathDenied();
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
            && !current.toRealPath().startsWith(base)) throw pathDenied();
      }
      return result;
    } catch (IOException ex) {
      throw pathDenied();
    }
  }

  private ApiException pathDenied() {
    return new ApiException(400, "PATH_NOT_ALLOWED", "文件路径不被允许");
  }
}
