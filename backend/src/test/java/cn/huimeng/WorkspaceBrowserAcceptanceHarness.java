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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 显式 Linux/H2 浏览器夹具；禁用真实存储和模型，不导入工作机 .runtime。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=${hm.browser.port:18091}",
      "server.address=127.0.0.1",
      "huimeng.public-origin=http://localhost:5187",
      "huimeng.storage.enabled=false",
      "huimeng.comfy.enabled=false",
      "huimeng.llm.enabled=${hm.llm.enabled:false}",
      "huimeng.llm.deepseek-endpoint=${hm.llm.deepseek-endpoint:http://127.0.0.1:18100}",
      "huimeng.llm.qwen-endpoint=${hm.llm.qwen-endpoint:http://127.0.0.1:18100}"
    })
@ActiveProfiles("test")
public class WorkspaceBrowserAcceptanceHarness {
  static final Path ROOT =
      Path.of("target/validation/workspace-browser-" + UUID.randomUUID()).toAbsolutePath();
  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired ConfigurationService configuration;

  @DynamicPropertySource
  static void fixture(DynamicPropertyRegistry properties) throws Exception {
    if (!"true".equals(System.getenv("HM_WORKSPACE_BROWSER"))
        || !System.getProperty("os.name").equals("Linux"))
      throw new IllegalStateException("Explicit Linux workspace browser opt-in required");
    Path config = Files.createDirectories(ROOT.resolve("config"));
    for (String name : List.of("master.key", "internal.key")) {
      byte[] key = new byte[32];
      new java.security.SecureRandom().nextBytes(key);
      Files.writeString(config.resolve(name), Base64.getEncoder().encodeToString(key));
    }
    properties.add("huimeng.workspace-root", () -> ROOT.resolve("workspaces").toString());
    properties.add("huimeng.master-key-file", () -> config.resolve("master.key").toString());
    properties.add("huimeng.internal-key-file", () -> config.resolve("internal.key").toString());
  }

  @Test
  void browserFixtureUntilStopped() throws Exception {
    String password = "M3-browser-fixture-only";
    users.bootstrap("ui-admin", password);
    Actor admin = users.authenticate("ui-admin", password);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    var member =
        users.create(new CreateUserReqDTO("ui-member", password, "USER"), "workspace-browser");
    users.create(new CreateUserReqDTO("ui-outsider", password, "USER"), "workspace-browser");
    var project = projects.create(new CreateProjectReqDTO("浏览器文档验收"), "workspace-browser");
    projects.create(new CreateProjectReqDTO("未授权项目"), "workspace-browser");
    projects.addMember(project.id(), admin.id(), "workspace-browser");
    projects.addMember(project.id(), member.id(), "workspace-browser");
    // 仅夹具：合成密钥与获批文本模型，真实密钥不进入代码或证据。
    configuration.setSecret("deepseek", "fixture-llm-key", "workspace-browser");
    configuration.approve(
        new ModelApprovalReqDTO("deepseek", "deepseek-chat", true), "workspace-browser");
    Path workspace = projects.workspaceRoot(project.id(), true);
    Files.writeString(workspace.resolve("欢迎.md"), "# 绘梦验收剧本\n\n这是隔离环境中的合成文档。\n");
    SecurityContextHolder.clearContext();
    Path stop = Path.of("target/workspace-browser.stop");
    Files.deleteIfExists(stop);
    Files.writeString(
        Path.of("target/workspace-browser.ready"),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("projectId", project.id(), "fixtureRoot", ROOT.toString())));
    long deadline = Instant.now().getEpochSecond() + 7200;
    try {
      while (!Files.exists(stop) && Instant.now().getEpochSecond() < deadline) Thread.sleep(500);
    } finally {
      try (var paths = Files.walk(ROOT)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
}
