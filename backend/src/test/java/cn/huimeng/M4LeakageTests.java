package cn.huimeng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.dto.req.CreateProjectReqDTO;
import cn.huimeng.service.ProjectService;
import cn.huimeng.service.UserService;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
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

/**
 * M4-03 泄漏检查：写进去的秘密不能从任何读接口、导出、审计或错误响应里出来。
 *
 * <p>只覆盖「服务端自己产生的响应」；日志文件与部署层由 M4-05 发布检查复核。
 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class M4LeakageTests {
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

  static final String ORIGIN = "https://localhost:18443";
  static final String PASSWORD = "synthetic-account-password-42";
  static final String SECRET = "sk-leak-probe-9f2c1d7b4e8a6f0c";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserService users;
  @Autowired ProjectService projects;
  MockHttpSession admin;

  @BeforeEach
  void setup() throws Exception {
    for (String table :
        List.of(
            "media_providers",
            "plugin_documents",
            "plugins",
            "market_catalog",
            "market_collections",
            "app_documents",
            "audit_events",
            "provider_secrets",
            "external_grants",
            "external_clients",
            "project_members",
            "projects",
            "user_preferences",
            "users")) jdbc.update("DELETE FROM " + table);
    users.bootstrap("admin", PASSWORD);
    admin = login("admin", PASSWORD);
  }

  @Test
  void writtenSecretsNeverAppearInReadsExportsOrAudit() throws Exception {
    mvc.perform(
            write(put("/api/v1/admin/providers/deepseek/secret"), admin, Map.of("apiKey", SECRET)))
        .andExpect(status().isNoContent());
    mvc.perform(write(put("/api/v1/admin/plugin-market/secret"), admin, Map.of("apiKey", SECRET)))
        .andExpect(status().isNoContent());

    for (String path :
        List.of(
            "/api/v1/admin/plugins",
            "/api/v1/admin/plugin-market/status",
            "/api/v1/admin/models",
            "/api/v1/admin/configuration/export",
            "/api/v1/admin/providers/media/list")) {
      String body =
          mvc.perform(get(path).session(admin).secure(true))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);
      assertThat(body).as(path).doesNotContain(SECRET);
    }

    // 审计只记动作元数据，不落正文。
    assertThat(
            jdbc.queryForList(
                "SELECT COALESCE(action,'') || '|' || COALESCE(target,'') FROM audit_events",
                String.class))
        .allSatisfy(row -> assertThat(row).doesNotContain(SECRET));
    // 数据库里必须是密文。
    String stored =
        jdbc.queryForObject(
            "SELECT encrypted_value FROM provider_secrets WHERE provider_id='deepseek'",
            String.class);
    assertThat(stored).isNotNull().doesNotContain(SECRET);
  }

  @Test
  void issuedClientTokensAreNotReadableAfterCreation() throws Exception {
    asAdmin();
    var project = projects.create(new CreateProjectReqDTO("泄漏检查项目"), "leak");
    SecurityContextHolder.clearContext();

    MvcResult issued =
        mvc.perform(
                write(
                    post("/api/v1/admin/external-clients"),
                    admin,
                    Map.of(
                        "name", "probe",
                        "projectId", project.id(),
                        "mcpTools", List.of("getAppState"),
                        "a2aTeams", List.of())))
            .andExpect(status().isCreated())
            .andReturn();
    String token = body(issued).path("token").asText();
    assertThat(token).startsWith("hm1.");

    String listed =
        mvc.perform(get("/api/v1/admin/external-clients").session(admin).secure(true))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(listed).doesNotContain(token);
    // 列表只给标识与能力，不给摘要。
    assertThat(listed).doesNotContain("tokenHash").doesNotContain("token_hash");
  }

  @Test
  void errorResponsesDoNotEchoSubmittedSecrets() throws Exception {
    MvcResult pre =
        mvc.perform(get("/api/v1/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
    MvcResult login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .secure(true)
                    .session((MockHttpSession) pre.getRequest().getSession())
                    .header("Origin", ORIGIN)
                    .header("X-CSRF-Token", body(pre).get("token").asText())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(Map.of("username", "admin", "password", SECRET))))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(login.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .doesNotContain(SECRET);

    MvcResult market =
        mvc.perform(
                write(
                    post("/api/v1/admin/plugin-market/install"),
                    admin,
                    Map.of("pluginId", "missing", "force", false)))
            .andExpect(status().is4xxClientError())
            .andReturn();
    assertThat(market.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .doesNotContain(SECRET);
  }

  // --- 夹具 -----------------------------------------------------------------

  private void asAdmin() {
    String adminId =
        jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", String.class);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new cn.huimeng.security.Actor(adminId, "admin", "ADMIN", "ACTIVE", 1),
                null,
                List.of()));
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
        .characterEncoding(StandardCharsets.UTF_8)
        .content(json.writeValueAsBytes(input));
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }
}
