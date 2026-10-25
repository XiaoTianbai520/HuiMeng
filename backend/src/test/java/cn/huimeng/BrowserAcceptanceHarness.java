package cn.huimeng;

import cn.huimeng.dto.req.*;
import cn.huimeng.security.Actor;
import cn.huimeng.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** Opt-in, loopback-only real Spring/H2 browser harness with explicitly synthetic credentials. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=18086",
      "server.address=127.0.0.1",
      "huimeng.public-origin=http://localhost:5187",
      "spring.config.import=optional:file:.runtime/object-storage/application.properties"
    })
@ActiveProfiles("test")
public class BrowserAcceptanceHarness {
  static {
    try {
      Path keys = Path.of("target/test-secrets");
      Files.createDirectories(keys);
      for (String name : List.of("master.key", "internal.key"))
        if (!Files.exists(keys.resolve(name))) {
          byte[] key = new byte[32];
          new java.security.SecureRandom().nextBytes(key);
          Files.writeString(keys.resolve(name), Base64.getEncoder().encodeToString(key));
        }
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired ProjectMediaService media;
  @Autowired ConfigurationService configuration;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired cn.huimeng.common.storage.ObjectStorage storage;

  @org.springframework.beans.factory.annotation.Value("${huimeng.storage.enabled:false}")
  boolean storageEnabled;

  @Test
  void serveSyntheticBrowserAcceptanceUntilStopped() throws Exception {
    if (!"true".equals(System.getenv("HM_BROWSER_ACCEPTANCE")))
      throw new IllegalStateException("Explicit browser harness opt-in required");
    String password = "M2-browser-fixture-only";
    users.bootstrap("ui-admin", password);
    Actor admin = users.authenticate("ui-admin", password);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    cn.huimeng.dto.resp.UserRespDTO employee =
        users.create(new CreateUserReqDTO("ui-member", password, "USER"), "browser-harness");
    users.create(new CreateUserReqDTO("ui-outsider", password, "USER"), "browser-harness");
    var project = projects.create(new CreateProjectReqDTO("浏览器验收项目"), "browser-harness");
    projects.create(new CreateProjectReqDTO("仅管理员管理的项目"), "browser-harness");
    projects.addMember(project.id(), employee.id(), "browser-harness");
    if (storageEnabled) {
      projects.addMember(project.id(), admin.id(), "browser-harness");
      byte[] image =
          Files.readAllBytes(
              Path.of("../docs/validation/fixtures/sample-project/assets/M0-sample.png"));
      media.upload(
          project.id(),
          "验收素材.png",
          image.length,
          new java.io.ByteArrayInputStream(image),
          "browser-harness");
      configuration.approve(
          new ModelApprovalReqDTO("qwenImage21Local", "qwenImage21Text", true), "browser-harness");
    }
    SecurityContextHolder.clearContext();
    Path stop = Path.of("target/browser-harness.stop");
    Files.deleteIfExists(stop);
    Files.writeString(
        Path.of("target/browser-harness.ready"), "Synthetic real Spring/H2 browser fixture ready");
    long deadline = Instant.now().getEpochSecond() + 7200;
    try {
      while (!Files.exists(stop) && Instant.now().getEpochSecond() < deadline) Thread.sleep(1000);
    } finally {
      if (storageEnabled) {
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM generation_runs WHERE capacity_held=TRUE", Integer.class)
            > 0)
          throw new IllegalStateException(
              "Wait for owned validation runs before stopping this fixture");
        for (String key : jdbc.queryForList("SELECT object_key FROM project_media", String.class))
          storage.delete(key);
      }
    }
  }
}
