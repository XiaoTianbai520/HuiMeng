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
      "huimeng.public-origin=http://localhost:5187"
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
    SecurityContextHolder.clearContext();
    Path stop = Path.of("target/browser-harness.stop");
    Files.deleteIfExists(stop);
    Files.writeString(
        Path.of("target/browser-harness.ready"), "Synthetic real Spring/H2 browser fixture ready");
    long deadline = Instant.now().getEpochSecond() + 1800;
    while (!Files.exists(stop) && Instant.now().getEpochSecond() < deadline) Thread.sleep(1000);
  }
}
