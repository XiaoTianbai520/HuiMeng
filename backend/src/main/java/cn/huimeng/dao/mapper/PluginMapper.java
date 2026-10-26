package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.PluginDO;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 能力登记表读写；导出才读取 BLOB，列表不把内容带进内存。 */
@Repository
public class PluginMapper {
  private final JdbcTemplate jdbc;

  public PluginMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(PluginDO plugin) {
    jdbc.update(
        "INSERT INTO plugins(id,type,name,display_name,version,sha256,source,source_url,enabled,config_json,manifest_json,content,installed_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        plugin.id(),
        plugin.type(),
        plugin.name(),
        plugin.displayName(),
        plugin.version(),
        plugin.sha256(),
        plugin.source(),
        plugin.sourceUrl(),
        plugin.enabled(),
        plugin.configJson(),
        plugin.manifestJson(),
        plugin.content(),
        plugin.installedBy(),
        Timestamp.from(plugin.createdAt()),
        Timestamp.from(plugin.updatedAt()));
  }

  public void replace(PluginDO plugin) {
    jdbc.update(
        "UPDATE plugins SET display_name=?,version=?,sha256=?,source=?,source_url=?,enabled=?,config_json=?,manifest_json=?,content=?,installed_by=?,updated_at=? WHERE id=?",
        plugin.displayName(),
        plugin.version(),
        plugin.sha256(),
        plugin.source(),
        plugin.sourceUrl(),
        plugin.enabled(),
        plugin.configJson(),
        plugin.manifestJson(),
        plugin.content(),
        plugin.installedBy(),
        Timestamp.from(plugin.updatedAt()),
        plugin.id());
  }

  public void enabled(String id, boolean enabled) {
    jdbc.update(
        "UPDATE plugins SET enabled=?,updated_at=? WHERE id=?",
        enabled,
        Timestamp.from(Instant.now()),
        id);
  }

  public void config(String id, String configJson) {
    jdbc.update(
        "UPDATE plugins SET config_json=?,updated_at=? WHERE id=?",
        configJson,
        Timestamp.from(Instant.now()),
        id);
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM plugin_documents WHERE plugin_id=?", id);
    jdbc.update("DELETE FROM plugins WHERE id=?", id);
  }

  /** 列表不带内容列，避免把大文件读进每次列表请求。 */
  public List<PluginDO> list() {
    return jdbc.query("SELECT " + META + " FROM plugins ORDER BY type,name", META_ROW);
  }

  public PluginDO find(String type, String name) {
    var rows =
        jdbc.query(
            "SELECT " + META + " FROM plugins WHERE type=? AND name=?", META_ROW, type, name);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 导出时单独读取原始内容。 */
  public byte[] content(String id) {
    var rows =
        jdbc.query("SELECT content FROM plugins WHERE id=?", (rs, n) -> rs.getBytes("content"), id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void documents(String pluginId, Map<String, String> documents) {
    jdbc.update("DELETE FROM plugin_documents WHERE plugin_id=?", pluginId);
    documents.forEach(
        (path, content) ->
            jdbc.update(
                "INSERT INTO plugin_documents(plugin_id,path,content) VALUES (?,?,?)",
                pluginId,
                path,
                content));
  }

  public Map<String, String> documents(String pluginId) {
    var result = new LinkedHashMap<String, String>();
    jdbc.query(
        "SELECT path,content FROM plugin_documents WHERE plugin_id=? ORDER BY path",
        rs -> {
          result.put(rs.getString("path"), rs.getString("content"));
        },
        pluginId);
    return result;
  }

  public boolean collected(String pluginId) {
    var rows =
        jdbc.query("SELECT 1 FROM market_collections WHERE plugin_id=?", (rs, n) -> 1, pluginId);
    return !rows.isEmpty();
  }

  public List<String> collections() {
    return jdbc.query(
        "SELECT plugin_id FROM market_collections ORDER BY plugin_id", (rs, n) -> rs.getString(1));
  }

  public boolean toggleCollection(String pluginId) {
    if (collected(pluginId)) {
      jdbc.update("DELETE FROM market_collections WHERE plugin_id=?", pluginId);
      return false;
    }
    jdbc.update(
        "INSERT INTO market_collections(plugin_id,created_at) VALUES (?,?)",
        pluginId,
        Timestamp.from(Instant.now()));
    return true;
  }

  private static final String META =
      "id,type,name,display_name,version,sha256,source,source_url,enabled,config_json,manifest_json,installed_by,created_at,updated_at";

  private static final RowMapper<PluginDO> META_ROW =
      (rs, n) ->
          new PluginDO(
              rs.getString("id"),
              rs.getString("type"),
              rs.getString("name"),
              rs.getString("display_name"),
              rs.getString("version"),
              rs.getString("sha256"),
              rs.getString("source"),
              rs.getString("source_url"),
              rs.getBoolean("enabled"),
              rs.getString("config_json"),
              rs.getString("manifest_json"),
              null,
              rs.getString("installed_by"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());
}
