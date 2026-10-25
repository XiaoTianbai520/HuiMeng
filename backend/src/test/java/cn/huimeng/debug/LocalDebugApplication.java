package cn.huimeng.debug;

import cn.huimeng.HuiMengApplication;
import cn.huimeng.dto.req.CreateProjectReqDTO;
import cn.huimeng.dto.req.CreateUserReqDTO;
import cn.huimeng.security.Actor;
import cn.huimeng.service.ProjectService;
import cn.huimeng.service.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 仅在测试类路径启动的本地调试入口；使用内存库、合成身份与回环监听，不读取部署凭据。 IDE 直接 Debug 此 main，工作目录选 backend，参数为 --local-debug。
 */
public final class LocalDebugApplication {
  /** 明确公开的合成密码，只能用于此内存调试环境。 */
  public static final String SYNTHETIC_PASSWORD = "M2-local-debug-fixture-only";

  private LocalDebugApplication() {}

  public static void main(String[] args) throws Exception {
    String[] settings = isolatedArguments(args);
    Files.deleteIfExists(Path.of("target/local-debug.ready"));
    Path keys = Path.of("target/local-debug-secrets");
    Files.createDirectories(keys);
    for (String name : List.of("master.key", "internal.key")) {
      byte[] key = new byte[32];
      new SecureRandom().nextBytes(key);
      Files.writeString(keys.resolve(name), Base64.getEncoder().encodeToString(key));
    }
    SpringApplication application = new SpringApplication(HuiMengApplication.class);
    application.setAdditionalProfiles("local-debug");
    ConfigurableApplicationContext context = application.run(settings);
    try {
      seed(context);
      Files.writeString(
          Path.of("target/local-debug.ready"), "Synthetic loopback debug application ready");
    } catch (Exception ex) {
      context.close();
      throw ex;
    }
  }

  /** 命令行优先级固定隔离配置，即使终端继承 HM_DB_* 也不会连接现有数据库。 */
  static String[] isolatedArguments(String[] args) {
    if (!Arrays.equals(args, new String[] {"--local-debug"})) {
      throw new IllegalArgumentException(
          "Use only --local-debug; deployment overrides are not accepted");
    }
    String database =
        "jdbc:h2:mem:huimeng_local_debug;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    return new String[] {
      "--spring.profiles.active=local-debug",
      "--server.address=127.0.0.1",
      "--server.port=18086",
      "--spring.datasource.url=" + database,
      "--spring.datasource.username=sa",
      "--spring.datasource.password=",
      "--spring.datasource.hikari.connection-init-sql=SELECT 1",
      "--spring.flyway.enabled=true",
      "--spring.flyway.url=" + database,
      "--spring.flyway.user=sa",
      "--spring.flyway.password=",
      "--huimeng.workspace-root=target/local-debug-workspaces",
      "--huimeng.master-key-file=target/local-debug-secrets/master.key",
      "--huimeng.internal-key-file=target/local-debug-secrets/internal.key",
      "--huimeng.public-origin=http://localhost:5187",
      "--HM_BOOTSTRAP_USERNAME=",
      "--HM_BOOTSTRAP_PASSWORD_FILE="
    };
  }

  private static void seed(ConfigurableApplicationContext context) {
    UserService users = context.getBean(UserService.class);
    ProjectService projects = context.getBean(ProjectService.class);
    users.bootstrap("debug-admin", SYNTHETIC_PASSWORD);
    Actor admin = users.authenticate("debug-admin", SYNTHETIC_PASSWORD);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    try {
      var member =
          users.create(
              new CreateUserReqDTO("debug-member", SYNTHETIC_PASSWORD, "USER"), "local-debug");
      users.create(
          new CreateUserReqDTO("debug-outsider", SYNTHETIC_PASSWORD, "USER"), "local-debug");
      var project = projects.create(new CreateProjectReqDTO("本地调试项目"), "local-debug");
      projects.create(new CreateProjectReqDTO("未授权项目"), "local-debug");
      projects.addMember(project.id(), admin.id(), "local-debug");
      projects.addMember(project.id(), member.id(), "local-debug");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
