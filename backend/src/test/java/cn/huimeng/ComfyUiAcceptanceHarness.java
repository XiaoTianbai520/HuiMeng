package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.*;
import cn.huimeng.security.Actor;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** 显式选择才执行的本机模型验收；不开放公共生成入口，不连接团队数据库。 */
@SpringBootTest
@ActiveProfiles("test")
public class ComfyUiAcceptanceHarness {
  static {
    try {
      Path keys = Path.of("target/test-secrets");
      Files.createDirectories(keys);
      for (String name : List.of("master.key", "internal.key")) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        Files.writeString(keys.resolve(name), Base64.getEncoder().encodeToString(key));
      }
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired ConfigurationService configuration;
  @Autowired ModelAuthorizationService authorization;
  @Autowired ObjectMapper json;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  final String base = "http://127.0.0.1:8188";

  private JsonNode get(String path) throws Exception {
    var result =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    return json.readTree(result.body());
  }

  private void actor(Actor actor) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
  }

  @Test
  void approvedMemberCanInvokeCurrentLocalQwenOnce() throws Exception {
    if (!"true".equals(System.getenv("HM_COMFY_ACCEPTANCE")))
      throw new IllegalStateException("Explicit local ComfyUI acceptance opt-in required");
    String password = "synthetic-comfy-acceptance-only";
    String provider = "qwenImage21Local", model = "qwenImage21Text";
    users.bootstrap("comfy-admin", password);
    Actor admin = users.authenticate("comfy-admin", password);
    actor(admin);
    var member =
        users.create(new CreateUserReqDTO("comfy-member", password, "USER"), "comfy-acceptance");
    var project = projects.create(new CreateProjectReqDTO("ComfyUI 合成验收"), "comfy-acceptance");
    projects.addMember(project.id(), member.id(), "comfy-acceptance");
    assertThatThrownBy(() -> authorization.requireApproved(project.id(), provider, model))
        .isInstanceOf(ApiException.class);
    actor(users.authenticate("comfy-member", password));
    assertThatThrownBy(() -> authorization.requireApproved(project.id(), provider, model))
        .isInstanceOf(ApiException.class);
    actor(admin);
    configuration.approve(new ModelApprovalReqDTO(provider, model, true), "comfy-acceptance");
    try {
      actor(users.authenticate("comfy-member", password));
      authorization.requireApproved(project.id(), provider, model);
      var queue = get("/queue");
      assertThat(queue.path("queue_running").size()).isZero();
      assertThat(queue.path("queue_pending").size()).isZero();
      var graph = json.readTree(Path.of("target/m2-comfy-api.json").toFile());
      var request = json.createObjectNode();
      request.set("prompt", graph);
      request.put("client_id", "huimeng-m2-acceptance");
      Instant start = Instant.now();
      var queued =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/prompt"))
                  .timeout(Duration.ofSeconds(20))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(queued.statusCode()).isEqualTo(200);
      String promptId = json.readTree(queued.body()).path("prompt_id").asText();
      assertThat(promptId).isNotBlank();
      JsonNode record = null;
      while (Instant.now().isBefore(start.plusSeconds(1800))) {
        record = get("/history/" + promptId).get(promptId);
        if (record != null) break;
        Thread.sleep(2000);
      }
      assertThat(record).isNotNull();
      assertThat(record.path("status").path("status_str").asText()).isEqualTo("success");
      JsonNode image = record.path("outputs").path("9").path("images").get(0);
      assertThat(image).isNotNull();
      String query =
          "filename="
              + URLEncoder.encode(image.path("filename").asText(), StandardCharsets.UTF_8)
              + "&subfolder="
              + URLEncoder.encode(image.path("subfolder").asText(), StandardCharsets.UTF_8)
              + "&type=output";
      var output =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/view?" + query))
                  .timeout(Duration.ofSeconds(30))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertThat(output.statusCode()).isEqualTo(200);
      assertThat(output.body().length).isGreaterThan(1024);
      Files.write(Path.of("target/m2-comfy-output.png"), output.body());
      var report =
          Map.of(
              "passed",
              true,
              "providerId",
              provider,
              "modelId",
              model,
              "promptId",
              promptId,
              "elapsedSeconds",
              Duration.between(start, Instant.now()).toSeconds(),
              "sha256",
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.body())),
              "scope",
              "test-only Spring member/approval gate plus real local ComfyUI; public media API pending M3");
      json.writerWithDefaultPrettyPrinter()
          .writeValue(Path.of("target/m2-comfy-result.json").toFile(), report);
    } finally {
      actor(admin);
      configuration.approve(new ModelApprovalReqDTO(provider, model, false), "comfy-acceptance");
      SecurityContextHolder.clearContext();
    }
  }
}
