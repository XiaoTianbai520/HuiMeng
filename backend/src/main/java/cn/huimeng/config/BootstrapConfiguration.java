package cn.huimeng.config;

import cn.huimeng.service.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A deployment-mounted secret file initializes the first administrator; no public setup endpoint.
 */
@Configuration
public class BootstrapConfiguration {
  @Bean
  ApplicationRunner bootstrap(
      UserService users,
      @Value("${HM_BOOTSTRAP_USERNAME:}") String username,
      @Value("${HM_BOOTSTRAP_PASSWORD_FILE:}") String file) {
    return args -> {
      if (!users.initialized() && !username.isEmpty() && !file.isEmpty())
        users.bootstrap(username, Files.readString(Path.of(file)).stripTrailing());
    };
  }
}
