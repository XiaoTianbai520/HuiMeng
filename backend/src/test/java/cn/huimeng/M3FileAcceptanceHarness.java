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

/** 显式选择的 Linux 文件写入 HTTP 验证；独立 H2/随机工作区，不连接业务资源。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {"server.port=18090", "server.address=127.0.0.1"})
@ActiveProfiles("test")
public class M3FileAcceptanceHarness {
  static final Path ROOT =
      Path.of("target/validation/m3-files-" + UUID.randomUUID()).toAbsolutePath();

  @DynamicPropertySource
  static void fixture(DynamicPropertyRegistry properties) throws Exception {
    if (!"true".equals(System.getenv("HM_M3_FILE_ACCEPTANCE"))
        || !System.getProperty("os.name").equals("Linux"))
      throw new IllegalStateException("Explicit isolated Linux file acceptance opt-in required");
    Path config = Files.createDirectories(ROOT.resolve("config"));
    for (String name : List.of("master.key", "internal.key")) {
      byte[] key = new byte[32];
      new java.security.SecureRandom().nextBytes(key);
      Files.writeString(config.resolve(name), Base64.getEncoder().encodeToString(key));
    }
    Path password = config.resolve("bootstrap-password");
    Files.writeString(password, "synthetic-m3-files-only");
    properties.add("huimeng.workspace-root", () -> ROOT.resolve("workspaces").toString());
    properties.add("huimeng.master-key-file", () -> config.resolve("master.key").toString());
    properties.add("huimeng.internal-key-file", () -> config.resolve("internal.key").toString());
    properties.add("HM_BOOTSTRAP_USERNAME", () -> "m3-admin");
    properties.add("HM_BOOTSTRAP_PASSWORD_FILE", password::toString);
  }

  @Test
  void realFileWriteBoundary() throws Exception {
    if ("true".equals(System.getenv("HM_M3_EXTERNAL_PYTHON"))) {
      Files.writeString(Path.of("target/m3-file.ready"), ROOT.toString());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
      while (!Files.exists(ROOT.resolve("result.json")) && System.nanoTime() < deadline)
        Thread.sleep(250);
      assertThat(Files.exists(ROOT.resolve("result.json"))).isTrue();
      var result =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(ROOT.resolve("result.json").toFile());
      assertThat(result.path("passed").asBoolean()).isTrue();
      return;
    }
    Process process =
        new ProcessBuilder(
                "python3",
                "../docs/validation/scripts/m3FileWriteBoundary.py",
                "--fixture-root",
                ROOT.toString(),
                "--report",
                ROOT.resolve("result.json").toString(),
                "--isolated-h2-fixture")
            .inheritIO()
            .start();
    try {
      assertThat(process.waitFor(180, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
