package cn.huimeng.dao.mapper;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.CursorPageDO;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 绑定授权范围和锚点的游标查询；表名、列名与过滤 SQL 只能由 Mapper 内固定代码传入。 */
@Repository
public class CursorMapper {
  private final JdbcTemplate jdbc;

  public CursorMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  <T> CursorPageDO<T> query(
      String scope,
      String from,
      String idColumn,
      String timeColumn,
      String filter,
      List<Object> parameters,
      String cursor,
      int limit,
      RowMapper<T> rowMapper,
      Function<T, String> identify) {
    if (limit < 1 || limit > 50) throw ApiException.invalid();
    var args = new ArrayList<>(parameters);
    String predicate = filter;
    if (cursor != null) {
      if (cursor.length() > 1024) throw ApiException.invalid();
      String decoded;
      try {
        decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException ex) {
        throw ApiException.invalid();
      }
      String prefix = scope + "\n";
      if (!decoded.startsWith(prefix)
          || !decoded.substring(prefix.length()).matches("[A-Za-z0-9_-]{1,128}"))
        throw ApiException.invalid();
      String anchor = decoded.substring(prefix.length());
      var anchorArgs = new ArrayList<>(parameters);
      anchorArgs.add(anchor);
      var rows =
          jdbc.queryForList(
              "SELECT "
                  + timeColumn
                  + " AS anchor_time FROM "
                  + from
                  + " WHERE "
                  + filter
                  + " AND "
                  + idColumn
                  + "=?",
              anchorArgs.toArray());
      if (rows.size() != 1) throw ApiException.invalid();
      Object time = rows.get(0).get("anchor_time");
      predicate += " AND (" + timeColumn + ">? OR (" + timeColumn + "=? AND " + idColumn + ">?))";
      args.add(time);
      args.add(time);
      args.add(anchor);
    }
    args.add(limit + 1);
    var rows =
        jdbc.query(
            "SELECT * FROM "
                + from
                + " WHERE "
                + predicate
                + " ORDER BY "
                + timeColumn
                + ","
                + idColumn
                + " LIMIT ?",
            rowMapper,
            args.toArray());
    boolean more = rows.size() > limit;
    if (more) rows.remove(rows.size() - 1);
    String next =
        more
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (scope + "\n" + identify.apply(rows.get(rows.size() - 1)))
                        .getBytes(StandardCharsets.UTF_8))
            : null;
    return new CursorPageDO<>(List.copyOf(rows), next);
  }
}
