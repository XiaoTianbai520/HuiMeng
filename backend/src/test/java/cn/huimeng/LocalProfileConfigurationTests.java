package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.config.JdbcUrlFailureAnalyzer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 仅检查配置装配；不创建数据源，不使用真实开发凭据或连接任何数据库。 */
class LocalProfileConfigurationTests {
  @TempDir Path directory;

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer());
  }

  @Test
  void explicitLocalProfileLoadsTheSelectedPrivateFileAndRetainsSecuritySettings()
      throws Exception {
    Path settings = directory.resolve("development.properties");
    Files.writeString(
        settings,
        "spring.datasource.url=jdbc:h2:mem:configuration_only\n"
            + "spring.datasource.username=synthetic-user\n"
            + "spring.datasource.password=synthetic-password\n");
    runner()
        .withPropertyValues(
            "spring.profiles.active=local",
            "huimeng.local-config=" + settings.toString().replace('\\', '/'))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var environment = context.getEnvironment();
              assertThat(environment.getProperty("spring.datasource.url"))
                  .isEqualTo("jdbc:h2:mem:configuration_only");
              assertThat(environment.getProperty("server.servlet.session.cookie.secure"))
                  .isEqualTo("true");
              assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
            });
  }

  @Test
  void testProfileDoesNotImportLocalDevelopmentConfiguration() {
    runner()
        .withPropertyValues(
            "spring.profiles.active=test", "huimeng.local-config=nonexistent-local-file.properties")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                  .startsWith("jdbc:h2:mem:huimeng;");
            });
  }

  @Test
  void localProfileFailsClearlyWhenItsPrivateFileIsMissing() {
    runner()
        .withPropertyValues(
            "spring.profiles.active=local",
            "huimeng.local-config="
                + directory.resolve("missing.properties").toString().replace('\\', '/'))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void invalidJdbcUrlProducesActionableGuidanceWithoutConnectionValues() {
    var failure = new IllegalArgumentException("'url' must start with \"jdbc\"");
    var analysis = new JdbcUrlFailureAnalyzer().analyze(failure);
    assertThat(analysis).isNotNull();
    assertThat(analysis.getAction())
        .contains("--spring.profiles.active=local", ".runtime/mysql-dev/application.properties");
    assertThat(analysis.getDescription()).contains("数据库连接尚未开始");
  }

  @Test
  void unrelatedArgumentFailuresKeepTheirExistingDiagnostics() {
    assertThat(new JdbcUrlFailureAnalyzer().analyze(new IllegalArgumentException("other")))
        .isNull();
  }
}
