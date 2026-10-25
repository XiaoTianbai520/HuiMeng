package cn.huimeng.dao.mapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅按可信账号标识读取和保存个人偏好；保存前须由业务事务锁定账号。 */
@Repository
public class PreferencesMapper {
  private final JdbcTemplate jdbc;

  public PreferencesMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 读取指定可信账号偏好；未保存时返回空。 */
  public String find(String userId) {
    var rows =
        jdbc.queryForList(
            "SELECT preferences_json FROM user_preferences WHERE user_id=?", String.class, userId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 在账号锁和调用方事务内更新或新增偏好。 */
  public void save(String userId, String value) {
    if (jdbc.update("UPDATE user_preferences SET preferences_json=? WHERE user_id=?", value, userId)
        == 0)
      jdbc.update(
          "INSERT INTO user_preferences(user_id,preferences_json) VALUES (?,?)", userId, value);
  }
}
