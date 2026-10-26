package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.A2aTaskDO;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** A2A 任务的写入与按 client+project 归属查询；查询永不带出其他客户端任务。 */
@Repository
public class A2aTaskMapper {
  private final JdbcTemplate jdbc;

  public A2aTaskMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(A2aTaskDO task) {
    jdbc.update(
        "INSERT INTO a2a_tasks(id,project_id,client_id,team,context_id,session_file,state,prompt,result_text,error,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        task.id(),
        task.projectId(),
        task.clientId(),
        task.team(),
        task.contextId(),
        task.sessionFile(),
        task.state(),
        task.prompt(),
        task.resultText(),
        task.error(),
        Timestamp.from(task.createdAt()),
        Timestamp.from(task.updatedAt()));
  }

  public void session(String id, String sessionFile) {
    jdbc.update(
        "UPDATE a2a_tasks SET session_file=?,updated_at=? WHERE id=?",
        sessionFile,
        Timestamp.from(Instant.now()),
        id);
  }

  public void state(String id, String state, String resultText, String error) {
    jdbc.update(
        "UPDATE a2a_tasks SET state=?,result_text=?,error=?,updated_at=? WHERE id=?",
        state,
        resultText,
        error,
        Timestamp.from(Instant.now()),
        id);
  }

  /** 归属不匹配时与不存在同样返回空，避免泄露任务是否存在。 */
  public A2aTaskDO find(String clientId, String projectId, String id) {
    var rows =
        jdbc.query(
            "SELECT * FROM a2a_tasks WHERE id=? AND client_id=? AND project_id=?",
            ROW,
            id,
            clientId,
            projectId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static final RowMapper<A2aTaskDO> ROW =
      (rs, n) ->
          new A2aTaskDO(
              rs.getString("id"),
              rs.getString("project_id"),
              rs.getString("client_id"),
              rs.getString("team"),
              rs.getString("context_id"),
              rs.getString("session_file"),
              rs.getString("state"),
              rs.getString("prompt"),
              rs.getString("result_text"),
              rs.getString("error"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());
}
