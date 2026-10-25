package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** 账号 SQL 与行映射；参数全部绑定，管理员互斥锁由调用方事务持有。 */
@Repository
public class UserMapper {
  private static final RowMapper<UserDO> ROW =
      (rs, n) ->
          new UserDO(
              rs.getString("id"),
              rs.getString("username"),
              rs.getString("password_hash"),
              rs.getString("status"),
              rs.getString("platform_role"),
              rs.getLong("auth_version"));
  private final JdbcTemplate jdbc;
  private final CursorMapper cursors;

  public UserMapper(JdbcTemplate jdbc, CursorMapper cursors) {
    this.jdbc = jdbc;
    this.cursors = cursors;
  }

  /** 统计账号总数，供首次初始化判断。 */
  public int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
  }

  /** 按标识读取认证记录，不直接作为响应。 */
  public UserDO findById(String id) {
    return first(jdbc.query("SELECT * FROM users WHERE id=?", ROW, id));
  }

  /** 按用户名读取认证记录。 */
  public UserDO findByUsername(String username) {
    return first(jdbc.query("SELECT * FROM users WHERE username=?", ROW, username));
  }

  private UserDO first(List<UserDO> rows) {
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 持有初始化专用行锁，序列化管理员状态变更。 */
  public void lockAdministration() {
    jdbc.queryForObject("SELECT id FROM bootstrap_lock WHERE id=1 FOR UPDATE", Integer.class);
  }

  /** 锁定账号，序列化其偏好更新。 */
  public void lockAccount(String id) {
    jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, id);
  }

  /** 统计仍有效的管理员。 */
  public int countActiveAdministrators() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM users WHERE status='ACTIVE' AND platform_role='ADMIN'",
        Integer.class);
  }

  /** 检查目标账号是否有效。 */
  public boolean active(String id) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id=? AND status='ACTIVE'", Integer.class, id)
        == 1;
  }

  /** 插入已经哈希的密码和服务端角色。 */
  public void insert(String id, String username, String hash, String role, Instant now) {
    jdbc.update(
        "INSERT INTO users(id,username,password_hash,status,platform_role,created_at) VALUES (?,?,?,'ACTIVE',?,?)",
        id,
        username,
        hash,
        role,
        Timestamp.from(now));
  }

  /** 更新账号状态、角色并递增认证版本。 */
  public void updateIdentity(String id, String status, String role) {
    jdbc.update(
        "UPDATE users SET status=?,platform_role=?,auth_version=auth_version+1 WHERE id=?",
        status,
        role,
        id);
  }

  /** 仅保存已经哈希的密码。 */
  public void updatePassword(String id, String hash) {
    jdbc.update("UPDATE users SET password_hash=? WHERE id=?", hash, id);
  }

  /** 按固定账号排序和作用域读取游标页。 */
  public CursorPageDO<UserDO> list(String cursor, int limit) {
    return cursors.query(
        "users", "users", "id", "created_at", "1=1", List.of(), cursor, limit, ROW, UserDO::id);
  }
}
