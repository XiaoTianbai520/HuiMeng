package cn.huimeng;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.dto.req.CreateUserReqDTO;
import cn.huimeng.security.Actor;
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

/** 自定义媒体供应商登记面：解析声明、编辑清单、删除与越权拒绝；来源文本不回传。 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class MediaProviderTests {
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
  static final String SOURCE =
      """
      const rules = [{ field: "apiKey", title: "API Key" }] as const;
      const version = "2.1.0";
      export default {
        name: "验收供应商",
        version,
        models: [
          { id: "probeImage", label: "验收图片", type: "image" },
          {
            id: "probeVideo",
            label: "验收视频",
            type: "video",
            note: "含 ] 与 [ 的字符串不参与括号配对",
            durationResolutionMap: [{ duration: [4, 8], resolution: ["720p"] }],
          },
        ],
        async generate() { throw new Error("fixture 不执行生成"); },
      };
      """;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserService users;
  MockHttpSession admin;
  String memberId;

  @BeforeEach
  void setup() throws Exception {
    jdbc.update("DELETE FROM media_providers");
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
    // users.create 需要管理员上下文；这里直接给出与登录后一致的 Actor。
    String adminId =
        jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", String.class);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new Actor(adminId, "admin", "ADMIN", "ACTIVE", 1), null, List.of()));
    memberId = users.create(new CreateUserReqDTO("member", PASSWORD, "USER"), "provider").id();
    SecurityContextHolder.clearContext();
    admin = login("admin", PASSWORD);
  }

  @Test
  void registersProviderAndExposesDeclaredModelsWithoutSource() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/providers/media/add"),
                admin,
                Map.of("fileName", "probe.ts", "source", SOURCE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fileName").value("probe.ts"))
        .andExpect(jsonPath("$.data.displayName").value("验收供应商"))
        .andExpect(jsonPath("$.data.version").value("2.1.0"))
        // 生成仍走内置供应商：登记成功不等于可用。
        .andExpect(jsonPath("$.data.executable").value(false))
        .andExpect(jsonPath("$.data.models.length()").value(2))
        .andExpect(jsonPath("$.data.models[0].id").value("probeImage"))
        .andExpect(jsonPath("$.data.models[1].id").value("probeVideo"))
        .andExpect(
            jsonPath("$.data.models[1].declaration.durationResolutionMap[0].duration[0]").value(4));

    mvc.perform(get("/api/v1/admin/providers/media/list").session(admin).secure(true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].fileName").value("probe.ts"))
        .andExpect(jsonPath("$.data[0].models.length()").value(2))
        // 来源文本不回传。
        .andExpect(jsonPath("$.data[0].source").doesNotExist());
  }

  @Test
  void editsModelsRefreshesAndDeletesProvider() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/providers/media/add"),
                admin,
                Map.of("fileName", "probe.ts", "source", SOURCE)))
        .andExpect(status().isOk());

    mvc.perform(
            write(
                put("/api/v1/admin/providers/media/save"),
                admin,
                Map.of(
                    "fileName",
                    "probe.ts",
                    "models",
                    List.of(Map.of("id", "probeVideo", "label", "只留视频", "type", "video")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.models.length()").value(1))
        .andExpect(jsonPath("$.data.models[0].id").value("probeVideo"))
        .andExpect(jsonPath("$.data.models[0].label").value("只留视频"));

    mvc.perform(
            write(
                post("/api/v1/admin/providers/media/models"),
                admin,
                Map.of("fileName", "probe.ts")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.models.length()").value(2));

    mvc.perform(
            write(
                delete("/api/v1/admin/providers/media/delete"),
                admin,
                Map.of("fileName", "probe.ts")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/providers/media/list").session(admin).secure(true))
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void rejectsSourceWithoutModelsAndNonAdmin() throws Exception {
    mvc.perform(
            write(
                post("/api/v1/admin/providers/media/add"),
                admin,
                Map.of("fileName", "broken.ts", "source", "export default { name: 'x' };\n")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MEDIA_PROVIDER_FORMAT"));

    MockHttpSession member = login("member", PASSWORD);
    mvc.perform(get("/api/v1/admin/providers/media/list").session(member).secure(true))
        .andExpect(status().isForbidden());
  }

  // --- 夹具 -----------------------------------------------------------------

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
