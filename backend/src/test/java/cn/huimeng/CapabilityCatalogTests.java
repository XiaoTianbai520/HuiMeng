package cn.huimeng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.dto.req.CreateProjectReqDTO;
import cn.huimeng.dto.req.CreateUserReqDTO;
import cn.huimeng.security.Actor;
import cn.huimeng.service.ProjectService;
import cn.huimeng.service.UserService;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
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

/** 能力目录运行期分发：已安装节点/工具脚本、启停、摘要版本与秘密配置不下发。 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class CapabilityCatalogTests {
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
      NODE_SOURCE =
          "// @version 1.2.3\nwindow.toonflowNodes = window.toonflowNodes || {};\n"
              + "window.toonflowNodes.demoNode = { render: function () { return null; } };\n",
      TOOL_SOURCE =
          "/*! toonflowTool:{\"name\":\"mediaGeneration\",\"version\":\"2.0.0\",\"displayName\":\"媒体生成\","
              + "\"description\":\"验收用工具包\",\"author\":\"fixture\",\"github\":\"\","
              + "\"components\":[\"imageGenerate\",\"videoGenerate\"],\"configRules\":[]} */\n"
              + "/*! toonflowToolClient:{\"code\":\"window.toonflowToolViews ="
              + " window.toonflowToolViews || {};\",\"css\":\".mg{color:#fff}\"} */\n"
              + "export default { validateConfig() { return {}; }, createTools() { return []; } };\n";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserService users;
  @Autowired ProjectService projects;
  MockHttpSession admin;
  String adminId, memberId;

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
    asAdmin();
    memberId = users.create(new CreateUserReqDTO("member", PASSWORD, "USER"), "capability").id();
    SecurityContextHolder.clearContext();
    admin = login("admin", PASSWORD);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void publishesInstalledNodeAndServesScript() throws Exception {
    installNode();

    mvc.perform(get("/api/v1/capabilities/nodes/get").session(login("member", PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("demoNode"))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(
            jsonPath("$.data[0].url").value("/api/v1/capabilities/nodes/files?name=demoNode"));

    MvcResult script =
        mvc.perform(
                get("/api/v1/capabilities/nodes/files")
                    .param("name", "demoNode")
                    .session(login("member", PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/javascript"))
            .andExpect(content().bytes(NODE_SOURCE.getBytes(StandardCharsets.UTF_8)))
            .andReturn();
    assertThat(script.getResponse().getHeader("ETag")).isEqualTo("\"" + sha256(NODE_SOURCE) + "\"");
    assertThat(script.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

    mvc.perform(get("/api/v1/capabilities/nodes/get")).andExpect(status().isUnauthorized());
  }

  @Test
  void hidesDisabledAndUnknownNodeScripts() throws Exception {
    installNode();
    mvc.perform(
            write(
                put("/api/v1/admin/nodes/setEnabled"),
                admin,
                Map.of("name", "demoNode", "enabled", false)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/capabilities/nodes/get").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].enabled").value(false));
    mvc.perform(get("/api/v1/capabilities/nodes/files").param("name", "demoNode").session(admin))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_FOUND"));
    mvc.perform(get("/api/v1/capabilities/nodes/files").param("name", "missingNode").session(admin))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/capabilities/nodes/files").param("name", "../demoNode").session(admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void publishesToolRendererWithVersionedClient() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/tools/install"),
                admin,
                Map.of("fileName", "mediaGeneration.tool.js", "source", TOOL_SOURCE)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/capabilities/tools/get").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.canManage").value(false))
        .andExpect(jsonPath("$.data.tools[0].name").value("canvasOperations"))
        .andExpect(jsonPath("$.data.tools[1].name").value("mediaGeneration"))
        .andExpect(jsonPath("$.data.tools[1].enabled").value(true));

    String version = sha256(TOOL_SOURCE);
    mvc.perform(get("/api/v1/capabilities/tools/renderers").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("mediaGeneration"))
        // 界面名来自包头 components（真实工具名），不是包名。
        .andExpect(jsonPath("$.data[0].tools.length()").value(2))
        .andExpect(jsonPath("$.data[0].tools[0]").value("imageGenerate"))
        .andExpect(jsonPath("$.data[0].tools[1]").value("videoGenerate"))
        .andExpect(
            jsonPath("$.data[0].url")
                .value(
                    "/api/v1/capabilities/tools/client?name=mediaGeneration&version=" + version));

    mvc.perform(
            get("/api/v1/capabilities/tools/client")
                .param("name", "mediaGeneration")
                .param("version", version)
                .session(admin))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/javascript"))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("window.toonflowToolViews")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "const id = \"toonflowToolStyle:mediaGeneration\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(".mg{color:#fff}")))
        // 下发的是内嵌界面脚本，不是承载 createTools 的 ESM 正文。
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("export default"))));
    mvc.perform(
            get("/api/v1/capabilities/tools/client")
                .param("name", "mediaGeneration")
                .param("version", "0".repeat(64))
                .session(admin))
        .andExpect(status().isNotFound());

    mvc.perform(
            write(
                put("/api/v1/admin/tools/setEnabled"),
                admin,
                Map.of("name", "mediaGeneration", "enabled", false)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/capabilities/tools/renderers").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  /** components 为空表示纯 Agent 工具：目录可见，但没有界面，也不该占渲染器名额。 */
  @Test
  void keepsUiLessToolOutOfRenderersAndReportsBrokenMetadata() throws Exception {
    String pure =
        "/*! toonflowTool:{\"name\":\"pureAgent\",\"version\":\"1.0.0\",\"displayName\":\"纯工具\","
            + "\"description\":\"无界面\",\"author\":\"fixture\",\"github\":\"\",\"components\":[],"
            + "\"configRules\":[]} */\n"
            + "export default { validateConfig() { return {}; }, createTools() { return []; } };\n";
    mvc.perform(
            write(
                post("/api/v1/admin/tools/install"),
                admin,
                Map.of("fileName", "pureAgent.tool.js", "source", pure)))
        .andExpect(status().isOk());
    mvc.perform(
            write(
                post("/api/v1/admin/tools/install"),
                admin,
                Map.of("fileName", "brokenTool.tool.js", "source", "// 缺少包头\n")))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/capabilities/tools/renderers").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    mvc.perform(get("/api/v1/capabilities/tools/get").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tools[1].name").value("brokenTool"))
        .andExpect(jsonPath("$.data.tools[1].description").value("工具元数据无效，无法加载"))
        .andExpect(jsonPath("$.data.tools[2].name").value("pureAgent"))
        .andExpect(jsonPath("$.data.tools[2].description").value("无界面"));

    mvc.perform(
            get("/api/v1/capabilities/tools/client")
                .param("name", "pureAgent")
                .param("version", sha256(pure))
                .session(admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void listsInstalledSkillsForProjectMembers() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/skills/install"),
                admin,
                Map.of("fileName", "demoSkill.md", "source", "# 演示技能\n\n用于能力目录验收。\n")))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/capabilities/mcp/skill").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.skills[0].name").value("demoSkill"));

    asAdmin();
    var project = projects.create(new CreateProjectReqDTO("能力目录验收"), "capability");
    projects.addMember(project.id(), memberId, "capability");
    SecurityContextHolder.clearContext();
    MockHttpSession member = login("member", PASSWORD);
    mvc.perform(get("/api/v1/projects/" + project.id() + "/creative/agent/skills").session(member))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("demoSkill"));
  }

  @Test
  void stripsSecretConfigFromNodeCatalog() throws Exception {
    installNode();
    mvc.perform(
            write(
                post("/api/v1/admin/plugins/node/demoNode/config"),
                admin,
                Map.of("config", Map.of("label", "演示节点", "apiKey", "super-secret"))))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/capabilities/nodes/get").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].config.label").value("演示节点"))
        .andExpect(jsonPath("$.data[0].config.apiKey").doesNotExist());
  }

  private void installNode() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/nodes/install"),
                admin,
                Map.of("fileName", "demoNode.umd.js", "source", NODE_SOURCE)))
        .andExpect(status().isOk());
  }

  private void asAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new Actor(adminId, "admin", "ADMIN", "ACTIVE", 1), null, List.of()));
  }

  private static String sha256(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
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

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }
}
