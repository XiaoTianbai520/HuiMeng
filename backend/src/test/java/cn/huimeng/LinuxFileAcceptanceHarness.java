package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Linux 上显式选择的真实 HTTP/文件验收；专属随机目录、H2 和合成账号。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {"server.port=18087", "server.address=127.0.0.1"})
@ActiveProfiles("test")
public class LinuxFileAcceptanceHarness {
  static final Path ROOT =
      Path.of("target/validation/m2-file-" + UUID.randomUUID()).toAbsolutePath();

  @DynamicPropertySource
  static void fixture(DynamicPropertyRegistry properties) throws Exception {
    if (!"true".equals(System.getenv("HM_FILE_ACCEPTANCE"))
        || !System.getProperty("os.name").equals("Linux"))
      throw new IllegalStateException("Explicit Linux file acceptance opt-in required");
    Path config = Files.createDirectories(ROOT.resolve("config"));
    for (String name : List.of("master.key", "internal.key")) {
      byte[] key = new byte[32];
      new java.security.SecureRandom().nextBytes(key);
      Files.writeString(config.resolve(name), Base64.getEncoder().encodeToString(key));
    }
    Path password = config.resolve("bootstrap-password");
    Files.writeString(password, "synthetic-linux-file-acceptance-only");
    properties.add("huimeng.workspace-root", () -> ROOT.resolve("workspaces").toString());
    properties.add("huimeng.master-key-file", () -> config.resolve("master.key").toString());
    properties.add("huimeng.internal-key-file", () -> config.resolve("internal.key").toString());
    properties.add("HM_BOOTSTRAP_USERNAME", () -> "file-admin");
    properties.add("HM_BOOTSTRAP_PASSWORD_FILE", password::toString);
  }

  @Test
  void realLinuxFileBoundary() throws Exception {
    Process process =
        new ProcessBuilder(
                "python3",
                "../docs/validation/scripts/m2FileBoundary.py",
                "--base",
                "http://127.0.0.1:18087",
                "--fixture-root",
                ROOT.toString(),
                "--report",
                ROOT.resolve("result.json").toString(),
                "--isolated-h2-fixture")
            .inheritIO()
            .start();
    try {
      assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
