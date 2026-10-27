package cn.huimeng;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.huimeng.common.exception.*;
import cn.huimeng.common.storage.*;
import cn.huimeng.dto.req.CreateUserReqDTO;
import cn.huimeng.dto.req.IssueExternalClientReqDTO;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
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
  @Autowired ProjectMediaService projectMedia;
  @Autowired MediaGenerationService generation;
  @Autowired cn.huimeng.dao.mapper.GenerationRunMapper generationRuns;
  @org.springframework.boot.test.mock.mockito.MockBean cn.huimeng.common.io.ComfyImageClient comfy;
  @org.springframework.boot.test.mock.mockito.MockBean ObjectStorage objectStorage;
  final Map<String, byte[]> mediaObjects = new HashMap<>();
  MockHttpSession admin;
  String adminId;

  @BeforeEach
  void setup() throws Exception {
    for (String table :
        List.of(
            "audit_events",
            "project_media",
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
    when(comfy.history(anyString()))
        .thenReturn(json.readTree("{\"status\":{\"status_str\":\"success\",\"completed\":true}}"));
    when(comfy.output(anyString(), anyString(), any()))
        .thenReturn(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1});
    when(comfy.submit(anyString(), anyString(), anyString(), any(), anyString(), any()))
        .thenAnswer(call -> call.getArgument(1));
    when(comfy.cancel(anyString())).thenReturn(true);
    when(comfy.active(anyString())).thenReturn(false);
    mediaObjects.clear();
    doAnswer(
            call -> {
              String key = call.getArgument(0);
              byte[] bytes = ((InputStream) call.getArgument(1)).readAllBytes();
              mediaObjects.put(key, bytes);
              return null;
            })
        .when(objectStorage)
        .put(anyString(), any(InputStream.class), anyLong());
    when(objectStorage.head(anyString()))
        .thenAnswer(
            call -> {
              byte[] bytes = mediaObjects.get(call.getArgument(0));
              if (bytes == null) throw S3ObjectStorage.unavailable();
              return new ObjectStorage.StoredObject(bytes.length, "synthetic-etag");
            });
    when(objectStorage.get(anyString(), anyString(), nullable(String.class)))
        .thenAnswer(
            call -> {
              byte[] bytes = mediaObjects.get(call.getArgument(0));
              String range = call.getArgument(2);
              if (range != null) {
                String[] ends = range.substring(6).split("-");
                bytes =
                    Arrays.copyOfRange(
                        bytes, Integer.parseInt(ends[0]), Integer.parseInt(ends[1]) + 1);
              }
              return new ByteArrayInputStream(bytes);
            });
    doAnswer(
            call -> {
              mediaObjects.remove(call.getArgument(0));
              return null;
            })
        .when(objectStorage)
        .delete(anyString());
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  Map<String, Object> imageInput() {
    return Map.of(
        "providerId",
        "qwenImage21Local",
        "modelId",
        "qwenImage21Text",
        "mediaType",
        "image",
        "prompt",
        "synthetic mug",
        "size",
        "1K",
        "ratio",
        "1:1");
  }

  void approveImage() {
    jdbc.update(
        "UPDATE approved_models SET enabled=TRUE WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'");
  }

  void approveEdit() {
    jdbc.update(
        "UPDATE approved_models SET enabled=TRUE WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Edit'");
  }

  /** 直接登记一条 READY 媒体并写入合成对象，供图生图参考图使用。 */
  String seedMedia(String project) {
    String id = UUID.randomUUID().toString();
    byte[] bytes = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 2, 3, 4};
    mediaObjects.put("projects/" + project + "/media/" + id, bytes);
    jdbc.update(
        "INSERT INTO project_media(id,project_id,object_key,filename,byte_size,sha256,state,created_by,created_at) VALUES (?,?,?,?,?,?,'READY',?,?)",
        id,
        project,
        "projects/" + project + "/media/" + id,
        "reference.png",
        bytes.length,
        "synthetic-hash",
        adminId,
        java.sql.Timestamp.from(Instant.now()));
    return id;
  }

  String imagePath(String project) {
    return "/api/v1/projects/" + project + "/creative/ai/media/generate";
  }

  @Test
  void workspaceOperationsCheckMembershipCsrfAndRejectLegacyDirectory() throws Exception {
    String project = createProject("workspace-gates");
    String base = "/api/v1/projects/" + project + "/creative/workspaces/files";
    mvc.perform(get(base + "/list").session(admin).secure(true)).andExpect(status().isForbidden());
    mvc.perform(write(post(base + "/mkdir"), admin, Map.of("path", "folder")))
        .andExpect(status().isForbidden());
    for (String operation : List.of("copy", "rename"))
      mvc.perform(
              write(post(base + "/" + operation), admin, Map.of("path", "file", "target", "other")))
          .andExpect(status().isForbidden());
    member(project, adminId);
    mvc.perform(
            write(post(base + "/mkdir"), admin, Map.of("path", "folder", "directory", "/private")))
        .andExpect(status().isBadRequest());
    mvc.perform(write(post(base + "/copy"), admin, Map.of("path", "file", "target", "../outside")))
        .andExpect(status().isBadRequest());
    mvc.perform(write(delete(base + "/remove"), admin, Map.of("path", "")))
        .andExpect(status().isBadRequest());
    mvc.perform(get(base + "/list").session(admin).secure(true).param("directory", "/private"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(base + "/mkdir")
                .session(admin)
                .secure(true)
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"path\":\"folder\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void workspaceRootListDoesNotExposePhysicalDirectoryOrEscape() throws Exception {
    String project = createProject("workspace-list");
    member(project, adminId);
    String route = "/api/v1/projects/" + project + "/creative/workspaces/files/list";
    String key =
        jdbc.queryForObject("SELECT workspace_key FROM projects WHERE id=?", String.class, project);
    Path root = Path.of("target/test-workspaces", key);
    Files.writeString(root.resolve("visible.md"), "public");
    Files.writeString(root.resolve(".huimeng-private"), "internal");
    boolean supported;
    try (var directory = Files.newDirectoryStream(root)) {
      supported = directory instanceof SecureDirectoryStream<?>;
    }
    var result = mvc.perform(get(route).secure(true).session(admin));
    if (supported)
      result
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.directory").value(project))
          .andExpect(jsonPath("$.data.entries.length()").value(1))
          .andExpect(jsonPath("$.data.entries[0].path").value("visible.md"));
    else result.andExpect(status().isServiceUnavailable());
    mvc.perform(get(route).secure(true).session(admin).param("path", ".."))
        .andExpect(status().isBadRequest());
  }

  MockHttpServletRequestBuilder fileWrite(String project, byte[] content) throws Exception {
    return write(
            put("/api/v1/projects/" + project + "/creative/workspaces/files/write"),
            admin,
            Map.of())
        .contentType("application/octet-stream")
        .content(content);
  }

  @Test
  void fileWritesRequireCsrfMembershipAndRejectClaimedDirectories() throws Exception {
    String project = createProject("write-gates");
    String route = "/api/v1/projects/" + project + "/creative/workspaces/files/write";
    mvc.perform(
            put(route)
                .secure(true)
                .session(admin)
                .header("Origin", ORIGIN)
                .contentType("application/octet-stream")
                .content("x")
                .param("path", "a.txt")
                .param("exclusive", "true"))
        .andExpect(status().isForbidden());
    mvc.perform(fileWrite(project, new byte[0]).param("path", "a.txt").param("exclusive", "true"))
        .andExpect(status().isForbidden());
    member(project, adminId);
    mvc.perform(
            fileWrite(project, new byte[0])
                .param("path", "a.txt")
                .param("exclusive", "true")
                .param("directory", "/tmp"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            fileWrite(project, new byte[0]).param("path", "../a.txt").param("exclusive", "true"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            fileWrite(project, new byte[0])
                .param("path", ".huimeng-media/a.png")
                .param("exclusive", "true"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void fileWriteRequiresExactlyOneRevisionOrExclusiveAndStrictEncoding() throws Exception {
    String project = createProject("write-input");
    member(project, adminId);
    mvc.perform(fileWrite(project, new byte[0]).param("path", "a.txt"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            fileWrite(project, new byte[0])
                .param("path", "a.txt")
                .param("exclusive", "true")
                .param("expectedRevision", "a".repeat(64)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            fileWrite(project, new byte[0]).param("path", "a.txt").param("expectedRevision", "bad"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            fileWrite(project, new byte[0])
                .param("path", "a.txt", "b.txt")
                .param("exclusive", "true"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            fileWrite(project, new byte[0])
                .param("path", "a.txt")
                .param("exclusive", "true")
                .param("encoding", "legacy"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            fileWrite(project, new byte[] {(byte) 255})
                .param("path", "a.txt")
                .param("exclusive", "true")
                .param("encoding", "utf-8"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void fileWriteUsesSafeAtomicRevisionOrRefusesUnsupportedFilesystem() throws Exception {
    String project = createProject("write-real-file");
    member(project, adminId);
    String key =
        jdbc.queryForObject("SELECT workspace_key FROM projects WHERE id=?", String.class, project);
    Path base = Path.of("target/test-workspaces", key), target = base.resolve("剧本.md");
    Files.writeString(target, "original");
    String revision =
        cn.huimeng.common.io.SecureFileWriter.revision(
            "original".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    boolean supported;
    try (var dir = Files.newDirectoryStream(base)) {
      supported = dir instanceof SecureDirectoryStream<?>;
    }
    var result =
        mvc.perform(
            fileWrite(project, "已保存\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .param("path", "剧本.md")
                .param("expectedRevision", revision)
                .param("encoding", "utf-8"));
    if (!supported) {
      result.andExpect(status().isServiceUnavailable());
      assertThat(Files.readString(target)).isEqualTo("original");
    } else {
      var saved = body(result.andExpect(status().isOk()).andReturn());
      String next = saved.at("/data/revision").asText();
      assertThat(Files.readString(target)).isEqualTo("已保存\r\n");
      mvc.perform(
              fileWrite(project, "stale".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .param("path", "剧本.md")
                  .param("expectedRevision", revision))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
          .andExpect(jsonPath("$.details.currentRevision").value(next));
      assertThat(Files.readString(target)).isEqualTo("已保存\r\n");
    }
    try (var entries = Files.list(base)) {
      assertThat(entries.noneMatch(p -> p.getFileName().toString().startsWith(".huimeng-write-")))
          .isTrue();
    }
  }

  void awaitReleased(String project) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (generationRuns.held(project) == 0) return;
      Thread.sleep(50);
    }
    fail("Generation capacity was not released");
  }

  @Test
  void mediaGenerationChecksMembershipApprovalAndInputBeforeCallingExecutor() throws Exception {
    String project = createProject("generate-gates");
    mvc.perform(
            write(post(imagePath(project)).header("Idempotency-Key", "guard"), admin, imageInput()))
        .andExpect(status().isForbidden());
    member(project, adminId);
    mvc.perform(
            write(post(imagePath(project)).header("Idempotency-Key", "guard"), admin, imageInput()))
        .andExpect(status().isForbidden());
    approveImage();
    var input = new HashMap<>(imageInput());
    input.put("directory", "/private");
    mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "guard"), admin, input))
        .andExpect(status().isBadRequest());
    input = new HashMap<>(imageInput());
    input.put("size", "4K");
    mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "guard"), admin, input))
        .andExpect(status().isBadRequest());
    mvc.perform(write(post(imagePath(project)), admin, imageInput()))
        .andExpect(status().isBadRequest());
    verify(comfy, never()).submit(anyString(), anyString(), anyString(), any(), anyString(), any());
    assertThat(generationRuns.held(project)).isZero();
  }

  @Test
  void unavailableExecutorIsRejectedWithoutConsumingProjectCapacity() throws Exception {
    String project = createProject("generate-offline");
    member(project, adminId);
    approveImage();
    doThrow(cn.huimeng.common.io.ComfyImageClient.unavailable()).when(comfy).checkReady();
    mvc.perform(
            write(
                post(imagePath(project)).header("Idempotency-Key", "offline"), admin, imageInput()))
        .andExpect(status().isServiceUnavailable());
    assertThat(generationRuns.held(project)).isZero();
    verify(comfy, never()).submit(anyString(), anyString(), anyString(), any(), anyString(), any());
  }

  @Test
  void approvedGenerationPersistsProjectMediaAndDeduplicatesRetries() throws Exception {
    String project = createProject("generate-success");
    member(project, adminId);
    approveImage();
    var initial =
        mvc.perform(
                write(
                    post(imagePath(project)).header("Idempotency-Key", "same"),
                    admin,
                    imageInput()))
            .andExpect(request().asyncStarted())
            .andReturn();
    String run =
        jdbc.queryForObject(
            "SELECT id FROM generation_runs WHERE project_id=?", String.class, project);
    var result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
    String text = result.getResponse().getContentAsString();
    JsonNode response = json.readTree(text);
    assertThat(response.path("code").asInt()).isEqualTo(200);
    assertThat(response.path("data").get(0).path("spec").asText()).isEqualTo("1K 1:1 1024×1024");
    String path = response.path("data").get(0).path("path").asText();
    mvc.perform(
            get("/api/v1/projects/" + project + "/creative/workspaces/files/read")
                .param("path", path)
                .session(admin))
        .andExpect(status().isOk())
        .andExpect(content().bytes(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1}));
    mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run).session(admin))
        .andExpect(jsonPath("$.state").value("SUCCEEDED"))
        .andExpect(jsonPath("$.capacityHeld").value(false));
    mvc.perform(
            write(post(imagePath(project)).header("Idempotency-Key", "same"), admin, imageInput()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(run));
    var changed = new HashMap<>(imageInput());
    changed.put("prompt", "different");
    mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "same"), admin, changed))
        .andExpect(status().isConflict());
    verify(comfy, times(1)).submit(eq(project), eq(run), anyString(), any(), anyString(), any());
  }

  @Test
  void editModelUsesProjectReferenceAndEchoesRequestedTwoKiloWideSpec() throws Exception {
    String project = createProject("generate-edit");
    member(project, adminId);
    approveEdit();
    String mediaId = seedMedia(project);
    when(comfy.submit(
            eq(project), anyString(), eq("qwenImage21Edit"), any(), anyString(), any(byte[].class)))
        .thenReturn("3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f");
    var input = new HashMap<>(imageInput());
    input.put("modelId", "qwenImage21Edit");
    input.put("size", "2K");
    input.put("ratio", "16:9");
    input.put(
        "images",
        List.of(Map.of("path", ".huimeng-media/" + mediaId + ".png", "mimeType", "image/png")));
    var initial =
        mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "edit"), admin, input))
            .andExpect(request().asyncStarted())
            .andReturn();
    var result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
    JsonNode response = json.readTree(result.getResponse().getContentAsString());
    assertThat(response.path("code").asInt()).isEqualTo(200);
    String spec = response.path("data").get(0).path("spec").asText();
    assertThat(spec).startsWith("2K 16:9 ");
    String[] size = spec.substring(spec.indexOf(' ') + 1).split("[ ×]");
    assertThat(Integer.parseInt(size[1]) % 32).isZero();
    assertThat(Integer.parseInt(size[1])).isGreaterThan(Integer.parseInt(size[2]));
    String run =
        jdbc.queryForObject(
            "SELECT id FROM generation_runs WHERE project_id=?", String.class, project);
    assertThat(generationRuns.find(project, run).upstreamRef())
        .isEqualTo("3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f");
  }

  @Test
  void editModelRejectsMissingForeignReferenceAndUnknownSpecBeforeAnyExecutorCall()
      throws Exception {
    String project = createProject("generate-edit-guard"),
        other = createProject("generate-edit-other");
    member(project, adminId);
    approveImage();
    approveEdit();
    String foreign = seedMedia(other);
    var missing = new HashMap<>(imageInput());
    missing.put("modelId", "qwenImage21Edit");
    mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "a"), admin, missing))
        .andExpect(status().isBadRequest());
    var foreignInput = new HashMap<>(missing);
    foreignInput.put(
        "images",
        List.of(Map.of("path", ".huimeng-media/" + foreign + ".png", "mimeType", "image/png")));
    mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "b"), admin, foreignInput))
        .andExpect(status().isNotFound());
    var legacyPath = new HashMap<>(missing);
    legacyPath.put(
        "images", List.of(Map.of("path", "assets/reference.png", "mimeType", "image/png")));
    mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "e"), admin, legacyPath))
        .andExpect(status().isBadRequest());
    var badSpec = new HashMap<>(imageInput());
    badSpec.put("size", "2K");
    badSpec.put("ratio", "5:4");
    mvc.perform(write(post(imagePath(project)).header("Idempotency-Key", "c"), admin, badSpec))
        .andExpect(status().isBadRequest());
    var textWithReference = new HashMap<>(imageInput());
    textWithReference.put(
        "images",
        List.of(Map.of("path", ".huimeng-media/" + foreign + ".png", "mimeType", "image/png")));
    mvc.perform(
            write(
                post(imagePath(project)).header("Idempotency-Key", "d"), admin, textWithReference))
        .andExpect(status().isBadRequest());
    assertThat(generationRuns.held(project)).isZero();
    verify(comfy, never()).submit(anyString(), anyString(), anyString(), any(), anyString(), any());
    verify(comfy, never()).checkReady();
  }

  @Test
  void generationSixthSlotIsRejectedAndAnotherProjectHasItsOwnCapacity() throws Exception {
    String full = createProject("generate-full"), other = createProject("generate-other");
    member(full, adminId);
    member(other, adminId);
    approveImage();
    for (int i = 0; i < 5; i++)
      generationRuns.insert(
          full, UUID.randomUUID().toString(), adminId, "seed-" + i, "synthetic-hash");
    mvc.perform(
            write(post(imagePath(full)).header("Idempotency-Key", "sixth"), admin, imageInput()))
        .andExpect(status().isTooManyRequests());
    verify(comfy, never()).submit(eq(full), anyString(), anyString(), any(), anyString(), any());
    // 第 6 个请求在名额检查处拒绝，连执行器就绪探测都不发起。
    verify(comfy, never()).checkReady();
    var request =
        mvc.perform(
                write(
                    post(imagePath(other)).header("Idempotency-Key", "other"), admin, imageInput()))
            .andReturn();
    mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
    assertThat(generationRuns.held(full)).isEqualTo(5);
    assertThat(generationRuns.held(other)).isZero();
  }

  @Test
  void generationCancellationOnlyStopsOwnedRunAfterExecutorConfirmation() throws Exception {
    String project = createProject("generate-cancel");
    member(project, adminId);
    approveImage();
    when(comfy.history(anyString())).thenReturn(null);
    when(comfy.active(anyString())).thenReturn(true);
    var initial =
        mvc.perform(
                write(
                    post(imagePath(project)).header("Idempotency-Key", "cancel"),
                    admin,
                    imageInput()))
            .andReturn();
    String run =
        jdbc.queryForObject(
            "SELECT id FROM generation_runs WHERE project_id=?", String.class, project);
    mvc.perform(
            write(
                post("/api/v1/projects/" + project + "/runs/" + run + "/cancel"), admin, Map.of()))
        .andExpect(status().isAccepted());
    verify(comfy, timeout(2500).atLeastOnce()).cancel(eq(run));
    assertThat(generationRuns.held(project)).isEqualTo(1);
    when(comfy.active(anyString())).thenReturn(false);
    mvc.perform(asyncDispatch(initial)).andExpect(status().isOk());
    assertThat(generationRuns.find(project, run).state()).isEqualTo("CANCELLED");
    assertThat(generationRuns.held(project)).isZero();
    assertThat(mediaObjects).isEmpty();
    createUser("run-outsider");
    mvc.perform(
            get("/api/v1/projects/" + project + "/runs/" + run)
                .session(login("run-outsider", PASSWORD)))
        .andExpect(status().isForbidden());
  }

  @Test
  void generationUnknownRemoteStateRetainsSlotUntilConfirmedTerminal() throws Exception {
    String project = createProject("generate-lost");
    member(project, adminId);
    approveImage();
    when(comfy.history(anyString())).thenReturn(null);
    doThrow(cn.huimeng.common.io.ComfyImageClient.unavailable())
        .when(comfy)
        .submit(anyString(), anyString(), anyString(), any(), anyString(), any());
    var initial =
        mvc.perform(
                write(
                    post(imagePath(project)).header("Idempotency-Key", "lost"),
                    admin,
                    imageInput()))
            .andReturn();
    mvc.perform(asyncDispatch(initial)).andExpect(status().isOk());
    String run =
        jdbc.queryForObject(
            "SELECT id FROM generation_runs WHERE project_id=?", String.class, project);
    assertThat(generationRuns.find(project, run).state()).isEqualTo("LOST");
    assertThat(generationRuns.held(project)).isEqualTo(1);
    when(comfy.history(anyString()))
        .thenReturn(json.readTree("{\"status\":{\"status_str\":\"error\",\"completed\":false}}"));
    ((cn.huimeng.service.impl.MediaGenerationServiceImpl) generation).reconcileLost();
    assertThat(generationRuns.find(project, run).state()).isEqualTo("LOST");
    assertThat(generationRuns.held(project)).isZero();
  }

  @Test
  void generationRevokedMembershipBeforePublicationDoesNotPersistResult() throws Exception {
    String project = createProject("generate-revoke");
    member(project, adminId);
    approveImage();
    when(comfy.output(eq(project), anyString(), any()))
        .thenAnswer(
            call -> {
              jdbc.update(
                  "DELETE FROM project_members WHERE project_id=? AND user_id=?", project, adminId);
              return new byte[] {1};
            });
    var initial =
        mvc.perform(
                write(
                    post(imagePath(project)).header("Idempotency-Key", "revoke"),
                    admin,
                    imageInput()))
            .andReturn();
    mvc.perform(asyncDispatch(initial)).andExpect(status().isOk());
    awaitReleased(project);
    assertThat(mediaObjects).isEmpty();
  }

  /** 音频入口只保留语义：明确拒绝、不占名额、不触达执行器。 */
  @Test
  void audioEntryIsRejectedBeforeCapacityWithoutTouchingExecutor() throws Exception {
    String project = createProject("audio-entry");
    member(project, adminId);
    approveImage();
    actAdmin();

    assertThatThrownBy(
            () ->
                generation.generate(
                    project,
                    new cn.huimeng.dto.req.GenerateImageReqDTO(
                        "qwenImage21Local",
                        "qwenImage21Text",
                        "audio",
                        "synthetic",
                        null,
                        null,
                        null,
                        null),
                    "audio-1",
                    "audio"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> {
              var api = (ApiException) error;
              assertThat(api.status).isEqualTo(409);
              assertThat(api.code).isEqualTo("MEDIA_AUDIO_UNSUPPORTED");
            });
    assertThat(generationRuns.held(project)).isZero();
    verifyNoInteractions(comfy);
  }

  @Test
  void sixConcurrentRequestsAtomicallyAdmitOnlyFiveAndCancellationReleasesThem() throws Exception {
    String project = createProject("generate-concurrent");
    member(project, adminId);
    approveImage();
    when(comfy.history(anyString())).thenReturn(null);
    var callers = java.util.concurrent.Executors.newFixedThreadPool(6);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 6; i++) {
        final String key = "concurrent-" + i;
        futures.add(
            callers.submit(
                () -> {
                  gate.await();
                  actAdmin();
                  try {
                    return generation
                        .generate(
                            project,
                            new cn.huimeng.dto.req.GenerateImageReqDTO(
                                "qwenImage21Local",
                                "qwenImage21Text",
                                "image",
                                "synthetic",
                                "1K",
                                "1:1",
                                null,
                                null),
                            key,
                            "concurrency")
                        .run()
                        .id();
                  } catch (ApiException ex) {
                    if (ex.status == 429) return "REJECTED";
                    throw ex;
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      }
      gate.countDown();
      var values = new ArrayList<String>();
      for (var future : futures) values.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(values.stream().filter("REJECTED"::equals).count()).isEqualTo(1);
      assertThat(generationRuns.held(project)).isEqualTo(5);
    } finally {
      actAdmin();
      for (String id :
          jdbc.queryForList(
              "SELECT id FROM generation_runs WHERE project_id=?", String.class, project))
        generation.cancel(project, id);
      awaitReleased(project);
      callers.shutdownNow();
      SecurityContextHolder.clearContext();
    }
  }

  MockHttpServletRequestBuilder mediaUpload(String project, String name, byte[] content)
      throws Exception {
    return write(
            post("/api/v1/projects/" + project + "/media").param("filename", name), admin, Map.of())
        .contentType("application/octet-stream")
        .content(content);
  }

  JsonNode uploadedMedia(String project) throws Exception {
    return body(
        mvc.perform(mediaUpload(project, "素材.png", "0123456789".getBytes()))
            .andExpect(status().isCreated())
            .andReturn());
  }

  @Test
  void membershipRemovedDuringUploadPreventsPublicationAndCompensatesObject() throws Exception {
    String project = createProject("media-revoke-mid-upload");
    member(project, adminId);
    doAnswer(
            call -> {
              mediaObjects.put(
                  call.getArgument(0), ((InputStream) call.getArgument(1)).readAllBytes());
              jdbc.update(
                  "DELETE FROM project_members WHERE project_id=? AND user_id=?", project, adminId);
              return null;
            })
        .when(objectStorage)
        .put(anyString(), any(), anyLong());
    mvc.perform(mediaUpload(project, "safe.bin", new byte[] {1, 2}))
        .andExpect(status().isForbidden());
    assertThat(mediaObjects).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM project_media WHERE project_id=?", String.class, project))
        .isEqualTo("FAILED");
  }

  @Test
  void uploadLengthMismatchAndMissingLengthNeverPublishReadyMedia() throws Exception {
    String project = createProject("media-short-upload");
    member(project, adminId);
    actAdmin();
    assertThatThrownBy(
            () ->
                projectMedia.upload(
                    project, "short.bin", 10, new ByteArrayInputStream(new byte[] {1}), "short"))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(400));
    assertThatThrownBy(
            () ->
                projectMedia.upload(
                    project, "missing.bin", -1, InputStream.nullInputStream(), "missing"))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(400));
    assertThat(mediaObjects).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_media WHERE project_id=? AND state='READY'",
                Integer.class,
                project))
        .isZero();
  }

  @Test
  void mediaAuditFailureRollsBackReadyStateAndRemovesUploadedObject() throws Exception {
    String project = createProject("media-audit-rollback");
    member(project, adminId);
    actAdmin();
    // request_id NOT NULL forces the audit insert to fail after the READY update.
    assertThatThrownBy(
            () ->
                projectMedia.upload(
                    project, "audit.bin", 1, new ByteArrayInputStream(new byte[] {1}), null))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(503));
    assertThat(mediaObjects).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM project_media WHERE project_id=?", String.class, project))
        .isEqualTo("FAILED");
  }

  @Test
  void objectMediaRoundTripIsPrivateScopedHasDigestAndSingleRange() throws Exception {
    String project = createProject("media-roundtrip");
    member(project, adminId);
    JsonNode value = uploadedMedia(project);
    assertThat(value.toString()).doesNotContain("objectKey", "bucket", "endpoint", "access-key");
    assertThat(value.path("sha256").asText())
        .isEqualTo("84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882");
    String path = "/api/v1/projects/" + project + "/media/" + value.path("id").asText();
    mvc.perform(get(path + "/content").session(admin))
        .andExpect(status().isOk())
        .andExpect(content().bytes("0123456789".getBytes()))
        .andExpect(header().string("Content-Length", "10"));
    mvc.perform(get(path + "/content").header("Range", "bytes=2-4").session(admin))
        .andExpect(status().isPartialContent())
        .andExpect(content().bytes("234".getBytes()))
        .andExpect(header().string("Content-Range", "bytes 2-4/10"));
    mvc.perform(get(path + "/content").header("Range", "bytes=30-").session(admin))
        .andExpect(status().isRequestedRangeNotSatisfiable())
        .andExpect(header().string("Content-Range", "bytes */10"));
    mvc.perform(get("/api/v1/projects/" + project + "/media").session(admin))
        .andExpect(jsonPath("$.items.length()").value(1));
    mvc.perform(write(delete(path), admin, Map.of())).andExpect(status().isNoContent());
    mvc.perform(get(path + "/content").session(admin)).andExpect(status().isNotFound());
    assertThat(mediaObjects).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action IN ('MEDIA_UPLOAD','MEDIA_DELETE')",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void objectMediaRequiresMembershipEvenForAdminAndFreshlyChecksRemoval() throws Exception {
    String own = createProject("media-own"), other = createProject("media-other");
    mvc.perform(mediaUpload(own, "safe.bin", new byte[] {1})).andExpect(status().isForbidden());
    verify(objectStorage, never()).put(anyString(), any(), anyLong());
    member(own, adminId);
    member(other, adminId);
    String id = uploadedMedia(own).path("id").asText();
    mvc.perform(get("/api/v1/projects/" + other + "/media/" + id + "/content").session(admin))
        .andExpect(status().isNotFound());
    jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?", own, adminId);
    mvc.perform(get("/api/v1/projects/" + own + "/media/" + id + "/content").session(admin))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/projects/" + own + "/media").session(admin))
        .andExpect(status().isForbidden());
  }

  @Test
  void objectMediaRejectsCsrfPathsExtraFieldsOversizeAndArchivedWrites() throws Exception {
    String project = createProject("media-input");
    member(project, adminId);
    mvc.perform(
            write(post("/api/v1/projects/" + project + "/media"), admin, Map.of())
                .contentType("application/octet-stream")
                .content(new byte[] {1}))
        .andExpect(status().isBadRequest());
    mvc.perform(
            write(
                post("/api/v1/projects/" + project + "/media").param("filename", "safe.bin"),
                admin,
                Map.of()))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            post("/api/v1/projects/" + project + "/media")
                .param("filename", "safe.bin")
                .session(admin)
                .header("Origin", ORIGIN)
                .contentType("application/octet-stream")
                .content(new byte[] {1}))
        .andExpect(status().isForbidden());
    for (String name : List.of("../secret", "C:\\private", "a%2fb", "..", "a\nb"))
      mvc.perform(mediaUpload(project, name, new byte[] {1})).andExpect(status().isBadRequest());
    mvc.perform(mediaUpload(project, "safe.bin", new byte[] {1}).param("objectKey", "other"))
        .andExpect(status().isBadRequest());
    actAdmin();
    assertThatThrownBy(
            () ->
                projectMedia.upload(
                    project, "safe.bin", 536870913L, InputStream.nullInputStream(), "oversize"))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(413));
    SecurityContextHolder.clearContext();
    jdbc.update("UPDATE projects SET status='ARCHIVED' WHERE id=?", project);
    mvc.perform(mediaUpload(project, "safe.bin", new byte[] {1})).andExpect(status().isForbidden());
    verify(objectStorage, never()).put(anyString(), any(), anyLong());
  }

  @Test
  void objectMediaFailedUploadIsHiddenAndDeleteFailureCanBeRetried() throws Exception {
    String project = createProject("media-failure");
    member(project, adminId);
    doThrow(S3ObjectStorage.unavailable()).when(objectStorage).put(anyString(), any(), anyLong());
    mvc.perform(mediaUpload(project, "failed.bin", new byte[] {1}))
        .andExpect(status().isServiceUnavailable());
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM project_media WHERE project_id=?", String.class, project))
        .isEqualTo("FAILED");
    mvc.perform(get("/api/v1/projects/" + project + "/media").session(admin))
        .andExpect(jsonPath("$.items.length()").value(0));
    String id =
        jdbc.queryForObject(
            "SELECT id FROM project_media WHERE project_id=?", String.class, project);
    String path = "/api/v1/projects/" + project + "/media/" + id;
    doThrow(S3ObjectStorage.unavailable()).when(objectStorage).delete(anyString());
    mvc.perform(write(delete(path), admin, Map.of())).andExpect(status().isServiceUnavailable());
    assertThat(jdbc.queryForObject("SELECT state FROM project_media WHERE id=?", String.class, id))
        .isEqualTo("DELETING");
    doNothing().when(objectStorage).delete(anyString());
    mvc.perform(write(delete(path), admin, Map.of())).andExpect(status().isNoContent());
  }

  @Test
  void objectMediaZeroByteAndPagingAreSupportedWithoutLocalFileHandles() throws Exception {
    String project = createProject("media-empty");
    member(project, adminId);
    String id =
        body(mvc.perform(mediaUpload(project, "empty.bin", new byte[0]))
                .andExpect(status().isCreated())
                .andReturn())
            .path("id")
            .asText();
    mvc.perform(get("/api/v1/projects/" + project + "/media/" + id + "/content").session(admin))
        .andExpect(status().isOk())
        .andExpect(content().bytes(new byte[0]));
    uploadedMedia(project);
    JsonNode page =
        body(
            mvc.perform(get("/api/v1/projects/" + project + "/media?limit=1").session(admin))
                .andExpect(status().isOk())
                .andReturn());
    mvc.perform(
            get("/api/v1/projects/" + project + "/media")
                .param("cursor", page.path("nextCursor").asText())
                .session(admin))
        .andExpect(jsonPath("$.items.length()").value(1));
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
  void modelCatalogOnlyPublishesReviewedSpecsForAdminAndMember() throws Exception {
    String original =
        jdbc.queryForObject(
            "SELECT options_json FROM approved_models WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'",
            String.class);
    try {
      jdbc.update(
          "UPDATE approved_models SET enabled=TRUE,options_json=? WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'",
          "{\"resolutions\":[\"1K\",\"2K\",\"synthetic-secret-url\",{\"apiKey\":\"synthetic-options-secret\"}],\"defaultResolution\":\"1K\",\"endpoint\":\"synthetic-private-endpoint\",\"apiKey\":\"synthetic-options-secret\"}");
      for (String path : List.of("/api/v1/admin/models", "/api/v1/models")) {
        String result =
            mvc.perform(get(path).session(admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(result)
            .contains("1K", "2K")
            .doesNotContain(
                "apiKey",
                "endpoint",
                "synthetic-options-secret",
                "synthetic-secret-url",
                "synthetic-private-endpoint");
      }
      createUser("spec-member");
      String result =
          mvc.perform(get("/api/v1/models").session(login("spec-member", PASSWORD)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(result)
          .contains("1K", "2K")
          .doesNotContain("apiKey", "endpoint", "synthetic-options-secret");
    } finally {
      jdbc.update(
          "UPDATE approved_models SET options_json=? WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'",
          original);
    }
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
        .andExpect(jsonPath("$.items.length()").value(11))
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
        .isEqualTo(10);
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
            "/api/v1/admin/plugins/unknown/execute"))
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
  void externalProtocolGatesCapabilitiesRevocationAndImplementsM3Dispatch() throws Exception {
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
    // M3-06 起握手与目录已实现；工具调用在未连接页面时明确返回 isError，而不是伪装成功。
    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Mcp-Session-Id"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("huimeng")));
    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("getCanvas")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("deleteFile"))));
    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"getCanvas\",\"arguments\":{}}}"))
        .andExpect(status().isOk())
        // 协议帧是 SSE 单帧，不能用 JSON 路径断言裸对象。
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"id\":7")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"isError\":true")));
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
