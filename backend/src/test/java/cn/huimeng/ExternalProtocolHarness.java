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

/**
 * 外部协议验收夹具：两个项目、两个单项目凭证，一个合成文本供应商。
 *
 * <p>只用于隔离环境；令牌只写入 target 下的就绪文件，不进入仓库或证据。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=${hm.external.port:18099}",
      "server.address=127.0.0.1",
      "huimeng.public-origin=http://localhost:5187",
      "huimeng.storage.enabled=false",
      "huimeng.comfy.enabled=false",
      "huimeng.llm.enabled=${hm.llm.enabled:false}",
      "huimeng.llm.deepseek-endpoint=${hm.llm.deepseek-endpoint:http://127.0.0.1:18100}",
      "huimeng.llm.qwen-endpoint=${hm.llm.qwen-endpoint:http://127.0.0.1:18100}"
    })
@ActiveProfiles("test")
public class ExternalProtocolHarness {
  static final Path ROOT =
      Path.of("target/validation/external-protocol-" + UUID.randomUUID()).toAbsolutePath();
  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired ConfigurationService configuration;
  @Autowired ExternalClientService externalClients;

  @DynamicPropertySource
  static void fixture(DynamicPropertyRegistry properties) throws Exception {
    // 夹具只用 H2、target 临时目录与随机密钥，不依赖平台特性；但仍需显式开关，避免误连真实库。
    if (!"true".equals(System.getenv("HM_EXTERNAL_PROTOCOL")))
      throw new IllegalStateException("Explicit external protocol opt-in required");
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
  void externalProtocolFixtureUntilStopped() throws Exception {
    String password = "M3-external-fixture-only";
    users.bootstrap("ext-admin", password);
    Actor admin = users.authenticate("ext-admin", password);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    var member = users.create(new CreateUserReqDTO("ext-member", password, "USER"), "external");
    var alpha = projects.create(new CreateProjectReqDTO("外部协议验收"), "external");
    var beta = projects.create(new CreateProjectReqDTO("外部协议隔离"), "external");
    for (var project : List.of(alpha, beta)) {
      projects.addMember(project.id(), admin.id(), "external");
      projects.addMember(project.id(), member.id(), "external");
    }
    Path alphaRoot = projects.workspaceRoot(alpha.id(), true);
    Files.writeString(alphaRoot.resolve("欢迎.md"), "# 外部协议\n\nAlpha 项目的合成文档。\n");
    Path betaRoot = projects.workspaceRoot(beta.id(), true);
    Files.writeString(betaRoot.resolve("私有.md"), "# 隔离\n\nBeta 项目的独占文档。\n");
    configuration.setSecret("deepseek", "fixture-llm-key", "external");
    configuration.approve(new ModelApprovalReqDTO("deepseek", "deepseek-chat", true), "external");
    var alphaClient =
        externalClients.issue(
            new IssueExternalClientReqDTO(
                "alpha-client",
                alpha.id(),
                List.of(
                    "getAppState",
                    "getCanvas",
                    "addCanvas",
                    "switchCanvas",
                    "listWorkspaceFiles",
                    "readWorkspaceFile",
                    "saveWorkspaceFile",
                    "listMedia",
                    "getRun",
                    "listTeams"),
                List.of("alphaTeam")),
            "external");
    var betaClient =
        externalClients.issue(
            new IssueExternalClientReqDTO(
                "beta-client",
                beta.id(),
                List.of("getAppState", "readWorkspaceFile", "listTeams"),
                // 同名团队在另一项目也授权，用于验证任务归属本身不会被 ID 猜测越过。
                List.of("betaTeam", "alphaTeam")),
            "external");
    SecurityContextHolder.clearContext();
    Path stop = Path.of("target/external-protocol.stop");
    Files.deleteIfExists(stop);
    var ready = new LinkedHashMap<String, Object>();
    ready.put("port", System.getProperty("hm.external.port", "18099"));
    ready.put("alphaProject", alpha.id());
    ready.put("betaProject", beta.id());
    ready.put("alphaToken", alphaClient.token());
    ready.put("betaToken", betaClient.token());
    ready.put("alphaClientId", alphaClient.id());
    ready.put("betaClientId", betaClient.id());
    ready.put("adminUser", "ext-admin");
    ready.put("adminPassword", password);
    ready.put("fixtureRoot", ROOT.toString());
    Files.writeString(
        Path.of("target/external-protocol.ready"),
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ready));
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
