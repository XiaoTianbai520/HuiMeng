package cn.huimeng;

import cn.huimeng.common.storage.ObjectStorage;
import cn.huimeng.dto.req.*;
import cn.huimeng.security.Actor;
import cn.huimeng.service.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 显式 Linux/H2 媒体浏览器夹具：真实 Spring 入口 + 合成 ComfyUI + 内存对象存储。
 *
 * <p>不连接真实模型、团队数据库或用户 MinIO；只用于验证名额、幂等、规格、取消与结果归属。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=${hm.media.port:18097}",
      "server.address=127.0.0.1",
      "huimeng.public-origin=http://localhost:5187",
      "huimeng.storage.enabled=false",
      "huimeng.comfy.enabled=${hm.comfy.enabled:false}",
      "huimeng.comfy.endpoint=${hm.comfy.endpoint:http://127.0.0.1:18110}"
    })
@ActiveProfiles("test")
public class MediaBrowserAcceptanceHarness {
  static final Path ROOT =
      Path.of("target/validation/media-browser-" + UUID.randomUUID()).toAbsolutePath();
  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired ConfigurationService configuration;

  /** 只替换对象存储，避免夹具依赖真实 MinIO；其余路径与线上一致。 */
  @TestConfiguration
  static class MemoryStorage {
    @Bean
    @Primary
    ObjectStorage memoryObjectStorage() {
      return new ObjectStorage() {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        public void put(String key, java.io.InputStream content, long length) {
          try {
            byte[] bytes = content.readAllBytes();
            if (bytes.length != length) throw unavailable();
            objects.put(key, bytes);
          } catch (java.io.IOException ex) {
            throw unavailable();
          }
        }

        public StoredObject head(String key) {
          byte[] bytes = objects.get(key);
          if (bytes == null) throw unavailable();
          return new StoredObject(bytes.length, etag(bytes));
        }

        public java.io.InputStream get(String key, String etag, String range) {
          byte[] bytes = objects.get(key);
          if (bytes == null || (etag != null && !etag.equals(etag(bytes)))) throw unavailable();
          if (range == null) return new ByteArrayInputStream(bytes);
          String[] ends = range.substring(6).split("-");
          return new ByteArrayInputStream(
              Arrays.copyOfRange(bytes, Integer.parseInt(ends[0]), Integer.parseInt(ends[1]) + 1));
        }

        public void delete(String key) {
          objects.remove(key);
        }

        private String etag(byte[] bytes) {
          try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .substring(0, 32);
          } catch (Exception ex) {
            throw unavailable();
          }
        }

        private RuntimeException unavailable() {
          return new cn.huimeng.common.exception.ApiException(503, "STORAGE_UNAVAILABLE", "对象存储不可用");
        }
      };
    }
  }

  @DynamicPropertySource
  static void fixture(DynamicPropertyRegistry properties) throws Exception {
    if (!"true".equals(System.getenv("HM_MEDIA_BROWSER"))
        || !System.getProperty("os.name").equals("Linux"))
      throw new IllegalStateException("Explicit Linux media browser opt-in required");
    Path config = Files.createDirectories(ROOT.resolve("config"));
    for (String name : List.of("master.key", "internal.key")) {
      byte[] key = new byte[32];
      new java.security.SecureRandom().nextBytes(key);
      Files.writeString(config.resolve(name), Base64.getEncoder().encodeToString(key));
    }
    properties.add("huimeng.workspace-root", () -> ROOT.resolve("workspaces").toString());
    properties.add("huimeng.master-key-file", () -> config.resolve("master.key").toString());
    properties.add("huimeng.internal-key-file", () -> config.resolve("internal.key").toString());
  }

  @Test
  void mediaFixtureUntilStopped() throws Exception {
    String password = "M3-media-fixture-only";
    users.bootstrap("ui-admin", password);
    Actor admin = users.authenticate("ui-admin", password);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    var member =
        users.create(new CreateUserReqDTO("ui-member", password, "USER"), "media-browser");
    users.create(new CreateUserReqDTO("ui-outsider", password, "USER"), "media-browser");
    var project = projects.create(new CreateProjectReqDTO("媒体生成验收"), "media-browser");
    projects.addMember(project.id(), admin.id(), "media-browser");
    projects.addMember(project.id(), member.id(), "media-browser");
    configuration.approve(
        new ModelApprovalReqDTO("qwenImage21Local", "qwenImage21Text", true), "media-browser");
    configuration.approve(
        new ModelApprovalReqDTO("qwenImage21Local", "qwenImage21Edit", true), "media-browser");
    projects.workspaceRoot(project.id(), true);
    SecurityContextHolder.clearContext();
    Path stop = Path.of("target/media-browser.stop");
    Files.deleteIfExists(stop);
    Files.writeString(
        Path.of("target/media-browser.ready"),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("projectId", project.id(), "fixtureRoot", ROOT.toString())));
    long deadline = Instant.now().getEpochSecond() + 7200;
    try {
      while (!Files.exists(stop) && Instant.now().getEpochSecond() < deadline) Thread.sleep(500);
    } finally {
      try (var paths = Files.walk(ROOT)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
}
