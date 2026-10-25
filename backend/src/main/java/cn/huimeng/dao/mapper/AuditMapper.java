package cn.huimeng.dao.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 审计元数据只能追加与按保留期删除，不提供更新能力。 */
@Repository
public class AuditMapper {
  private final JdbcTemplate jdbc;

  public AuditMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 追加允许的审计元数据。 */
  public void insert(
      String actorType,
      String actorId,
      String projectId,
      String action,
      String target,
      String result,
      String requestId,
      Instant now) {
    jdbc.update(
        "INSERT INTO audit_events(id,actor_type,actor_id,project_id,action,target,result,request_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actorType,
        actorId,
        projectId,
        action,
        target,
        result,
        requestId,
        Timestamp.from(now));
  }

  /** 仅删除早于保留边界的审计记录。 */
  public void deleteBefore(Instant before) {
    jdbc.update("DELETE FROM audit_events WHERE created_at<?", Timestamp.from(before));
  }
}
