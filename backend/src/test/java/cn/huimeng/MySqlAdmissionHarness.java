package cn.huimeng;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.ComfyImageClient;
import cn.huimeng.common.storage.ObjectStorage;
import cn.huimeng.dao.mapper.GenerationRunMapper;
import cn.huimeng.dto.req.*;
import cn.huimeng.security.Actor;
import cn.huimeng.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;

/** 显式选择的真实 MySQL 名额事务验证；合成账号/项目，执行器与存储为离线替身。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {"server.port=18089", "server.address=127.0.0.1"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "HM_MYSQL_ADMISSION", matches = "true")
class MySqlAdmissionHarness {
  static final Properties SETTINGS = new Properties();

  static {
    if (!"true".equals(System.getenv("HM_MYSQL_ADMISSION")))
      throw new ExceptionInInitializerError("Explicit MySQL admission opt-in required");
    try {
      try (var reader =
          Files.newBufferedReader(Path.of(".runtime/mysql-dev/application.properties"))) {
        SETTINGS.load(reader);
      }
      String url = SETTINGS.getProperty("spring.datasource.url");
      if (!url.matches("^jdbc:mysql://192\\.168\\.1\\.102:3306/huimeng_dev\\?.*")
          || !url.contains("sslMode=VERIFY_CA")) throw new IllegalStateException();
      var properties = new Properties();
      properties.put("user", SETTINGS.getProperty("spring.datasource.username"));
      properties.put("password", SETTINGS.getProperty("spring.datasource.password"));
      for (String name :
          List.of(
              "trustCertificateKeyStoreUrl",
              "trustCertificateKeyStoreType",
              "trustCertificateKeyStorePassword"))
        properties.put(
            name, SETTINGS.getProperty("spring.datasource.hikari.data-source-properties." + name));
      try (var connection = java.sql.DriverManager.getConnection(url, properties);
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT DATABASE(),(SELECT COUNT(*) FROM generation_runs WHERE capacity_held=TRUE)")) {
        rows.next();
        if (!rows.getString(1).equals("huimeng_dev") || rows.getInt(2) != 0)
          throw new IllegalStateException();
      }
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(
          "Protected independent idle MySQL development database required");
    }
  }

  @DynamicPropertySource
  static void dataSource(DynamicPropertyRegistry properties) {
    for (String key : SETTINGS.stringPropertyNames())
      if (key.startsWith("spring.datasource."))
        properties.add(key, () -> SETTINGS.getProperty(key));
  }

  @Autowired UserService users;
  @Autowired ProjectService projects;
  @Autowired ConfigurationService configuration;
  @Autowired MediaGenerationService generation;
  @Autowired GenerationRunMapper runs;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @org.springframework.boot.test.mock.mockito.MockBean ComfyImageClient comfy;
  @org.springframework.boot.test.mock.mockito.MockBean ObjectStorage storage;

  void bind(Actor actor) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
  }

  @Test
  void sixRequestsUseRealMySqlRowLockAndKeepFiveProjectSlots() throws Exception {
    Actor admin =
        users.findActor(
            jdbc.queryForObject(
                "SELECT id FROM users WHERE username='dev-admin' AND status='ACTIVE' AND platform_role='ADMIN'",
                String.class));
    boolean enabled =
        jdbc.queryForObject(
            "SELECT enabled FROM approved_models WHERE provider_id='qwenImage21Local' AND model_id='qwenImage21Text'",
            Boolean.class);
    String project = null, member = null;
    var callers = Executors.newFixedThreadPool(6);
    var gate = new CountDownLatch(1);
    try {
      bind(admin);
      String suffix = UUID.randomUUID().toString().substring(0, 8);
      member =
          users
              .create(
                  new CreateUserReqDTO(
                      "m2-mysql-check-" + suffix, "synthetic-admission-check-only", "USER"),
                  "mysql-admission")
              .id();
      project =
          projects
              .create(
                  new CreateProjectReqDTO("M2 MySQL admission validation " + suffix),
                  "mysql-admission")
              .id();
      projects.addMember(project, member, "mysql-admission");
      configuration.approve(
          new ModelApprovalReqDTO("qwenImage21Local", "qwenImage21Text", true), "mysql-admission");
      Actor owner = users.findActor(member);
      String target = project;
      when(comfy.history(anyString())).thenReturn(null);
      when(comfy.cancel(anyString())).thenReturn(true);
      when(comfy.active(anyString())).thenReturn(false);
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 6; i++) {
        String key = "mysql-concurrent-" + i;
        futures.add(
            callers.submit(
                () -> {
                  gate.await();
                  bind(owner);
                  try {
                    return generation
                        .generate(
                            target,
                            new GenerateImageReqDTO(
                                "qwenImage21Local",
                                "qwenImage21Text",
                                "image",
                                "synthetic",
                                "1K",
                                "1:1",
                                null,
                                null),
                            key,
                            "mysql-admission")
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
      for (var future : futures) values.add(future.get(20, TimeUnit.SECONDS));
      assertThat(values.stream().filter("REJECTED"::equals).count()).isEqualTo(1);
      assertThat(runs.held(project)).isEqualTo(5);
      verify(comfy, timeout(5000).times(5))
          .submit(eq(project), anyString(), anyString(), any(), anyString(), any());
      bind(owner);
      for (String id : values) if (!id.equals("REJECTED")) generation.cancel(project, id);
      for (int i = 0; i < 100 && runs.held(project) != 0; i++) Thread.sleep(100);
      assertThat(runs.held(project)).isZero();
      verify(storage, never()).put(anyString(), any(java.io.InputStream.class), anyLong());
    } finally {
      callers.shutdownNow();
      bind(admin);
      if (project != null)
        projects.update(
            project, new UpdateProjectReqDTO(null, "ARCHIVED"), "mysql-admission-cleanup");
      if (member != null)
        users.update(
            member, new UpdateUserReqDTO("DISABLED", null, null), "mysql-admission-cleanup");
      configuration.approve(
          new ModelApprovalReqDTO("qwenImage21Local", "qwenImage21Text", enabled),
          "mysql-admission-cleanup");
      SecurityContextHolder.clearContext();
    }
    Files.writeString(
        Path.of("target/mysql-admission-result.json"),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "passed",
                    true,
                    "database",
                    "huimeng_dev",
                    "admitted",
                    5,
                    "rejected",
                    1,
                    "capacityAfterCancellation",
                    0,
                    "vendorCallsReal",
                    false,
                    "configurationRestored",
                    true,
                    "syntheticUserDisabled",
                    true,
                    "syntheticProjectArchived",
                    true,
                    "isolation",
                    jdbc.queryForObject("SELECT @@transaction_isolation", String.class))));
  }
}
