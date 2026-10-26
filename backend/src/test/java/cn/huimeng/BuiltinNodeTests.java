package cn.huimeng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.dao.mapper.PluginMapper;
import cn.huimeng.service.UserService;
import cn.huimeng.service.impl.BuiltinNodeRegistrar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 内置节点包登记：随部署分发的 {@code capabilities/nodes/*.umd.js} 启动登记为 {@code source=builtin}。
 *
 * <p>覆盖摘要幂等、目录与脚本分发、管理员启停状态跨重启保留、摘要变化后的更新与内置卸载拒绝，以及坏产物的 跳过策略（WARN 后继续，不阻断启动）。
 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class BuiltinNodeTests {
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
      PASSWORD = "synthetic-account-password-42",
      BUILTIN_LOCATION = "classpath*:capabilities/nodes/*.umd.js",
      ROOT_PACKAGE = "capabilities/nodes/";
  // 与 classpath 下的产物同名同序（registrar 按文件名排序登记）。
  static final List<String> BUILTIN_NODES =
      List.of(
          "audioGenerationNode",
          "audioNode",
          "director3dNode",
          "imageGenerationNode",
          "imageNode",
          "textNode",
          "videoGenerationNode",
          "videoNode");
  static final int TEXT_INDEX = BUILTIN_NODES.indexOf("textNode");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserService users;
  @Autowired PluginMapper mapper;
  @Autowired BuiltinNodeRegistrar registrar;
  MockHttpSession admin;

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
    admin = login("admin", PASSWORD);
  }

  @Test
  void registersEachBuiltinNodeOnceAndPublishesCatalogUrls() throws Exception {
    assertThat(registrar.sync())
        .extracting(BuiltinNodeRegistrar.Outcome::change)
        .containsOnly(BuiltinNodeRegistrar.Change.INSERTED);
    assertThat(builtinNames()).containsExactlyElementsOf(BUILTIN_NODES);

    for (String name : BUILTIN_NODES) {
      byte[] artifact = artifact(name);
      assertThat(column("sha256", name)).as(name).isEqualTo(sha256(artifact));
      assertThat(column("version", name)).isEqualTo("2.0.0");
      assertThat(column("installed_by", name)).isEqualTo("builtin");
      assertThat(enabled(name)).as(name).isTrue();
      assertThat(mapper.content("node:" + name)).isEqualTo(artifact);
    }
    Map<String, Timestamp> stamps = updatedAt();

    // 第二次登记：摘要一致，一行不增、一个字段不动。
    assertThat(registrar.sync())
        .extracting(BuiltinNodeRegistrar.Outcome::change)
        .containsOnly(BuiltinNodeRegistrar.Change.UNCHANGED);
    assertThat(builtinNames()).containsExactlyElementsOf(BUILTIN_NODES);
    assertThat(updatedAt()).isEqualTo(stamps);

    mvc.perform(get("/api/v1/capabilities/nodes/get").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(BUILTIN_NODES.size()))
        .andExpect(jsonPath("$.data[0].name").value("audioGenerationNode"))
        .andExpect(jsonPath("$.data[0].displayName").value("音频生成"))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(
            jsonPath("$.data[0].url")
                .value("/api/v1/capabilities/nodes/files?name=audioGenerationNode"))
        .andExpect(jsonPath("$.data[" + TEXT_INDEX + "].name").value("textNode"))
        .andExpect(jsonPath("$.data[" + TEXT_INDEX + "].displayName").value("文本"))
        .andExpect(jsonPath("$.data[" + TEXT_INDEX + "].enabled").value(true))
        .andExpect(
            jsonPath("$.data[" + TEXT_INDEX + "].url")
                .value("/api/v1/capabilities/nodes/files?name=textNode"));
  }

  @Test
  void servesClasspathArtifactBytesWithDigestEtag() throws Exception {
    registrar.sync();
    for (String name : BUILTIN_NODES) {
      byte[] artifact = artifact(name);
      MvcResult result =
          mvc.perform(get("/api/v1/capabilities/nodes/files").param("name", name).session(admin))
              .andExpect(status().isOk())
              .andExpect(content().contentTypeCompatibleWith("text/javascript"))
              .andExpect(content().bytes(artifact))
              .andReturn();
      assertThat(result.getResponse().getHeader("ETag"))
          .as(name)
          .isEqualTo("\"" + sha256(artifact) + "\"");
      assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
  }

  @Test
  void keepsAdministratorDisabledStateAndConfigAcrossRestart() throws Exception {
    registrar.sync();
    mvc.perform(
            write(
                put("/api/v1/admin/nodes/setEnabled"),
                Map.of("name", "textNode", "enabled", false)))
        .andExpect(status().isOk());
    mvc.perform(
            write(
                post("/api/v1/admin/plugins/node/textNode/config"),
                Map.of("config", Map.of("label", "管理员改过的标签"))))
        .andExpect(status().isOk());
    Map<String, Timestamp> stamps = updatedAt();

    // 新实例等价于进程重启：内置登记再次扫描同一批产物。
    assertThat(new BuiltinNodeRegistrar(mapper, json, BUILTIN_LOCATION).sync())
        .extracting(BuiltinNodeRegistrar.Outcome::change)
        .containsOnly(BuiltinNodeRegistrar.Change.UNCHANGED);
    assertThat(enabled("textNode")).isFalse();
    assertThat(column("config_json", "textNode")).contains("管理员改过的标签");
    assertThat(updatedAt()).isEqualTo(stamps);

    mvc.perform(get("/api/v1/capabilities/nodes/get").session(admin))
        .andExpect(jsonPath("$.data[" + TEXT_INDEX + "].name").value("textNode"))
        .andExpect(jsonPath("$.data[" + TEXT_INDEX + "].enabled").value(false));
    mvc.perform(get("/api/v1/capabilities/nodes/files").param("name", "textNode").session(admin))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_FOUND"));
  }

  @Test
  void updatesChangedArtifactAndKeepsAdminState(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("customNode.umd.js");
    Files.write(file, artifact("customNode", "1.0.0", "first"));
    BuiltinNodeRegistrar local = new BuiltinNodeRegistrar(mapper, json, dir.toUri() + "*.umd.js");
    assertThat(local.sync())
        .extracting(BuiltinNodeRegistrar.Outcome::change)
        .containsExactly(BuiltinNodeRegistrar.Change.INSERTED);
    Timestamp createdAt = created("customNode");

    mvc.perform(
            write(
                put("/api/v1/admin/nodes/setEnabled"),
                Map.of("name", "customNode", "enabled", false)))
        .andExpect(status().isOk());
    mvc.perform(
            write(
                post("/api/v1/admin/plugins/node/customNode/config"),
                Map.of("config", Map.of("label", "保留配置"))))
        .andExpect(status().isOk());

    // 同一标识换发新产物：内容与元数据更新，启停状态与配置保持。
    byte[] next = artifact("customNode", "2.0.0", "second");
    Files.write(file, next);
    assertThat(local.sync())
        .extracting(BuiltinNodeRegistrar.Outcome::change)
        .containsExactly(BuiltinNodeRegistrar.Change.UPDATED);
    assertThat(column("sha256", "customNode")).isEqualTo(sha256(next));
    assertThat(column("version", "customNode")).isEqualTo("2.0.0");
    assertThat(column("installed_by", "customNode")).isEqualTo("builtin");
    assertThat(column("config_json", "customNode")).contains("保留配置");
    assertThat(enabled("customNode")).isFalse();
    assertThat(created("customNode")).isEqualTo(createdAt);
    assertThat(mapper.content("node:customNode")).isEqualTo(next);
    assertThat(column("manifest_json", "customNode")).contains("\"version\":\"2.0.0\"");
    assertThat(builtinNames()).containsExactly("customNode");

    assertThat(local.sync())
        .extracting(BuiltinNodeRegistrar.Outcome::change)
        .containsExactly(BuiltinNodeRegistrar.Change.UNCHANGED);
    assertThat(builtinNames()).containsExactly("customNode");
  }

  @Test
  void skipsBrokenArtifactWithoutBlockingStartup(@TempDir Path dir) throws Exception {
    Files.write(dir.resolve("goodNode.umd.js"), artifact("goodNode", "1.0.0", "ok"));
    Files.writeString(dir.resolve("badNode.umd.js"), "console.log('no metadata header');\n");
    Files.write(
        dir.resolve("hugeNode.umd.js"), artifact("hugeNode", "1.0.0", "x".repeat(25000), "huge"));
    BuiltinNodeRegistrar local = new BuiltinNodeRegistrar(mapper, json, dir.toUri() + "*.umd.js");

    assertThat(local.sync())
        .containsExactlyInAnyOrder(
            new BuiltinNodeRegistrar.Outcome("badNode", BuiltinNodeRegistrar.Change.SKIPPED),
            new BuiltinNodeRegistrar.Outcome("goodNode", BuiltinNodeRegistrar.Change.INSERTED),
            new BuiltinNodeRegistrar.Outcome("hugeNode", BuiltinNodeRegistrar.Change.SKIPPED));
    assertThat(builtinNames()).containsExactly("goodNode");
  }

  @Test
  void prunesRemovedArtifactButKeepsRowsWhenNothingIsScanned(@TempDir Path dir) throws Exception {
    Files.write(dir.resolve("goodNode.umd.js"), artifact("goodNode", "1.0.0", "ok"));
    Files.write(dir.resolve("goneNode.umd.js"), artifact("goneNode", "1.0.0", "ok"));
    BuiltinNodeRegistrar local = new BuiltinNodeRegistrar(mapper, json, dir.toUri() + "*.umd.js");
    local.sync();
    assertThat(builtinNames()).containsExactly("goneNode", "goodNode");

    // 产物下线后重扫：只删下线条目，管理员配置一并移除。
    Files.delete(dir.resolve("goneNode.umd.js"));
    local.sync();
    assertThat(builtinNames()).containsExactly("goodNode");

    // 一个产物都扫不到（资源读取失败或目录为空）时不清理，避免误删全部内置节点。
    BuiltinNodeRegistrar empty =
        new BuiltinNodeRegistrar(mapper, json, dir.resolve("nothing").toUri() + "*.umd.js");
    assertThat(empty.sync()).isEmpty();
    assertThat(builtinNames()).containsExactly("goodNode");
  }

  @Test
  void rejectsUninstallingBuiltinNode() throws Exception {
    registrar.sync();

    mvc.perform(
            delete("/api/v1/admin/nodes/uninstall")
                .param("name", "textNode")
                .session(admin)
                .secure(true)
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PLUGIN_BUILTIN"));
    assertThat(builtinNames()).containsExactlyElementsOf(BUILTIN_NODES);

    mvc.perform(get("/api/v1/admin/plugins").session(admin).secure(true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(BUILTIN_NODES.size()))
        .andExpect(jsonPath("$.items[" + TEXT_INDEX + "].name").value("textNode"))
        .andExpect(jsonPath("$.items[" + TEXT_INDEX + "].source").value("builtin"))
        .andExpect(jsonPath("$.items[" + TEXT_INDEX + "].canManage").value(false));
  }

  // --- 夹具 -----------------------------------------------------------------

  private static byte[] artifact(String name) throws Exception {
    return new ClassPathResource(ROOT_PACKAGE + name + ".umd.js").getInputStream().readAllBytes();
  }

  private static byte[] artifact(String module, String version, String body) {
    return artifact(module, version, "# 演示", body);
  }

  private static byte[] artifact(String module, String version, String readme, String body) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("displayName", "演示节点");
    metadata.put("author", "synthetic");
    metadata.put("github", "");
    metadata.put("configRules", List.of());
    metadata.put("version", version);
    metadata.put("readme", readme);
    String header;
    try {
      header = new ObjectMapper().writeValueAsString(metadata);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
    return ("/*! toonflowNode:"
            + header
            + " */\nwindow.toonflowNodes = window.toonflowNodes || {};\n"
            + "window.toonflowNodes."
            + module
            + " = { render: function () { return null; } }; // "
            + body
            + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private List<String> builtinNames() {
    return jdbc.queryForList(
        "SELECT name FROM plugins WHERE type='node' AND source='builtin' ORDER BY name",
        String.class);
  }

  private boolean enabled(String name) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT enabled FROM plugins WHERE type='node' AND name=?", Boolean.class, name));
  }

  private String column(String field, String name) {
    return jdbc.queryForObject(
        "SELECT " + field + " FROM plugins WHERE type='node' AND name=?", String.class, name);
  }

  private Timestamp created(String name) {
    return jdbc.queryForObject(
        "SELECT created_at FROM plugins WHERE type='node' AND name=?", Timestamp.class, name);
  }

  private Map<String, Timestamp> updatedAt() {
    Map<String, Timestamp> stamps = new LinkedHashMap<>();
    jdbc.query(
        "SELECT name,updated_at FROM plugins WHERE type='node' AND source='builtin' ORDER BY name",
        rs -> {
          stamps.put(rs.getString("name"), rs.getTimestamp("updated_at"));
        });
    return stamps;
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
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

  private String csrf() throws Exception {
    return body(mvc.perform(get("/api/v1/auth/csrf").secure(true).session(admin)).andReturn())
        .get("token")
        .asText();
  }

  private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Object input)
      throws Exception {
    return request
        .secure(true)
        .session(admin)
        .header("Origin", ORIGIN)
        .header("X-CSRF-Token", csrf())
        .contentType("application/json")
        .content(json.writeValueAsBytes(input));
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }
}
