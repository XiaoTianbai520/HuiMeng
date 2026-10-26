package cn.huimeng.dao.mapper;

import cn.huimeng.dao.entity.MediaProviderDO;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 自定义媒体供应商登记表；来源文本只在服务端保存，不随列表下发。 */
@Repository
public class MediaProviderMapper {
  private static final String META =
      "file_name,display_name,version,source,models_json,sha256,created_at,updated_at";
  private static final RowMapper<MediaProviderDO> ROW =
      (rs, n) ->
          new MediaProviderDO(
              rs.getString("file_name"),
              rs.getString("display_name"),
              rs.getString("version"),
              rs.getString("source"),
              rs.getString("models_json"),
              rs.getString("sha256"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());

  private final JdbcTemplate jdbc;

  public MediaProviderMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<MediaProviderDO> list() {
    return jdbc.query("SELECT " + META + " FROM media_providers ORDER BY file_name", ROW);
  }

  public List<MediaProviderDO> listWithoutSource() {
    return jdbc.query(
        "SELECT file_name,display_name,version,'' AS source,models_json,sha256,created_at,updated_at"
            + " FROM media_providers ORDER BY file_name",
        ROW);
  }

  public MediaProviderDO find(String fileName) {
    List<MediaProviderDO> rows =
        jdbc.query("SELECT " + META + " FROM media_providers WHERE file_name=?", ROW, fileName);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void insert(MediaProviderDO provider) {
    jdbc.update(
        "INSERT INTO media_providers(" + META + ") VALUES (?,?,?,?,?,?,?,?)",
        provider.fileName(),
        provider.displayName(),
        provider.version(),
        provider.source(),
        provider.modelsJson(),
        provider.sha256(),
        Timestamp.from(provider.createdAt()),
        Timestamp.from(provider.updatedAt()));
  }

  public void update(MediaProviderDO provider) {
    jdbc.update(
        "UPDATE media_providers SET display_name=?,version=?,source=?,models_json=?,sha256=?,"
            + "updated_at=? WHERE file_name=?",
        provider.displayName(),
        provider.version(),
        provider.source(),
        provider.modelsJson(),
        provider.sha256(),
        Timestamp.from(provider.updatedAt()),
        provider.fileName());
  }

  public void updateModels(String fileName, String modelsJson) {
    jdbc.update(
        "UPDATE media_providers SET models_json=?,updated_at=? WHERE file_name=?",
        modelsJson,
        Timestamp.from(java.time.Instant.now()),
        fileName);
  }

  public void remove(String fileName) {
    jdbc.update("DELETE FROM media_providers WHERE file_name=?", fileName);
  }

  public void clear() {
    jdbc.update("DELETE FROM media_providers");
  }
}
