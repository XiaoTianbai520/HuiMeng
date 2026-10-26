package cn.huimeng;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.dto.req.CreateUserReqDTO;
import cn.huimeng.security.SecretBox;
import cn.huimeng.service.ConfigurationService;
import cn.huimeng.service.UserService;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** M3-08：安装/覆盖/归档越界/配置秘密/导出/卸载与市场代理边界。 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class PluginMarketTests {
  static {
    try {
      Path dir = Path.of("target/test-secrets");
      Files.createDirectories(dir);
      byte[] key = new byte[32];
      new java.security.SecureRandom().nextBytes(key);
      Files.writeString(dir.resolve("master.key"), Base64.getEncoder().encodeToString(key));
      Files.writeString(dir.resolve("internal.key"), Base64.getEncoder().encodeToString(key));
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  static final String ORIGIN = "https://localhost:18443",
      PASSWORD = "synthetic-account-password-42";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserService users;
  @Autowired ConfigurationService configuration;
  @Autowired SecretBox box;
  MockHttpSession admin;
  String adminId;

  @BeforeEach
  void setup() throws Exception {
    for (String table :
        List.of(
            "plugin_documents",
            "plugins",
            "market_collections",
            "app_documents",
            "audit_events",
            "provider_secrets",
            "project_members",
            "projects",
            "user_preferences",
            "users")) jdbc.update("DELETE FROM " + table);
    users.bootstrap("admin", PASSWORD);
    adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", String.class);
    admin = login("admin", PASSWORD);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void installsNodeFileAndRejectsVersionChangeWithoutForce() throws Exception {
    String source = "// @version 1.0.0\nconsole.log('node');";
    mvc.perform(
            write(
                post("/api/v1/admin/nodes/install"),
                admin,
                Map.of("fileName", "demoNode.umd.js", "source", source)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demoNode"));
    mvc.perform(get("/api/v1/admin/plugins").session(admin).secure(true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("demoNode"))
        .andExpect(jsonPath("$.items[0].version").value("1.0.0"))
        .andExpect(jsonPath("$.items[0].enabled").value(true));

    // 相同内容幂等
    mvc.perform(
            write(
                post("/api/v1/admin/nodes/install"),
                admin,
                Map.of("fileName", "demoNode.umd.js", "source", source)))
        .andExpect(status().isOk());
    // 版本变化必须显式覆盖
    String next = "// @version 2.0.0\nconsole.log('node2');";
    mvc.perform(
            write(
                post("/api/v1/admin/nodes/install"),
                admin,
                Map.of("fileName", "demoNode.umd.js", "source", next)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    mvc.perform(
            write(
                post("/api/v1/admin/nodes/install"),
                admin,
                Map.of("fileName", "demoNode.umd.js", "source", next, "force", true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demoNode"));
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM plugins WHERE type='node' AND name='demoNode'", String.class))
        .isEqualTo("2.0.0");
  }

  @Test
  void rejectsArchiveTraversalAndAcceptsSkillDocuments() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/skills/install"),
                admin,
                Map.of(
                    "fileName",
                    "evil.zip",
                    "base64",
                    Base64.getEncoder().encodeToString(zipOf("../escape.md", "# x")))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PLUGIN_ARCHIVE_PATH"));

    String packageBase64 =
        Base64.getEncoder()
            .encodeToString(
                zipOf(
                    "SKILL.md",
                    "# 技能\n\n说明。",
                    "manifest.json",
                    "{\"name\":\"docSkill\",\"version\":\"1.2.0\"}"));
    mvc.perform(
            write(
                post("/api/v1/admin/skills/install"),
                admin,
                Map.of("fileName", "docSkill.zip", "base64", packageBase64)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("docSkill"));
    mvc.perform(
            get("/api/v1/admin/skills/list").param("name", "docSkill").session(admin).secure(true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.mainPath").value("SKILL.md"))
        .andExpect(jsonPath("$.data.files[0]").value("SKILL.md"));
    mvc.perform(
            get("/api/v1/admin/skills/read").param("name", "docSkill").session(admin).secure(true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("# 技能\n\n说明。"));

    // 保存后回读一致
    mvc.perform(
            write(
                put("/api/v1/admin/skills/save"),
                admin,
                Map.of("name", "docSkill", "path", "SKILL.md", "content", "# 改过")))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/admin/skills/read").param("name", "docSkill").session(admin).secure(true))
        .andExpect(jsonPath("$.data.content").value("# 改过"));
  }

  @Test
  void masksSecretConfigAndExportsOriginalContent() throws Exception {
    String source = "// @version 1.0.0\nexport default {}";
    mvc.perform(
            write(
                post("/api/v1/admin/tools/install"),
                admin,
                Map.of("fileName", "webSearch.tool.js", "source", source)))
        .andExpect(status().isOk());
    mvc.perform(
            write(
                post("/api/v1/admin/plugins/tool/webSearch/config"),
                admin,
                Map.of(
                    "config",
                    Map.of("endpoint", "https://search.example", "apiKey", "plain-secret"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.config.apiKey").value("***"))
        .andExpect(jsonPath("$.data.config.endpoint").value("https://search.example"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("plain-secret"))));
    String stored =
        jdbc.queryForObject("SELECT config_json FROM plugins WHERE name='webSearch'", String.class);
    assertThat(stored).doesNotContain("plain-secret");

    byte[] exported =
        mvc.perform(
                get("/api/v1/admin/plugins/export")
                    .param("type", "tool")
                    .param("name", "webSearch")
                    .session(admin)
                    .secure(true))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(new String(exported, StandardCharsets.UTF_8)).isEqualTo(source);
  }

  @Test
  void togglesEnabledStateAndUninstallsOnlyNonBuiltin() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/skills/install"),
                admin,
                Map.of(
                    "fileName",
                    "toggle.md",
                    "base64",
                    Base64.getEncoder().encodeToString("# s".getBytes(StandardCharsets.UTF_8)))))
        .andExpect(status().isOk());
    mvc.perform(
            write(
                put("/api/v1/admin/skills/setEnabled"),
                admin,
                Map.of("name", "toggle", "enabled", false)))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject("SELECT enabled FROM plugins WHERE name='toggle'", Boolean.class))
        .isFalse();
    mvc.perform(
            delete("/api/v1/admin/skills/uninstall")
                .param("name", "toggle")
                .secure(true)
                .session(admin)
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", csrf()))
        .andExpect(status().isNoContent());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plugins", Integer.class)).isZero();

    jdbc.update(
        "INSERT INTO plugins(id,type,name,display_name,version,sha256,source,enabled,manifest_json,installed_by,created_at,updated_at) VALUES ('node:builtin','node','builtin','内置','1.0.0',?,'builtin',TRUE,'{}','system',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        "0".repeat(64));
    mvc.perform(
            delete("/api/v1/admin/plugins/node/builtin")
                .secure(true)
                .session(admin)
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PLUGIN_BUILTIN"));
  }

  @Test
  void marketRequiresCredentialAndBlocksUnlistedDownloadHosts() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/search"),
                admin,
                Map.of("page", 1, "limit", 10, "type", "node")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));

    asAdmin();
    configuration.setSecret(ConfigurationService.MARKET, "tf-router-key", "test");
    SecurityContextHolder.clearContext();
    // 上游不可达时给出明确故障，而不是伪造空目录。
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/search"),
                admin,
                Map.of("page", 1, "limit", 10, "type", "node")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MARKET_UNAVAILABLE"));

    // 安装不再接受客户端给的地址与摘要：多带 url/sha256 直接按请求体非法拒绝。
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/install"),
                admin,
                Map.of(
                    "type", "node",
                    "name", "remoteNode",
                    "version", "1.0.0",
                    "url", "https://evil.example/node.umd.js",
                    "sha256", "0".repeat(64))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void membersCannotManagePluginsAndPersonalizationTracksRevision() throws Exception {
    asAdmin();
    users.create(new CreateUserReqDTO("member", PASSWORD, "USER"), "test");
    SecurityContextHolder.clearContext();
    MockHttpSession member = login("member", PASSWORD);
    mvc.perform(get("/api/v1/admin/plugins").session(member).secure(true))
        .andExpect(status().isForbidden());

    mvc.perform(
            get("/api/v1/preferences/personalization")
                .param("document", "agents")
                .session(admin)
                .secure(true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value(""))
        .andExpect(jsonPath("$.data.revision").value(""));
    MvcResult saved =
        mvc.perform(
                write(
                    put("/api/v1/preferences/personalization"),
                    admin,
                    Map.of("document", "agents", "content", "团队说明")))
            .andExpect(status().isOk())
            .andReturn();
    String revision = body(saved).path("data").path("revision").asText();
    assertThat(revision).hasSize(32);

    mvc.perform(
            write(
                put("/api/v1/preferences/personalization"),
                admin,
                Map.of("document", "agents", "content", "并发修改", "revision", "stale")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
    mvc.perform(
            write(
                put("/api/v1/preferences/personalization"),
                admin,
                Map.of("document", "agents", "content", "已更新", "revision", revision)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("已更新"));
  }

  private void asAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new cn.huimeng.security.Actor(adminId, "admin", "ADMIN", "ACTIVE", 1),
                null,
                List.of()));
  }

  private String csrf() throws Exception {
    MvcResult token = mvc.perform(get("/api/v1/auth/csrf").secure(true).session(admin)).andReturn();
    return body(token).get("token").asText();
  }

  private byte[] zipOf(String... entries) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (int i = 0; i < entries.length; i += 2) {
        zip.putNextEntry(new ZipEntry(entries[i]));
        zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }

  private MockHttpSession login(String username, String password) throws Exception {
    MvcResult pre =
        mvc.perform(get("/api/v1/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .secure(true)
                    .session((MockHttpSession) pre.getRequest().getSession())
                    .header("Origin", ORIGIN)
                    .header("X-CSRF-Token", body(pre).get("token").asText())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession();
  }

  private MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder request, MockHttpSession session, Object input)
      throws Exception {
    MvcResult token =
        mvc.perform(get("/api/v1/auth/csrf").secure(true).session(session)).andReturn();
    return request
        .secure(true)
        .session(session)
        .header("Origin", ORIGIN)
        .header("X-CSRF-Token", body(token).get("token").asText())
        .contentType("application/json")
        .content(json.writeValueAsBytes(input));
  }
}
