package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** 所有对象查询及状态更新均同时限定项目与媒体 ID。 */
@Repository
public class ProjectMediaMapper {
  private static final RowMapper<ProjectMediaDO> ROW =
      (r, n) ->
          new ProjectMediaDO(
              r.getString("id"),
              r.getString("project_id"),
              r.getString("object_key"),
              r.getString("filename"),
              r.getLong("byte_size"),
              r.getString("sha256"),
              r.getString("state"),
              r.getString("created_by"),
              r.getTimestamp("created_at").toInstant());
  private final JdbcTemplate jdbc;
  private final CursorMapper cursors;

  public ProjectMediaMapper(JdbcTemplate jdbc, CursorMapper cursors) {
    this.jdbc = jdbc;
    this.cursors = cursors;
  }

  public void insert(
      String id,
      String project,
      String key,
      String filename,
      long size,
      String actor,
      Instant time) {
    jdbc.update(
        "INSERT INTO project_media(id,project_id,object_key,filename,byte_size,state,created_by,created_at) VALUES (?,?,?,?,?,'PENDING',?,?)",
        id,
        project,
        key,
        filename,
        size,
        actor,
        Timestamp.from(time));
  }

  public ProjectMediaDO find(String project, String id) {
    var rows =
        jdbc.query("SELECT * FROM project_media WHERE project_id=? AND id=?", ROW, project, id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public CursorPageDO<ProjectMediaDO> list(String project, String cursor, int limit) {
    return cursors.query(
        "media:" + project,
        "project_media",
        "id",
        "created_at",
        "project_id=? AND state='READY'",
        List.of(project),
        cursor,
        limit,
        ROW,
        ProjectMediaDO::id);
  }

  public int ready(String project, String id, String hash) {
    return jdbc.update(
        "UPDATE project_media SET state='READY',sha256=? WHERE project_id=? AND id=? AND state='PENDING'",
        hash,
        project,
        id);
  }

  public void fail(String project, String id) {
    jdbc.update(
        "UPDATE project_media SET state='FAILED' WHERE project_id=? AND id=? AND state='PENDING'",
        project,
        id);
  }

  public int deleting(String project, String id) {
    return jdbc.update(
        "UPDATE project_media SET state='DELETING' WHERE project_id=? AND id=? AND state IN ('READY','FAILED','DELETING')",
        project,
        id);
  }

  public void remove(String project, String id) {
    jdbc.update(
        "DELETE FROM project_media WHERE project_id=? AND id=? AND state='DELETING'", project, id);
  }
}
