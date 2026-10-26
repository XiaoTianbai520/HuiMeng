package cn.huimeng;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.security.SecretBox;
import cn.huimeng.service.ConfigurationService;
import cn.huimeng.service.UserService;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 市场代理成功流：本地合成上游返回目录与制品，验证凭据转发、摘要校验与安装落库。 */
@SpringBootTest
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class PluginMarketProxyTests {
  static final int PORT = 18141;
  static final HttpServer MARKET = startMarket();
  static final byte[] ARTIFACT = skillZip();
  static final String SHA = sha256(ARTIFACT);

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

  @DynamicPropertySource
  static void market(DynamicPropertyRegistry registry) {
    registry.add("huimeng.market.endpoint", () -> "http://127.0.0.1:" + PORT);
    registry.add("huimeng.market.artifact-hosts", () -> "127.0.0.1");
  }

  static HttpServer startMarket() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
      server.createContext(
          "/v1/plugin/getPlugin",
          exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body =
                "{\"code\":200,\"data\":{\"items\":[{\"id\":\"market-skill-1\",\"type\":\"skill\","
                    + "\"name\":\"marketSkill\",\"displayName\":\"市场技能\",\"author\":\"team\","
                    + "\"description\":\"来自市场的合成技能\",\"version\":\"1.4.0\","
                    + "\"manifest\":{\"type\":\"skill\",\"name\":\"marketSkill\",\"version\":\"1.4.0\","
                    + "\"sha256\":\""
                    + SHA
                    + "\"}}],\"total\":1}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (auth == null || !auth.startsWith("Bearer ")) {
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.createContext(
          "/artifacts/marketSkill.zip",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, ARTIFACT.length);
            exchange.getResponseBody().write(ARTIFACT);
            exchange.close();
          });
      server.start();
      return server;
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @AfterAll
  static void stopMarket() {
    MARKET.stop(0);
  }

  static byte[] skillZip() {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(out)) {
        zip.putNextEntry(new ZipEntry("SKILL.md"));
        zip.write("# 市场技能\n".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(
            "{\"name\":\"marketSkill\",\"version\":\"1.4.0\"}".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      return out.toByteArray();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
        hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
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
            "users")) jdbc.update("DELETE FROM " + table);
    users.bootstrap("admin", PASSWORD);
    adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", String.class);
    admin = login("admin", PASSWORD);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new cn.huimeng.security.Actor(adminId, "admin", "ADMIN", "ACTIVE", 1),
                null,
                List.of()));
    configuration.setSecret(ConfigurationService.MARKET, "tf-router-key", "test");
    SecurityContextHolder.clearContext();
  }

  @Test
  void searchesWithTeamCredentialAndInstallsVerifiedArtifact() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/search"),
                admin,
                Map.of("page", 1, "limit", 10, "type", "skill")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].name").value("marketSkill"))
        .andExpect(jsonPath("$.items[0].installable").value(true))
        .andExpect(jsonPath("$.items[0].isCollected").value(false));

    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/install"),
                admin,
                Map.of(
                    "pluginId", "market-skill-1",
                    "type", "skill",
                    "name", "marketSkill",
                    "version", "1.4.0",
                    "url", "http://127.0.0.1:" + PORT + "/artifacts/marketSkill.zip",
                    "sha256", SHA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("marketSkill"));
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM plugins WHERE type='skill' AND name='marketSkill'",
                String.class))
        .isEqualTo("1.4.0");
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM plugins WHERE type='skill' AND name='marketSkill'",
                String.class))
        .isEqualTo("market");
    // manifest.json 用于解析元数据，只把正文文档留在可变内容里。
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plugin_documents", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT content FROM plugin_documents WHERE path='SKILL.md'", String.class))
        .contains("市场技能");

    // 摘要不符时拒绝写入，且不覆盖已安装版本。
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/install"),
                admin,
                Map.of(
                    "type", "skill",
                    "name", "otherSkill",
                    "version", "1.0.0",
                    "url", "http://127.0.0.1:" + PORT + "/artifacts/marketSkill.zip",
                    "sha256", "f".repeat(64))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
  }

  @Test
  void collectionTogglePersistsPerPlugin() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/collections/toggle"),
                admin,
                Map.of("pluginId", "market-skill-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.collected").value(true));
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/search"),
                admin,
                Map.of("page", 1, "limit", 10, "type", "skill")))
        .andExpect(jsonPath("$.items[0].isCollected").value(true));
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/collections/toggle"),
                admin,
                Map.of("pluginId", "market-skill-1")))
        .andExpect(jsonPath("$.collected").value(false));
  }

  private MockHttpSession login(String username, String password) throws Exception {
    MvcResult pre = mvc.perform(get("/api/v1/auth/csrf").secure(true)).andReturn();
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

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }
}
