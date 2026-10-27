package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.dao.entity.PluginDO;
import cn.huimeng.dao.mapper.PluginMapper;
import cn.huimeng.security.Actor;
import cn.huimeng.service.PluginService;
import cn.huimeng.service.UserService;
import cn.huimeng.service.impl.ToolRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 真实市场工具的执行夹具（显式运行，不进普通 verify）。
 *
 * <p>用 `HM_MARKET_TOOL_DIR` 指向已下载的 `.tool.js` 目录，逐个登记为文件安装包，输出加载结果与工具定义；若设置
 * `HM_MARKET_TOOL_EXEC=toolName` 与 `HM_MARKET_TOOL_ARGS`（JSON），再真实执行一次并记录返回值。制品与凭据只在本地临时目录。
 *
 * <pre>
 * HM_MARKET_TOOL_DIR=.m2-work HM_MARKET_TOOL_EXEC=composeCinematicPrompt \
 *   HM_MARKET_TOOL_ARGS='{"idea":"..."}' mvnw -Dtest=MarketToolHarness test
 * </pre>
 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@ActiveProfiles("test")
class MarketToolHarness {
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

  @Autowired UserService users;
  @Autowired PluginService plugins;
  @Autowired PluginMapper mapper;
  @Autowired ToolRuntime runtime;
  @Autowired ObjectMapper json;

  @Test
  void inspectAndRunMarketTools() throws Exception {
    String dir = System.getenv("HM_MARKET_TOOL_DIR");
    assertThat(dir).as("HM_MARKET_TOOL_DIR").isNotBlank();

    String password = "market-harness-only";
    users.bootstrap("market-admin", password);
    Actor admin = users.authenticate("market-admin", password);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));

    var report = new LinkedHashMap<String, Object>();
    var perPack = new LinkedHashMap<String, Object>();
    try (var files = Files.list(Path.of(dir))) {
      for (Path file : files.sorted().toList()) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".tool.js")) continue;
        byte[] bytes = Files.readAllBytes(file);
        plugins.installFile("tool", fileName, bytes, true, "market-harness");
        String name = fileName.substring(0, fileName.length() - ".tool.js".length());
        PluginDO plugin = mapper.find("tool", name);
        var item = new LinkedHashMap<String, Object>();
        item.put("bytes", bytes.length);
        item.put("loadError", runtime.loadError(plugin));
        perPack.put(name, item);
      }
    }
    report.put("packs", perPack);
    report.put("allTools", runtime.definitions());

    String exec = System.getenv("HM_MARKET_TOOL_EXEC");
    if (exec != null && !exec.isBlank()) {
      JsonNode args = json.readTree(System.getenv().getOrDefault("HM_MARKET_TOOL_ARGS", "{}"));
      var execution = new LinkedHashMap<String, Object>();
      execution.put("tool", exec);
      execution.put("arguments", args);
      try {
        execution.put("result", runtime.execute(exec, "market-harness", args));
      } catch (RuntimeException ex) {
        execution.put("error", ex.getMessage());
      }
      report.put("execution", execution);
    }
    SecurityContextHolder.clearContext();
    Files.createDirectories(Path.of("target"));
    Files.writeString(
        Path.of("target/market-tool-report.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    System.out.println(
        "market_tool_report=" + Path.of("target/market-tool-report.json").toAbsolutePath());
  }
}
