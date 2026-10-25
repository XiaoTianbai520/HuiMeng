package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** 项目元数据和成员关系的持久化；授权与工作区 I/O 由业务层负责。 */
@Repository
public class ProjectMapper {
  private static final RowMapper<ProjectDO> ROW =
      (rs, n) ->
          new ProjectDO(
              rs.getString("id"),
              rs.getString("name"),
              rs.getString("status"),
              rs.getString("workspace_key"));
  private final JdbcTemplate jdbc;
  private final CursorMapper cursors;

  public ProjectMapper(JdbcTemplate jdbc, CursorMapper cursors) {
    this.jdbc = jdbc;
    this.cursors = cursors;
  }

  /** 读取包含内部工作区键的项目记录。 */
  public ProjectDO findById(String id) {
    var rows = jdbc.query("SELECT * FROM projects WHERE id=?", ROW, id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 检查指定项目与账号的成员关系。 */
  public boolean member(String projectId, String userId) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=?",
            Integer.class,
            projectId,
            userId)
        > 0;
  }

  /** 检查项目是否处于可用状态。 */
  public boolean active(String id) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM projects WHERE id=? AND status='ACTIVE'", Integer.class, id)
        == 1;
  }

  /** 按管理员或成员作用域分页查询项目。 */
  public CursorPageDO<ProjectDO> list(String actorId, boolean admin, String cursor, int limit) {
    return cursors.query(
        "projects:" + actorId + ":" + (admin ? "ADMIN" : "USER"),
        "projects",
        "id",
        "created_at",
        admin
            ? "1=1"
            : "EXISTS (SELECT 1 FROM project_members pm WHERE pm.project_id=projects.id AND pm.user_id=?)",
        admin ? List.of() : List.of(actorId),
        cursor,
        limit,
        ROW,
        ProjectDO::id);
  }

  /** 插入服务端生成的工作区映射和创建者。 */
  public void insert(String id, String name, String key, String creator, Instant now) {
    jdbc.update(
        "INSERT INTO projects(id,name,workspace_key,status,created_by,created_at) VALUES (?,?,?,'ACTIVE',?,?)",
        id,
        name,
        key,
        creator,
        Timestamp.from(now));
  }

  /** 更新项目元数据。 */
  public void update(String id, String name, String status) {
    jdbc.update("UPDATE projects SET name=?,status=? WHERE id=?", name, status, id);
  }

  /** 插入项目成员关系。 */
  public void addMember(String id, String userId, Instant now) {
    jdbc.update(
        "INSERT INTO project_members(project_id,user_id,joined_at) VALUES (?,?,?)",
        id,
        userId,
        Timestamp.from(now));
  }

  /** 按项目与账号联合条件移除成员。 */
  public void removeMember(String id, String userId) {
    jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?", id, userId);
  }

  /** 按项目分页读取成员关系。 */
  public CursorPageDO<ProjectMemberDO> members(String id, String cursor, int limit) {
    return cursors.query(
        "members:" + id,
        "project_members",
        "user_id",
        "joined_at",
        "project_id=?",
        List.of(id),
        cursor,
        limit,
        (rs, n) -> new ProjectMemberDO(rs.getString("user_id"), rs.getString("project_id")),
        ProjectMemberDO::userId);
  }
}
