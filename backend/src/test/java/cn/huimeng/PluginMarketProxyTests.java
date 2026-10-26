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

/**
 * 市场代理：本地合成上游返回真实目录形态（identifier/supplier/desc/link/fileName/state），验证字段映射、来源白名单、
 * 服务端目录回查与获批摘要，而不是采信客户端给的 url/sha256。
 */
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
            if (auth == null || !auth.startsWith("Bearer ")) {
              exchange.sendResponseHeaders(401, -1);
              exchange.close();
              return;
            }
            String body =
                "{\"code\":200,\"data\":{\"list\":["
                    + entry(
                        74,
                        "市场技能",
                        "marketSkill",
                        "team",
                        "skill",
                        1,
                        "来自市场的合成技能",
                        "1.4.0",
                        "marketSkill.zip",
                        artifact("marketSkill.zip"))
                    + ","
                    + entry(
                        75,
                        "越界工具",
                        "evilTool",
                        "team",
                        "tool",
                        1,
                        "来源不在清单内",
                        "1.0.0",
                        "evilTool.tool.js",
                        "https://evil.example.com/x/evilTool.tool.js")
                    + ","
                    + entry(
                        76,
                        "已下架节点",
                        "goneNode",
                        "team",
                        "node",
                        0,
                        "已下架",
                        "1.0.0",
                        "goneNode.umd.js",
                        artifact("goneNode.umd.js"))
                    + "],\"total\":3}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      // 签名跳转：同源 302 后才是真实制品。
      server.createContext(
          "/artifacts/marketSkill.zip",
          exchange -> {
            exchange.getResponseHeaders().add("Location", artifact("real.zip"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      server.createContext(
          "/artifacts/real.zip",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, ARTIFACT.length);
            exchange.getResponseBody().write(ARTIFACT);
            exchange.close();
          });
      // 跨主机跳转：不允许被跟随。
      server.createContext(
          "/artifacts/offhost.zip",
          exchange -> {
            exchange.getResponseHeaders().add("Location", "https://evil.example.com/x.zip");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  static String artifact(String fileName) {
    return "http://127.0.0.1:" + PORT + "/artifacts/" + fileName;
  }

  static String entry(
      int id,
      String displayName,
      String identifier,
      String supplier,
      String type,
      int state,
      String desc,
      String version,
      String fileName,
      String link) {
    return "{\"id\":"
        + id
        + ",\"name\":\""
        + displayName
        + "\",\"identifier\":\""
        + identifier
        + "\",\"supplier\":\""
        + supplier
        + "\",\"type\":\""
        + type
        + "\",\"state\":"
        + state
        + ",\"desc\":\""
        + desc
        + "\",\"link\":\""
        + link
        + "\",\"fileName\":\""
        + fileName
        + "\",\"version\":\""
        + version
        + "\",\"fileHash\":\"4157b9da127ef31448865fae2d1700b156249f36dca7a1d5de64cd6bcaace791\"}";
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
            "market_catalog",
            "market_collections",
            "app_documents",
            "audit_events",
            "provider_secrets",
            "project_members",
            "projects",
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
  void mapsRealCatalogFieldsAndInstallsApprovedArtifact() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/search"),
                admin,
                Map.of("page", 1, "limit", 10, "type", "skill")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.total").value(3))
        // identifier 是安装标识，中文 name 只作显示名。
        .andExpect(jsonPath("$.items[0].name").value("marketSkill"))
        .andExpect(jsonPath("$.items[0].displayName").value("市场技能"))
        .andExpect(jsonPath("$.items[0].author").value("team"))
        .andExpect(jsonPath("$.items[0].description").value("来自市场的合成技能"))
        .andExpect(jsonPath("$.items[0].installable").value(true))
        // 未知来源与已下架都不能装，并且给出原因。
        .andExpect(jsonPath("$.items[1].installable").value(false))
        .andExpect(jsonPath("$.items[1].reason").value("下载来源不在允许清单内"))
        .andExpect(jsonPath("$.items[2].installable").value(false))
        .andExpect(jsonPath("$.items[2].reason").value("该制品已下架"));

    // 只登记可安装的条目。
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_catalog", Integer.class))
        .isEqualTo(1);

    mvc.perform(write(post("/api/v1/admin/plugin-market/install"), admin, Map.of("pluginId", "74")))
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
    assertThat(
            jdbc.queryForObject(
                "SELECT content FROM plugin_documents WHERE path='SKILL.md'", String.class))
        .contains("市场技能");
    // 首次下载即记录获批摘要；fileHash 不作校验依据。
    assertThat(
            jdbc.queryForObject(
                "SELECT sha256 FROM market_catalog WHERE plugin_id='74'", String.class))
        .isEqualTo(SHA);

    // 同一制品再次安装必须与已获批摘要一致，显式覆盖也要走同一校验。
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/install"),
                admin,
                Map.of("pluginId", "74", "force", true)))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsUnknownEntryAndClientSuppliedSource() throws Exception {
    mvc.perform(
            write(post("/api/v1/admin/plugin-market/install"), admin, Map.of("pluginId", "999")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MARKET_ENTRY_UNKNOWN"));

    // 未登记（越界来源）的条目即使知道 id 也不能装。
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/search"), admin, Map.of("page", 1, "limit", 10)))
        .andExpect(status().isOk());
    mvc.perform(write(post("/api/v1/admin/plugin-market/install"), admin, Map.of("pluginId", "75")))
        .andExpect(status().isNotFound());

    // 客户端塞 url/sha256 不再被接受：请求体只允许 pluginId 与 force。
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/install"),
                admin,
                Map.of(
                    "pluginId",
                    "74",
                    "url",
                    "http://127.0.0.1:" + PORT + "/artifacts/real.zip",
                    "sha256",
                    SHA)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsArtifactThatDiffersFromApprovedDigest() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/search"), admin, Map.of("page", 1, "limit", 10)))
        .andExpect(status().isOk());
    jdbc.update("UPDATE market_catalog SET sha256=? WHERE plugin_id='74'", "f".repeat(64));

    mvc.perform(write(post("/api/v1/admin/plugin-market/install"), admin, Map.of("pluginId", "74")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM plugins WHERE name='marketSkill'", Integer.class))
        .isZero();
  }

  @Test
  void refusesRedirectThatLeavesAllowedHosts() throws Exception {
    jdbc.update(
        "INSERT INTO market_catalog(plugin_id,type,name,display_name,version,file_name,"
            + "artifact_url,sha256,updated_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        "offhost",
        "skill",
        "offHostSkill",
        "跨主机",
        "1.0.0",
        "offHostSkill.zip",
        artifact("offhost.zip"),
        null);

    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/install"), admin, Map.of("pluginId", "offhost")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PLUGIN_SOURCE_FORBIDDEN"));
  }

  @Test
  void collectionTogglePersistsPerPlugin() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/plugin-market/collections/toggle"),
                admin,
                Map.of("pluginId", "74")))
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
                Map.of("pluginId", "74")))
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
