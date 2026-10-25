package cn.huimeng;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.common.exception.*;
import cn.huimeng.dto.req.CreateUserReqDTO;
import cn.huimeng.dto.req.IssueExternalClientReqDTO;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
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

/** Real filters, persistence and HTTP DTO tests using synthetic accounts and fresh migrations. */
@SpringBootTest
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class SecurityIntegrationTests {
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
  @Autowired ProjectService projects;
  @Autowired ExternalClientService clients;
  @Autowired SecretBox secrets;
  @Autowired InternalIdentityService internalIdentity;
  @Autowired ModelAuthorizationService modelAuthorization;
  MockHttpSession admin;
  String adminId;

  @BeforeEach
  void setup() throws Exception {
    for (String table :
        List.of(
            "audit_events",
            "generation_runs",
            "external_grants",
            "external_clients",
            "provider_secrets",
            "project_members",
            "projects",
            "user_preferences",
            "users")) jdbc.update("DELETE FROM " + table);
    jdbc.update("UPDATE approved_models SET enabled=FALSE");
    jdbc.update("UPDATE approved_models SET options_json='{}' WHERE provider_id='deepseek'");
    users.bootstrap("admin", PASSWORD);
    adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", String.class);
    admin = login("admin", PASSWORD);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void configurationExportNeverIncludesWriteOnlySecretsOrArbitraryModelOptions() throws Exception {
    String secret = "synthetic-export-provider-secret";
    mvc.perform(
            write(
                put("/api/v1/admin/providers/export-check/secret"),
                admin,
                Map.of("apiKey", secret)))
        .andExpect(status().isNoContent());
    mvc.perform(write(put("/api/v1/admin/plugin-market/secret"), admin, Map.of("apiKey", secret)))
        .andExpect(status().isNoContent());
    jdbc.update(
        "UPDATE approved_models SET options_json=? WHERE provider_id='deepseek'",
        "{\"apiKey\":\"synthetic-forbidden-option\"}");
    MvcResult result =
        mvc.perform(get("/api/v1/admin/configuration/export").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.marketConfigured").value(true))
            .andReturn();
    String content = result.getResponse().getContentAsString();
    assertThat(content)
        .doesNotContain(
            secret,
            "synthetic-forbidden-option",
            "encrypted_value",
            "apiKey",
            "workspace_key",
            "master.key",
            "options");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action='CONFIGURATION_EXPORT'",
                Integer.class))
        .isEqualTo(1);
    createUser("export-member");
    mvc.perform(get("/api/v1/admin/configuration/export").session(login("export-member", PASSWORD)))
        .andExpect(status().isForbidden());
  }

  @Test
  void modelExecutionGateRechecksMembershipApprovalAndArchivedState() throws Exception {
    String project = createProject("model-execution-gate");
    actAdmin();
    assertThatThrownBy(
            () ->
                modelAuthorization.requireApproved(project, "qwenImage21Local", "qwenImage21Text"))
        .isInstanceOf(ApiException.class);
    SecurityContextHolder.clearContext();
    member(project, adminId);
    actAdmin();
    assertThatThrownBy(
            () ->
                modelAuthorization.requireApproved(project, "qwenImage21Local", "qwenImage21Text"))
        .isInstanceOf(ApiException.class);
    jdbc.update(
        "UPDATE approved_models SET enabled=TRUE WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'");
    assertThat(
            modelAuthorization
                .requireApproved(project, "qwenImage21Local", "qwenImage21Text")
                .modelId())
        .isEqualTo("qwenImage21Text");
    jdbc.update(
        "UPDATE approved_models SET enabled=FALSE WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'");
    assertThatThrownBy(
            () ->
                modelAuthorization.requireApproved(project, "qwenImage21Local", "qwenImage21Text"))
        .isInstanceOf(ApiException.class);
    jdbc.update(
        "UPDATE approved_models SET enabled=TRUE WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'");
    jdbc.update("UPDATE projects SET status='ARCHIVED' WHERE id=?", project);
    assertThatThrownBy(
            () ->
                modelAuthorization.requireApproved(project, "qwenImage21Local", "qwenImage21Text"))
        .isInstanceOf(ApiException.class);
    jdbc.update("UPDATE projects SET status='ACTIVE' WHERE id=?", project);
    jdbc.update("DELETE FROM project_members WHERE project_id=?", project);
    assertThatThrownBy(
            () ->
                modelAuthorization.requireApproved(project, "qwenImage21Local", "qwenImage21Text"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void realFileRouteEnforcesMembersBeforeReadingAndRejectsClaimedDirectories() throws Exception {
    String own = createProject("files-own"), other = createProject("files-other");
    createUser("file-member");
    String memberId =
        jdbc.queryForObject("SELECT id FROM users WHERE username='file-member'", String.class);
    member(own, memberId);
    MockHttpSession user = login("file-member", PASSWORD);
    String route = "/api/v1/projects/" + own + "/creative/workspaces/files/read";
    mvc.perform(get(route).param("path", "file.txt")).andExpect(status().isUnauthorized());
    mvc.perform(get(route).param("path", "file.txt").session(admin))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/projects/" + other + "/creative/workspaces/files/read")
                .param("path", "file.txt")
                .session(user))
        .andExpect(status().isForbidden());
    for (String path :
        List.of("../outside.txt", "/etc/passwd", "a/../../b", "C:/secret", "a\\b", "a%2fb"))
      mvc.perform(get(route).param("path", path).session(user)).andExpect(status().isBadRequest());
    mvc.perform(get(route).param("path", "file.txt").param("directory", "/outside").session(user))
        .andExpect(status().isBadRequest());
    mvc.perform(write(delete("/api/v1/projects/" + own + "/members/" + memberId), admin, Map.of()))
        .andExpect(status().isNoContent());
    mvc.perform(get(route).param("path", "file.txt").session(user))
        .andExpect(status().isForbidden());
  }

  @Test
  void nativeFileReadsUseSecureHandlesOrFailClosedWithoutAWeakerFallback() throws Exception {
    String project = createProject("native-file");
    member(project, adminId);
    String key =
        jdbc.queryForObject("SELECT workspace_key FROM projects WHERE id=?", String.class, project);
    Path root = Path.of("target/test-workspaces", key);
    byte[] bytes = "验收\r\nfile".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(root.resolve("测试.txt"), bytes);
    String route = "/api/v1/projects/" + project + "/creative/workspaces/files/read";
    boolean secure;
    try (var stream =
        Files.newDirectoryStream(
            root.getRoot() == null ? root.toAbsolutePath().getRoot() : root.getRoot())) {
      secure = stream instanceof java.nio.file.SecureDirectoryStream<?>;
    }
    if (!secure) {
      mvc.perform(get(route).param("path", "测试.txt").session(admin))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("EXECUTOR_UNAVAILABLE"));
      return;
    }
    mvc.perform(get(route).param("path", "测试.txt").session(admin))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes));
    mvc.perform(get(route).param("path", "测试.txt").param("snapshot", "true").session(admin))
        .andExpect(jsonPath("$.data.text").value("验收\r\nfile"));
    mvc.perform(get(route).param("path", "测试.txt").header("Range", "bytes=0-2").session(admin))
        .andExpect(status().isPartialContent())
        .andExpect(content().bytes(java.util.Arrays.copyOf(bytes, 3)));
    mvc.perform(get(route).param("path", "测试.txt").header("Range", "bytes=99999-").session(admin))
        .andExpect(status().isRequestedRangeNotSatisfiable());
  }

  @Test
  void accountCreationRollsBackWhenAuditInsertFailsAfterLayerRefactor() {
    actAdmin();
    assertThatThrownBy(
            () ->
                users.create(
                    new CreateUserReqDTO("rollback-account", PASSWORD, "USER"), "x".repeat(129)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username='rollback-account'", Integer.class))
        .isZero();
  }

  @Test
  void externalClientAndGrantsRollBackTogetherWhenAuditFails() throws Exception {
    String project = createProject("rollback-client");
    actAdmin();
    assertThatThrownBy(
            () ->
                clients.issue(
                    new IssueExternalClientReqDTO(
                        "rollback-client", project, List.of("getCanvas"), List.of("writers")),
                    "x".repeat(129)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_clients", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_grants", Integer.class)).isZero();
  }

  @Test
  void preferencesPersistPerAccountAndNeverSelectAnotherOwner() throws Exception {
    createUser("preferences-member");
    MockHttpSession employee = login("preferences-member", PASSWORD);
    mvc.perform(get("/api/v1/preferences").session(employee))
        .andExpect(status().isOk())
        .andExpect(content().json("{}"));
    mvc.perform(
            write(
                put("/api/v1/preferences"),
                employee,
                Map.of(
                    "ui", Map.of("theme", "dark"), "stores", Map.of("panels", List.of("canvas")))))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/preferences").session(employee))
        .andExpect(jsonPath("$.ui.theme").value("dark"));
    mvc.perform(get("/api/v1/preferences").session(admin)).andExpect(content().json("{}"));
    mvc.perform(
            write(put("/api/v1/preferences"), employee, Map.of("userId", adminId, "ui", Map.of())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void preferencesRejectSharedSecretsInvalidShapesAndOversizedPayloads() throws Exception {
    for (Object input :
        List.of(
            Map.of("customProviders", List.of()),
            Map.of("ui", List.of()),
            Map.of("stores", Map.of("nested", List.of(Map.of("api_key", "synthetic-secret")))),
            Map.of("general", Map.of("token", "synthetic-token")),
            Map.of("documentExtensions", Map.of("txt", 12)),
            Map.of("ui", Map.of("label", "a".repeat(33000))))) {
      mvc.perform(write(put("/api/v1/preferences"), admin, input))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get("/api/v1/preferences").session(admin)).andExpect(content().json("{}"));
  }

  @Test
  void preferenceWritesRequireSessionCsrfAndSameOrigin() throws Exception {
    mvc.perform(get("/api/v1/preferences")).andExpect(status().isUnauthorized());
    mvc.perform(
            put("/api/v1/preferences")
                .session(admin)
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            write(
                put("/api/v1/preferences"),
                admin,
                Map.of("documentExtensions", Map.of("md", "text/markdown"))))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/preferences").session(admin))
        .andExpect(jsonPath("$.documentExtensions.md").value("text/markdown"));
  }

  @Test
  void onlyAdministratorsSeeUnapprovedModelCatalogAndApprovalState() throws Exception {
    createUser("catalog-member");
    MockHttpSession employee = login("catalog-member", PASSWORD);
    mvc.perform(get("/api/v1/admin/models").session(employee)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/models").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(8))
        .andExpect(jsonPath("$.items[0].enabled").value(false));
    mvc.perform(get("/api/v1/models").session(employee))
        .andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(
            write(
                put("/api/v1/admin/models/approval"),
                admin,
                Map.of("providerId", "deepseek", "modelId", "deepseek-chat", "enabled", true)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/models").session(employee))
        .andExpect(jsonPath("$.items[0].enabled").value(true));
  }

  JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }

  MockHttpSession login(String username, String password) throws Exception {
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

  MockHttpServletRequestBuilder write(
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

  String createUser(String name) throws Exception {
    return body(mvc.perform(
                write(
                    post("/api/v1/admin/users"),
                    admin,
                    Map.of("username", name, "password", PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn())
        .get("id")
        .asText();
  }

  String createProject(String name) throws Exception {
    return body(mvc.perform(write(post("/api/v1/projects"), admin, Map.of("name", name)))
            .andExpect(status().isCreated())
            .andReturn())
        .get("id")
        .asText();
  }

  void member(String project, String user) throws Exception {
    mvc.perform(
            write(post("/api/v1/projects/" + project + "/members"), admin, Map.of("userId", user)))
        .andExpect(status().isCreated());
  }

  void actAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new Actor(adminId, "admin", "ADMIN", "ACTIVE", 0), null, List.of()));
  }

  @Test
  void freshMigrationsCreateConstraintsAndValidateOnRestart() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL",
                Integer.class))
        .isEqualTo(3);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO project_members VALUES ('missing','missing',CURRENT_TIMESTAMP)"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    String hash =
        jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, adminId);
    assertThat(hash).doesNotContain(PASSWORD).hasSizeGreaterThan(60);
    users.bootstrap("replacement", PASSWORD);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
  }

  @Test
  void loginRotatesSessionAndPreloginCsrf() throws Exception {
    MvcResult pre = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
    MockHttpSession old = (MockHttpSession) pre.getRequest().getSession();
    String token = body(pre).get("token").asText();
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .session(old)
                    .header("Origin", ORIGIN)
                    .header("X-CSRF-Token", token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(Map.of("username", "admin", "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    MockHttpSession fresh = (MockHttpSession) result.getRequest().getSession();
    assertThat(body(result).path("user").path("status").asText()).isEqualTo("ACTIVE");
    assertThat(old.isInvalid()).isTrue();
    assertThat(fresh.getId()).isNotEqualTo(old.getId());
    String next =
        body(mvc.perform(get("/api/v1/auth/csrf").session(fresh)).andReturn())
            .get("token")
            .asText();
    assertThat(next).isNotEqualTo(token);
    mvc.perform(
            post("/api/v1/auth/logout")
                .session(fresh)
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
  }

  @Test
  void everyBrowserWriteRequiresOriginAndCsrfIncludingLogin() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    mvc.perform(
            write(post("/api/v1/projects"), admin, Map.of("name", "denied"))
                .header("Origin", "https://evil.invalid"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/projects")
                .session(admin)
                .contentType("application/json")
                .content("{\"name\":\"denied\"}"))
        .andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects", Integer.class)).isZero();
  }

  @Test
  void logoutAndAccountDisablingBlockSubsequentRequests() throws Exception {
    String id = createUser("employee");
    MockHttpSession employee = login("employee", PASSWORD);
    mvc.perform(write(patch("/api/v1/admin/users/" + id), admin, Map.of("status", "DISABLED")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").session(employee)).andExpect(status().isUnauthorized());
    mvc.perform(write(post("/api/v1/auth/logout"), admin, Map.of()))
        .andExpect(status().isNoContent())
        .andExpect(
            header()
                .string(
                    "Set-Cookie",
                    org.hamcrest.Matchers.containsString("Secure; HttpOnly; SameSite=Lax")));
    assertThat(admin.isInvalid()).isTrue();
  }

  @Test
  void passwordAndRoleChangesRevokeOldSessions() throws Exception {
    String id = createUser("employee");
    MockHttpSession employee = login("employee", PASSWORD);
    mvc.perform(write(patch("/api/v1/admin/users/" + id), admin, Map.of("role", "ADMIN")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").session(employee)).andExpect(status().isUnauthorized());
    MockHttpSession next = login("employee", PASSWORD);
    mvc.perform(
            write(
                patch("/api/v1/admin/users/" + id),
                admin,
                Map.of("password", "new-synthetic-password-43")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").session(next)).andExpect(status().isUnauthorized());
  }

  @Test
  void lastAdministratorCannotBeDisabledOrDemoted() throws Exception {
    mvc.perform(write(patch("/api/v1/admin/users/" + adminId), admin, Map.of("status", "DISABLED")))
        .andExpect(status().isBadRequest());
    mvc.perform(write(patch("/api/v1/admin/users/" + adminId), admin, Map.of("role", "USER")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void accountsHaveNoPublicRegistrationOrDefaultCredentials() throws Exception {
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/register")).andExpect(status().isForbidden());
    mvc.perform(get("/actuator/env").session(admin)).andExpect(status().isForbidden());
    mvc.perform(get("/h2-console").session(admin)).andExpect(status().isForbidden());
  }

  @Test
  void membersCannotManageAccountsKeysCredentialsOrMembership() throws Exception {
    String id = createUser("employee");
    MockHttpSession employee = login("employee", PASSWORD);
    String project = createProject("private");
    for (String path :
        List.of(
            "/api/v1/admin/users",
            "/api/v1/admin/providers",
            "/api/v1/admin/external-clients",
            "/api/v1/projects/" + project + "/members"))
      mvc.perform(get(path).session(employee)).andExpect(status().isForbidden());
    mvc.perform(
            write(
                put("/api/v1/admin/providers/deepseek/secret"),
                employee,
                Map.of("apiKey", "synthetic-secret")))
        .andExpect(status().isForbidden());
    mvc.perform(
            write(post("/api/v1/projects/" + project + "/members"), employee, Map.of("userId", id)))
        .andExpect(status().isForbidden());
  }

  @Test
  void projectsAreFilteredAndMembershipRemovalTakesImmediateEffect() throws Exception {
    String id = createUser("employee");
    String own = createProject("own"), other = createProject("other");
    member(own, id);
    MockHttpSession employee = login("employee", PASSWORD);
    mvc.perform(get("/api/v1/projects").session(employee))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(own));
    mvc.perform(get("/api/v1/projects/" + other).session(employee))
        .andExpect(status().isForbidden());
    mvc.perform(write(delete("/api/v1/projects/" + own + "/members/" + id), admin, Map.of()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/projects/" + own).session(employee)).andExpect(status().isForbidden());
  }

  @Test
  void adminMetadataAccessDoesNotPermitCreativeAccess() throws Exception {
    String project = createProject("private");
    mvc.perform(get("/api/v1/projects/" + project).session(admin))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("workspace"))));
    actAdmin();
    assertThatThrownBy(() -> projects.resolve(project, "canvas.json", false))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("无权");
  }

  @Test
  void pathsRejectTraversalAbsolutePathsAndCrossProjectIds() throws Exception {
    String project = createProject("private");
    member(project, adminId);
    actAdmin();
    for (String path :
        List.of(
            "../x",
            "a/../../x",
            "/etc/passwd",
            "C:/Windows/file",
            "a\\b",
            "a:b",
            "%2e%2e/x",
            "a//b",
            "./a",
            "a/.."))
      assertThatThrownBy(() -> projects.resolve(project, path, false))
          .isInstanceOf(ApiException.class);
    assertThat(projects.resolve(project, "中文/画布.json", false).toString()).contains("中文");
  }

  @Test
  void archivedProjectsRejectNewCreativeWrites() throws Exception {
    String project = createProject("archived");
    member(project, adminId);
    mvc.perform(write(patch("/api/v1/projects/" + project), admin, Map.of("status", "ARCHIVED")))
        .andExpect(status().isOk());
    actAdmin();
    assertThatThrownBy(() -> projects.resolve(project, "canvas.json", true))
        .isInstanceOf(ApiException.class);
    assertThat(projects.resolve(project, "canvas.json", false)).isNotNull();
  }

  @Test
  void symbolicLinksCannotEscapeProject() throws Exception {
    String project = createProject("links");
    member(project, adminId);
    actAdmin();
    Path safe = projects.resolve(project, "link", false),
        outside = Files.createTempDirectory("hm-outside");
    try {
      Files.createSymbolicLink(safe, outside);
    } catch (java.io.IOException | UnsupportedOperationException ex) {
      Assumptions.abort(
          "Symlink creation unavailable on this host; required Linux verification remains");
    }
    assertThatThrownBy(() -> projects.resolve(project, "link/secret", false))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void strictDtosRejectClaimedActorAndPhysicalDirectory() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/projects"),
                admin,
                Map.of("name", "x", "directory", "/etc", "actor", "admin")))
        .andExpect(status().isBadRequest());
    mvc.perform(write(patch("/api/v1/admin/users/" + adminId), admin, Map.of()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void companySecretsAreAuthenticatedEncryptedAndNeverReturned() throws Exception {
    String secret = "synthetic-company-secret-for-test";
    mvc.perform(
            write(put("/api/v1/admin/providers/deepseek/secret"), admin, Map.of("apiKey", secret)))
        .andExpect(status().isNoContent());
    String stored =
        jdbc.queryForObject(
            "SELECT encrypted_value FROM provider_secrets WHERE provider_id='deepseek'",
            String.class);
    assertThat(stored).doesNotContain(secret);
    assertThat(secrets.decrypt("deepseek", stored)).isEqualTo(secret);
    assertThatThrownBy(() -> secrets.decrypt("other-provider", stored))
        .isInstanceOf(IllegalStateException.class);
    mvc.perform(get("/api/v1/admin/providers").session(admin))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(stored))));
    mvc.perform(write(put("/api/v1/admin/plugin-market/secret"), admin, Map.of("apiKey", secret)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/admin/plugin-market/status").session(admin))
        .andExpect(jsonPath("$.configured").value(true));
    assertThat(jdbc.queryForList("SELECT * FROM audit_events").toString())
        .doesNotContain(secret, stored);
  }

  @Test
  void membersSeeOnlyApprovedModelsAndQwenKeeps2k() throws Exception {
    String employeeId = createUser("employee");
    MockHttpSession employee = login("employee", PASSWORD);
    mvc.perform(get("/api/v1/models").session(employee))
        .andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(
            write(
                put("/api/v1/admin/models/approval"),
                admin,
                Map.of(
                    "providerId",
                    "qwenImage21Local",
                    "modelId",
                    "qwenImage21Text",
                    "enabled",
                    true)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/models").session(employee))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].options.resolutions[2]").value("2K"));
    mvc.perform(
            write(
                put("/api/v1/admin/models/approval"),
                employee,
                Map.of(
                    "providerId",
                    "qwenImage21Local",
                    "modelId",
                    "qwenImage21Edit",
                    "enabled",
                    true)))
        .andExpect(status().isForbidden());
  }

  @Test
  void externalTokensAreOneTimeScopedHashedAndImmediatelyRevocable() throws Exception {
    String project = createProject("external"), other = createProject("other");
    JsonNode issued =
        body(
            mvc.perform(
                    write(
                        post("/api/v1/admin/external-clients"),
                        admin,
                        Map.of(
                            "name",
                            "integration",
                            "projectId",
                            project,
                            "mcpTools",
                            List.of("getCanvas"),
                            "a2aTeams",
                            List.of("writers"))))
                .andExpect(status().isCreated())
                .andReturn());
    String token = issued.get("token").asText(), id = issued.get("id").asText();
    assertThat(
            jdbc.queryForObject(
                "SELECT secret_hash FROM external_clients WHERE id=?", String.class, id))
        .hasSize(64)
        .doesNotContain(token);
    var actor = clients.authenticate("Bearer " + token);
    clients.authorize(actor, project, "MCP", "getCanvas");
    assertThatThrownBy(() -> clients.authorize(actor, other, "MCP", "getCanvas"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> clients.authorize(actor, project, "MCP", "deleteFile"))
        .isInstanceOf(ApiException.class);
    mvc.perform(get("/api/v1/admin/external-clients").session(admin))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret_hash"))));
    mvc.perform(write(post("/api/v1/admin/external-clients/" + id + "/revoke"), admin, Map.of()))
        .andExpect(status().isNoContent());
    assertThatThrownBy(() -> clients.authenticate("Bearer " + token))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void externalIngressRejectsEmployeeSessionsAndPreservesRpcId() throws Exception {
    mvc.perform(
            post("/mcp")
                .session(admin)
                .contentType("application/json")
                .content("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"initialize\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.id").value(42))
        .andExpect(jsonPath("$.error.code").value(-32099))
        .andExpect(jsonPath("$.error.data.code").value("CLIENT_TOKEN_INVALID"));
    mvc.perform(post("/a2a/writers").session(admin)).andExpect(status().isUnauthorized());
  }

  @Test
  void noCreativeOrMarketProxyIsOpenedByPlatformAuthentication() throws Exception {
    String project = createProject("closed");
    member(project, adminId);
    for (String path :
        List.of(
            "/api/v1/projects/" + project + "/creative/file/read",
            "/api/v1/projects/" + project + "/creative/ai/image",
            "/api/v1/admin/plugin-market/search"))
      mvc.perform(write(post(path), admin, Map.of())).andExpect(status().isForbidden());
  }

  @Test
  void pagingIsStableScopedAndValidatesCursorAndLimits() throws Exception {
    createProject("one");
    createProject("two");
    createProject("three");
    JsonNode first =
        body(
            mvc.perform(get("/api/v1/projects?limit=2").session(admin))
                .andExpect(status().isOk())
                .andReturn());
    String cursor = first.get("nextCursor").asText();
    mvc.perform(get("/api/v1/projects").param("limit", "2").param("cursor", cursor).session(admin))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
    mvc.perform(get("/api/v1/admin/users").param("cursor", cursor).session(admin))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/projects?limit=51").session(admin)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/projects?cursor=bad").session(admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void auditingTracksMutationsAndDenialsWithoutBodies() throws Exception {
    createUser("employee");
    MockHttpSession employee = login("employee", PASSWORD);
    mvc.perform(
            get("/api/v1/admin/users").session(employee).header("X-Request-Id", "safe_request_42"))
        .andExpect(status().isForbidden())
        .andExpect(header().string("X-Request-Id", "safe_request_42"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action='ACCESS_DENIED' AND request_id='safe_request_42'",
                Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT * FROM audit_events").toString())
        .doesNotContain(PASSWORD, "password_hash", "Authorization");
  }

  @Test
  void loginLimiterExpiresAndDoesNotTrustForwardedHeaders() {
    LoginLimiter limiter =
        new LoginLimiter(Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC));
    for (int i = 0; i < 10; i++) limiter.check("127.0.0.1", "same-user");
    assertThatThrownBy(() -> limiter.check("another-ip", "same-user"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("过多");
  }

  @Test
  void absoluteSessionLifetimeRejectsAnOtherwiseActiveSession() throws Exception {
    admin.setAttribute("born", Instant.now().minusSeconds(86401).getEpochSecond());
    mvc.perform(get("/api/v1/auth/me").session(admin)).andExpect(status().isUnauthorized());
  }

  @Test
  void sensitiveWritesAreRateLimitedAndResetNextMinute() {
    OperationLimiter limiter = new OperationLimiter();
    for (int i = 0; i < 60; i++) limiter.check("synthetic-user", 120);
    assertThatThrownBy(() -> limiter.check("synthetic-user", 121)).isInstanceOf(ApiException.class);
    limiter.check("synthetic-user", 180);
  }

  @Test
  void springInternalContextBindsFreshMembershipAndDoesNotExposePhysicalPaths() throws Exception {
    String project = createProject("internal");
    actAdmin();
    assertThatThrownBy(() -> internalIdentity.issueReadyProbe(project, "probe"))
        .isInstanceOf(ApiException.class);
    SecurityContextHolder.clearContext();
    member(project, adminId);
    actAdmin();
    String token = internalIdentity.issueReadyProbe(project, "probe");
    JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
    assertThat(payload.path("projectId").asText()).isEqualTo(project);
    assertThat(payload.path("actorId").asText()).isEqualTo(adminId);
    assertThat(payload.path("exp").asLong() - payload.path("iat").asLong()).isEqualTo(20);
    assertThat(payload.toString()).doesNotContain("absolutePath", "directory", "/data/");
    assertThat(internalIdentity.issueReadyProbe(project, "probe")).isNotEqualTo(token);
  }

  @Test
  void externalProtocolGatesCapabilitiesRevocationAndNeverExecutesDuringM2() throws Exception {
    String project = createProject("protocol");
    JsonNode issued =
        body(
            mvc.perform(
                    write(
                        post("/api/v1/admin/external-clients"),
                        admin,
                        Map.of(
                            "name",
                            "protocol",
                            "projectId",
                            project,
                            "mcpTools",
                            List.of("getCanvas"),
                            "a2aTeams",
                            List.of("writers"))))
                .andExpect(status().isCreated())
                .andReturn());
    String token = issued.path("token").asText();
    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"getCanvas\"}}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.id").value(7));
    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"deleteFile\"}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.id").doesNotExist());
    mvc.perform(post("/a2a/other-team").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            write(
                post("/api/v1/admin/external-clients/" + issued.path("id").asText() + "/revoke"),
                admin,
                Map.of()))
        .andExpect(status().isNoContent());
    mvc.perform(post("/mcp").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }
}
