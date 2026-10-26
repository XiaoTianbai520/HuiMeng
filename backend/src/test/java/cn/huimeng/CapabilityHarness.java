package cn.huimeng;

import cn.huimeng.dto.req.CreateProjectReqDTO;
import cn.huimeng.dto.req.CreateUserReqDTO;
import cn.huimeng.security.Actor;
import cn.huimeng.service.PluginService;
import cn.huimeng.service.ProjectService;
import cn.huimeng.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
 * 能力目录浏览器夹具：真实 Spring 入口 + 已安装的合成节点/工具/技能。
 *
 * <p>用于验证页面确实取回并执行节点脚本；不连接真实模型、对象存储或市场。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=${hm.capability.port:18097}",
      // 默认只绑回环；跨机验证（前端在另一台机器）时用 HM_BIND_ADDRESS 指定可达地址。
      "server.address=${HM_BIND_ADDRESS:127.0.0.1}",
      "huimeng.public-origin=http://localhost:5187",
      "huimeng.storage.enabled=false",
      "huimeng.comfy.enabled=false",
      "huimeng.llm.enabled=false",
      // 默认指向不存在的端口，保证常规测试不打外网；只有显式给 HM_MARKET_ENDPOINT 时才连真实市场。
      "huimeng.market.endpoint=${HM_MARKET_ENDPOINT:http://127.0.0.1:1}"
    })
@ActiveProfiles("test")
public class CapabilityHarness {
  static final Path ROOT =
      Path.of("target/validation/capability-" + UUID.randomUUID()).toAbsolutePath();
  static final String NODE_SOURCE =
      "// @version 1.3.0\n"
          + "(function () {\n"
          + "  window.toonflowNodes = window.toonflowNodes || {};\n"
          + "  window.toonflowNodes.demoNode = { name: 'demoNode', render: function () { return null; } };\n"
          + "  window.__hmDemoNodeLoaded = true;\n"
          + "})();\n";
  static final String TOOL_SOURCE =
      "/*! toonflowTool:{\"name\":\"mediaGeneration\",\"version\":\"1.0.0\",\"displayName\":\"媒体生成\","
          + "\"description\":\"夹具工具包\",\"author\":\"fixture\",\"github\":\"\","
          + "\"components\":[\"mediaGeneration\"],\"configRules\":[]} */\n"
          + "/*! toonflowToolClient:{\"code\":\"window.toonflowToolViews = window.toonflowToolViews"
          + " || {};\",\"css\":\"\"} */\n"
          + "export default { validateConfig() { return {}; }, createTools() { return []; } };\n";
  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired PluginService plugins;

  @DynamicPropertySource
  static void fixture(DynamicPropertyRegistry properties) throws Exception {
    if (!"true".equals(System.getenv("HM_CAPABILITY")))
      throw new IllegalStateException("Explicit capability opt-in required");
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
  void capabilityFixtureUntilStopped() throws Exception {
    String password = "M3-capability-fixture-only";
    users.bootstrap("cap-admin", password);
    Actor admin = users.authenticate("cap-admin", password);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    var member = users.create(new CreateUserReqDTO("cap-member", password, "USER"), "capability");
    var project = projects.create(new CreateProjectReqDTO("能力目录验收"), "capability");
    projects.addMember(project.id(), admin.id(), "capability");
    projects.addMember(project.id(), member.id(), "capability");
    projects.workspaceRoot(project.id(), true);
    plugins.installFile(
        "node",
        "demoNode.umd.js",
        NODE_SOURCE.getBytes(StandardCharsets.UTF_8),
        false,
        "capability");
    plugins.installFile(
        "tool",
        "mediaGeneration.tool.js",
        TOOL_SOURCE.getBytes(StandardCharsets.UTF_8),
        false,
        "capability");
    plugins.installFile(
        "skill",
        "demoSkill.md",
        "# 演示技能\n\n用于能力目录验收。\n".getBytes(StandardCharsets.UTF_8),
        false,
        "capability");
    SecurityContextHolder.clearContext();
    Path stop = Path.of("target/capability.stop");
    Files.deleteIfExists(stop);
    var ready = new LinkedHashMap<String, Object>();
    ready.put("port", System.getProperty("hm.capability.port", "18097"));
    ready.put("project", project.id());
    ready.put("projectName", "能力目录验收");
    ready.put("adminUser", "cap-admin");
    ready.put("memberUser", "cap-member");
    ready.put("password", password);
    ready.put("fixtureRoot", ROOT.toString());
    Files.writeString(
        Path.of("target/capability.ready"), new ObjectMapper().writeValueAsString(ready));
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
