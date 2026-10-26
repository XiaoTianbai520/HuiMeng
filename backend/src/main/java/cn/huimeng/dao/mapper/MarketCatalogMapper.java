package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.MarketCatalogDO;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 市场目录与获批制品清单：搜索结果落库，安装只按这里的地址与摘要取件。 */
@Repository
public class MarketCatalogMapper {
  private static final String META =
      "plugin_id,type,name,display_name,version,file_name,artifact_url,sha256,updated_at";
  private static final RowMapper<MarketCatalogDO> ROW =
      (rs, n) ->
          new MarketCatalogDO(
              rs.getString("plugin_id"),
              rs.getString("type"),
              rs.getString("name"),
              rs.getString("display_name"),
              rs.getString("version"),
              rs.getString("file_name"),
              rs.getString("artifact_url"),
              rs.getString("sha256"),
              rs.getTimestamp("updated_at").toInstant());

  private final JdbcTemplate jdbc;

  public MarketCatalogMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 目录条目以最新一次搜索结果为准，但已获批的摘要只增不改。 */
  public void upsert(MarketCatalogDO entry) {
    if (find(entry.pluginId()) == null) {
      jdbc.update(
          "INSERT INTO market_catalog(" + META + ") VALUES (?,?,?,?,?,?,?,?,?)",
          entry.pluginId(),
          entry.type(),
          entry.name(),
          entry.displayName(),
          entry.version(),
          entry.fileName(),
          entry.artifactUrl(),
          entry.sha256(),
          Timestamp.from(entry.updatedAt()));
      return;
    }
    jdbc.update(
        "UPDATE market_catalog SET type=?,name=?,display_name=?,version=?,file_name=?,"
            + "artifact_url=?,updated_at=? WHERE plugin_id=?",
        entry.type(),
        entry.name(),
        entry.displayName(),
        entry.version(),
        entry.fileName(),
        entry.artifactUrl(),
        Timestamp.from(entry.updatedAt()),
        entry.pluginId());
  }

  public MarketCatalogDO find(String pluginId) {
    List<MarketCatalogDO> rows =
        jdbc.query("SELECT " + META + " FROM market_catalog WHERE plugin_id=?", ROW, pluginId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 首次成功下载即视为获批；之后同一 pluginId 必须匹配该摘要。 */
  public void approve(String pluginId, String sha256) {
    jdbc.update(
        "UPDATE market_catalog SET sha256=?,updated_at=? WHERE plugin_id=? AND sha256 IS NULL",
        sha256,
        Timestamp.from(Instant.now()),
        pluginId);
  }

  public void clear() {
    jdbc.update("DELETE FROM market_catalog");
  }
}
