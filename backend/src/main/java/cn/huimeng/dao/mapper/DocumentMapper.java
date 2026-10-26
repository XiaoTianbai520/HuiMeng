package cn.huimeng.dao.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 团队级文档读写（个性化说明、本地记忆、A2A 设置）；按名称唯一，带内容摘要作修订号。 */
@Repository
public class DocumentMapper {
  private final JdbcTemplate jdbc;

  public DocumentMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Document(String content, String revision) {}

  public Document find(String name) {
    var rows =
        jdbc.query(
            "SELECT content,revision FROM app_documents WHERE name=?",
            (rs, n) -> new Document(rs.getString("content"), rs.getString("revision")),
            name);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void save(String name, String content, String revision) {
    int updated =
        jdbc.update(
            "UPDATE app_documents SET content=?,revision=?,updated_at=? WHERE name=?",
            content,
            revision,
            Timestamp.from(Instant.now()),
            name);
    if (updated == 0)
      jdbc.update(
          "INSERT INTO app_documents(name,content,revision,updated_at) VALUES (?,?,?,?)",
          name,
          content,
          revision,
          Timestamp.from(Instant.now()));
  }

  public void delete(String name) {
    jdbc.update("DELETE FROM app_documents WHERE name=?", name);
  }

  public List<String> names() {
    return jdbc.query("SELECT name FROM app_documents ORDER BY name", (rs, n) -> rs.getString(1));
  }
}
