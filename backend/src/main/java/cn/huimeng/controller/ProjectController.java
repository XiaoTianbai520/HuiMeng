package cn.huimeng.controller;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 项目元数据和成员管理；文件访问由独立的精确路由控制器处理。 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  /** 读取当前账号可见项目的游标页。 */
  @GetMapping
  public PageRespDTO<ProjectRespDTO> list(
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
    return projects.list(cursor, limit);
  }

  /** 读取经过身份校验的项目元数据。 */
  @GetMapping("/{projectId}")
  public ProjectRespDTO get(@PathVariable String projectId) {
    return projects.get(projectId);
  }

  /** 创建项目及服务端工作区映射。 */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectRespDTO create(
      @Valid @RequestBody CreateProjectReqDTO input, HttpServletRequest request) {
    return projects.create(input, RequestContext.requestId(request));
  }

  /** 修改项目元数据或归档状态。 */
  @PatchMapping("/{projectId}")
  public ProjectRespDTO update(
      @PathVariable String projectId,
      @Valid @RequestBody UpdateProjectReqDTO input,
      HttpServletRequest request) {
    return projects.update(projectId, input, RequestContext.requestId(request));
  }

  /** 管理员分页读取项目成员。 */
  @GetMapping("/{projectId}/members")
  public PageRespDTO<ProjectMemberRespDTO> members(
      @PathVariable String projectId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return projects.members(projectId, cursor, limit);
  }

  /** 管理员添加有效账号为项目成员。 */
  @PostMapping("/{projectId}/members")
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectMemberRespDTO add(
      @PathVariable String projectId,
      @Valid @RequestBody ProjectMemberReqDTO input,
      HttpServletRequest request) {
    return projects.addMember(projectId, input.userId(), RequestContext.requestId(request));
  }

  /** 管理员移除项目成员关系。 */
  @DeleteMapping("/{projectId}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(
      @PathVariable String projectId, @PathVariable String userId, HttpServletRequest request) {
    projects.removeMember(projectId, userId, RequestContext.requestId(request));
  }
}
