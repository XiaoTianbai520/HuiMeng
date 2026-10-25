package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** 外部凭证和精确能力授权的 SQL；哈希只用于存入与匹配。 */
@Repository
public class ExternalClientMapper {
  private final JdbcTemplate jdbc;
  private final CursorMapper cursors;

  public ExternalClientMapper(JdbcTemplate jdbc, CursorMapper cursors) {
    this.jdbc = jdbc;
    this.cursors = cursors;
  }

  /** 保存客户端元数据及令牌哈希，不保存明文。 */
  public void insert(
      String id, String name, String projectId, String hash, String actorId, Instant now) {
    jdbc.update(
        "INSERT INTO external_clients(id,name,project_id,secret_hash,status,created_by,created_at) VALUES (?,?,?,?,'ACTIVE',?,?)",
        id,
        name,
        projectId,
        hash,
        actorId,
        Timestamp.from(now));
  }

  /** 插入单项目的精确能力授权。 */
  public void grant(String id, String projectId, String type, String name) {
    jdbc.update(
        "INSERT INTO external_grants(client_id,project_id,capability_type,capability_name) VALUES (?,?,?,?)",
        id,
        projectId,
        type,
        name);
  }

  /** 查询客户端所属项目；不存在时返回空。 */
  public String projectId(String id) {
    var rows =
        jdbc.queryForList("SELECT project_id FROM external_clients WHERE id=?", String.class, id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 撤销凭证并保留首次撤销时间。 */
  public void revoke(String id, Instant now) {
    jdbc.update(
        "UPDATE external_clients SET status='REVOKED',revoked_at=COALESCE(revoked_at,?) WHERE id=?",
        Timestamp.from(now),
        id);
  }

  /** 同时匹配令牌哈希、客户端状态与项目状态。 */
  public ExternalClientDO findActiveByHash(String hash) {
    var rows =
        jdbc.query(
            "SELECT c.id,c.name,c.project_id,c.created_at,c.revoked_at FROM external_clients c JOIN projects p ON p.id=c.project_id WHERE c.secret_hash=? AND c.status='ACTIVE' AND p.status='ACTIVE'",
            ROW,
            hash);
    return rows.size() == 1 ? rows.get(0) : null;
  }

  /** 检查客户端、项目和能力的精确联合授权。 */
  public boolean granted(String id, String projectId, String type, String name) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM external_grants WHERE client_id=? AND project_id=? AND capability_type=? AND capability_name=?",
            Integer.class,
            id,
            projectId,
            type,
            name)
        == 1;
  }

  /** 读取已授予的能力名称。 */
  public List<String> capabilities(String id, String type) {
    return jdbc.queryForList(
        "SELECT capability_name FROM external_grants WHERE client_id=? AND capability_type=? ORDER BY capability_name",
        String.class,
        id,
        type);
  }

  private static final RowMapper<ExternalClientDO> ROW =
      (rs, n) ->
          new ExternalClientDO(
              rs.getString("id"),
              rs.getString("name"),
              rs.getString("project_id"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("revoked_at") == null
                  ? null
                  : rs.getTimestamp("revoked_at").toInstant());

  /** 分页读取客户端元数据，不映射令牌哈希。 */
  public CursorPageDO<ExternalClientDO> list(String cursor, int limit) {
    return cursors.query(
        "clients",
        "external_clients",
        "id",
        "created_at",
        "1=1",
        List.of(),
        cursor,
        limit,
        ROW,
        ExternalClientDO::id);
  }
}
