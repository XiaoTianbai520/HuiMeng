package cn.huimeng;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The only public business application; creative routes remain closed during M2. */
@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class HuiMengApplication {
  public static void main(String[] args) {
    if (java.util.Arrays.asList(args).contains("--migrate-only")) {
      org.flywaydb.core.Flyway.configure()
          .dataSource(
              System.getenv("HM_DB_URL"),
              System.getenv("HM_MIGRATION_USER"),
              System.getenv("HM_MIGRATION_PASSWORD"))
          .cleanDisabled(true)
          .baselineOnMigrate(false)
          .load()
          .migrate();
      return;
    }
    SpringApplication.run(HuiMengApplication.class, args);
  }
}
