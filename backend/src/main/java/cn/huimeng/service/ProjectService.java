package cn.huimeng.service;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import java.nio.file.Path;

/** 项目元数据、成员授权与工作区映射；管理员元数据权限不自动授予创作权限。 */
public interface ProjectService {
  /** 当前项目受控工作区根，仅服务端 I/O 使用，不能进入客户端 DTO。 */
  java.nio.file.Path workspaceRoot(String id, boolean write);

  /** 当前项目是否包含指定账号。 */
  boolean member(String projectId, String userId);

  /** 校验当前身份的成员关系和写入状态。 */
  void requireMember(String id, boolean write);

  /** 按身份读取项目安全视图。 */
  ProjectRespDTO get(String id);

  /** 仅返回当前身份可见项目。 */
  PageRespDTO<ProjectRespDTO> list(String cursor, int limit);

  /** 管理员创建项目及服务端生成的工作区映射。 */
  ProjectRespDTO create(CreateProjectReqDTO input, String requestId);

  /** 管理员修改项目名称或归档状态。 */
  ProjectRespDTO update(String id, UpdateProjectReqDTO input, String requestId);

  /** 管理员授予指定账号成员关系。 */
  ProjectMemberRespDTO addMember(String id, String userId, String requestId);

  /** 管理员移除成员；下次请求重新校验。 */
  void removeMember(String id, String userId, String requestId);

  /** 管理员分页读取成员。 */
  PageRespDTO<ProjectMemberRespDTO> members(String id, String cursor, int limit);

  /** 归一化相对路径并拒绝跨项目、绝对路径及符号链接逃逸。 */
  Path resolve(String id, String relative, boolean write);
}
