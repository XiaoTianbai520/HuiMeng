package cn.huimeng.debug;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 调试入口必须明确选择，且数据源、密钥路径和监听范围不能由额外参数替换。 */
class LocalDebugLauncherTests {
  @Test
  void refusesImplicitStartupAndDeploymentOverrides() {
    assertThatThrownBy(() -> LocalDebugApplication.isolatedArguments(new String[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LocalDebugApplication.isolatedArguments(
                    new String[] {"--local-debug", "--server.address=0.0.0.0"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixesEphemeralDatasourceAndLoopbackBoundary() {
    Map<String, String> settings =
        Arrays.stream(LocalDebugApplication.isolatedArguments(new String[] {"--local-debug"}))
            .map(value -> value.substring(2).split("=", 2))
            .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    assertThat(settings.get("spring.datasource.url")).startsWith("jdbc:h2:mem:");
    assertThat(settings.get("spring.flyway.url")).isEqualTo(settings.get("spring.datasource.url"));
    assertThat(settings.get("server.address")).isEqualTo("127.0.0.1");
    assertThat(settings.get("huimeng.public-origin")).isEqualTo("http://localhost:5187");
    assertThat(settings.get("huimeng.master-key-file")).startsWith("target/local-debug-secrets/");
    assertThat(settings.get("HM_BOOTSTRAP_PASSWORD_FILE")).isEmpty();
  }
}
