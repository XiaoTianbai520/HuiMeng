package cn.huimeng.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** JDBC URL 未加载或格式错误时提供安全的启动指引，不输出实际连接信息。 */
public class JdbcUrlFailureAnalyzer extends AbstractFailureAnalyzer<IllegalArgumentException> {
  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, IllegalArgumentException cause) {
    if (!"'url' must start with \"jdbc\"".equals(cause.getMessage())) return null;
    return new FailureAnalysis(
        "未加载有效的 spring.datasource.url，数据库连接尚未开始。",
        "本机 MySQL 调试：工作目录选择 backend，使用“绘梦 MySQL 本地”运行配置，"
            + "或添加 --spring.profiles.active=local。当前开发配置使用独立 MySQL TLS 连接。"
            + "本机配置应在 .runtime/mysql-dev/application.properties；不要将凭据填入源码。"
            + "部署入口请通过受控配置提供有效的 HM_DB_URL、HM_DB_USER、HM_DB_PASSWORD。",
        cause);
  }
}
