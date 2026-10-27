package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * project_media.created_by 同时可能是内部用户 id 与外部客户端 id（MCP/A2A 的 {@code ExternalActor}）。
 *
 * <p>原来的外键只允许 users，外部入口的媒体生成在最后入库时必然违反约束——真实表现是运行走到 「上游已完成」之后直接
 * FAILED，且因为异常被吞掉，客户端只看到「生成失败，请稍后重试」。 这一列是弱引用，去掉外键、保留索引；归属校验仍由服务层按项目与主体做。
 *
 * <p>用 Java 迁移而不是 SQL：V4 里的外键没有命名，MySQL 与 H2 生成的约束名不同， 只能先从元数据里查出来再按方言删除。
 */
public class V11__RelaxProjectMediaCreatorFk extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    DatabaseMetaData meta = connection.getMetaData();
    String table = actualTable(meta, "project_media");
    if (table == null) return;
    String constraint = fkOn(meta, table, "created_by");
    if (constraint == null) return;
    String quote = meta.getIdentifierQuoteString();
    quote = quote == null ? "" : quote.trim();
    boolean mysql = meta.getDatabaseProductName().toLowerCase().contains("mysql");
    String sql =
        "ALTER TABLE "
            + quoted(quote, table)
            + (mysql ? " DROP FOREIGN KEY " : " DROP CONSTRAINT ")
            + quoted(quote, constraint);
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String actualTable(DatabaseMetaData meta, String wanted) throws Exception {
    try (ResultSet tables = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
      while (tables.next()) {
        String name = tables.getString("TABLE_NAME");
        if (wanted.equalsIgnoreCase(name)) return name;
      }
    }
    return null;
  }

  private static String fkOn(DatabaseMetaData meta, String table, String column) throws Exception {
    try (ResultSet keys = meta.getImportedKeys(null, null, table)) {
      while (keys.next()) {
        if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME")))
          return keys.getString("FK_NAME");
      }
    }
    return null;
  }

  private static String quoted(String quote, String name) {
    return quote.isEmpty() ? name : quote + name + quote;
  }
}
