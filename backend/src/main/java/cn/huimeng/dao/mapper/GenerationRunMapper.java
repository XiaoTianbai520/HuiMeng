package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.GenerationRunDO;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** 由服务事务锁定项目行后抢占名额；查询/更新始终限定项目。 */
@Repository
public class GenerationRunMapper {
  private static final RowMapper<GenerationRunDO> ROW =
      (r, n) ->
          new GenerationRunDO(
              r.getString("id"),
              r.getString("project_id"),
              r.getString("actor_id"),
              r.getString("state"),
              r.getBoolean("capacity_held"),
              r.getString("request_hash"),
              r.getString("upstream_ref"),
              r.getTimestamp("started_at").toInstant(),
              r.getTimestamp("ended_at") == null ? null : r.getTimestamp("ended_at").toInstant());
  private final JdbcTemplate jdbc;

  public GenerationRunMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lockProject(String project) {
    jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", String.class, project);
  }

  public int held(String project) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM generation_runs WHERE project_id=? AND capacity_held=TRUE",
        Integer.class,
        project);
  }

  public GenerationRunDO find(String project, String id) {
    var rows =
        jdbc.query("SELECT * FROM generation_runs WHERE project_id=? AND id=?", ROW, project, id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public GenerationRunDO idempotent(String project, String actor, String key) {
    var rows =
        jdbc.query(
            "SELECT * FROM generation_runs WHERE project_id=? AND actor_type='USER' AND actor_id=? AND operation='generateMedia' AND idempotency_key=?",
            ROW,
            project,
            actor,
            key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void insert(String project, String id, String actor, String key, String hash) {
    jdbc.update(
        "INSERT INTO generation_runs(id,project_id,actor_type,actor_id,kind,state,capacity_held,operation,idempotency_key,request_hash,started_at) VALUES (?,?,'USER',?,'image','RESERVED',TRUE,'generateMedia',?,?,?)",
        id,
        project,
        actor,
        key,
        hash,
        Timestamp.from(Instant.now()));
  }

  public int running(String project, String id) {
    return jdbc.update(
        "UPDATE generation_runs SET state='RUNNING' WHERE project_id=? AND id=? AND state='RESERVED'",
        project,
        id);
  }

  public void reference(String project, String id, String ref) {
    jdbc.update(
        "UPDATE generation_runs SET upstream_ref=? WHERE project_id=? AND id=?", ref, project, id);
  }

  public void cancel(String project, String id) {
    jdbc.update(
        "UPDATE generation_runs SET state='CANCELLING' WHERE project_id=? AND id=? AND state IN ('RESERVED','RUNNING')",
        project,
        id);
  }

  public int succeed(String project, String id) {
    return jdbc.update(
        "UPDATE generation_runs SET state='SUCCEEDED',capacity_held=FALSE,ended_at=? WHERE project_id=? AND id=? AND state='RUNNING'",
        Timestamp.from(Instant.now()),
        project,
        id);
  }

  public void finish(String project, String id, String state, boolean hold) {
    jdbc.update(
        "UPDATE generation_runs SET state=?,capacity_held=?,ended_at=? WHERE project_id=? AND id=? AND capacity_held=TRUE",
        state,
        hold,
        Timestamp.from(Instant.now()),
        project,
        id);
  }

  /** 进程重启后没有线程再推动这些运行：没提交到上游的当场释放名额，已提交的等核对上游终态。 */
  public void markLostAfterRestart() {
    jdbc.update(
        "UPDATE generation_runs SET state='LOST',capacity_held=FALSE,ended_at=NOW() WHERE capacity_held=TRUE AND state IN ('RESERVED','RUNNING','CANCELLING') AND (upstream_ref IS NULL OR upstream_ref='')");
    jdbc.update(
        "UPDATE generation_runs SET state='LOST' WHERE capacity_held=TRUE AND state IN ('RESERVED','RUNNING','CANCELLING')");
  }

  public List<GenerationRunDO> lost() {
    return jdbc.query(
        "SELECT * FROM generation_runs WHERE capacity_held=TRUE AND state='LOST' AND upstream_ref IS NOT NULL",
        ROW);
  }

  /** 上游长期查不到终态（例如执行器被重建）时的兜底，避免名额被永久占用。 */
  public List<GenerationRunDO> lostOlderThan(int minutes) {
    return jdbc.query(
        "SELECT * FROM generation_runs WHERE capacity_held=TRUE AND state='LOST' AND started_at < ?",
        ROW,
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(minutes))));
  }
}
