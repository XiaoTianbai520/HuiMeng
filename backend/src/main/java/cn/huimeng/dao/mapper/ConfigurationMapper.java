package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 团队密文与模型目录持久化；不向管理列表选择密文字段。 */
@Repository
public class ConfigurationMapper {
  private final JdbcTemplate jdbc;

  public ConfigurationMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 在调用方事务中替换团队密文和版本元数据。 */
  public void replaceSecret(String providerId, String encrypted, String actorId, Instant now) {
    jdbc.update("DELETE FROM provider_secrets WHERE provider_id=?", providerId);
    jdbc.update(
        "INSERT INTO provider_secrets(provider_id,encrypted_value,key_version,updated_by,updated_at) VALUES (?,?,1,?,?)",
        providerId,
        encrypted,
        actorId,
        Timestamp.from(now));
  }

  /** 列举供应商配置状态，排除市场记录及密文。 */
  public List<ProviderStatusDO> providersExcept(String market) {
    return jdbc.query(
        "SELECT provider_id,updated_at FROM provider_secrets WHERE provider_id<>? ORDER BY provider_id",
        (rs, n) -> new ProviderStatusDO(rs.getString(1), rs.getTimestamp(2).toInstant()),
        market);
  }

  /** 只查询是否已配置，不加载密文。 */
  public boolean secretConfigured(String providerId) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM provider_secrets WHERE provider_id=?", Integer.class, providerId)
        > 0;
  }

  /** 按批准过滤条件读取登记模型。 */
  public List<ModelDO> models(boolean includeDisabled) {
    return jdbc.query(
        "SELECT * FROM approved_models "
            + (includeDisabled ? "" : "WHERE enabled=TRUE ")
            + "ORDER BY provider_id,model_id",
        (rs, n) ->
            new ModelDO(
                rs.getString("provider_id"),
                rs.getString("model_id"),
                rs.getString("label"),
                rs.getBoolean("enabled"),
                rs.getString("capabilities"),
                rs.getString("options_json")));
  }

  /** 更新登记模型状态并返回影响行数。 */
  public int approve(String providerId, String modelId, boolean enabled) {
    return jdbc.update(
        "UPDATE approved_models SET enabled=? WHERE provider_id=? AND model_id=?",
        enabled,
        providerId,
        modelId);
  }
}
