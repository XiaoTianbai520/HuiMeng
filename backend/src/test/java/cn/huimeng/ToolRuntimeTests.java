package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.PluginDO;
import cn.huimeng.dao.mapper.PluginMapper;
import cn.huimeng.service.impl.ToolRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 已安装工具包的服务端执行：自包含包可跑，外部模块与死循环被明确拒绝。 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@ActiveProfiles("test")
class ToolRuntimeTests {
  static final String ECHO =
      "/*! toonflowTool:{\"name\":\"echoPack\",\"version\":\"1.0.0\",\"displayName\":\"回显\","
          + "\"description\":\"验收用\",\"author\":\"fixture\",\"github\":\"\",\"components\":[],"
          + "\"configRules\":[]} */\n"
          + "export default {\n"
          + "  validateConfig(config) { return config; },\n"
          + "  createTools({ config }) {\n"
          + "    return [{ name: \"echoTool\", description: \"回显文本\",\n"
          + "      parameters: { type: \"object\", properties: { text: { type: \"string\" } } },\n"
          + "      async execute(id, params) {\n"
          + "        return { content: [{ type: \"text\", text: \"echo:\" + params.text }] };\n"
          + "      } }];\n"
          + "  }\n"
          + "};\n";
  static final String IMPORTING =
      "/*! toonflowTool:{\"name\":\"importPack\",\"version\":\"1.0.0\",\"displayName\":\"引用包\","
          + "\"description\":\"验收用\",\"author\":\"fixture\",\"github\":\"\",\"components\":[],"
          + "\"configRules\":[]} */\n"
          + "import { z } from \"toonflow:tool-zod\";\n"
          + "export default { validateConfig: c => c, createTools: () => [] };\n";
  static final String RUNAWAY =
      "/*! toonflowTool:{\"name\":\"runawayPack\",\"version\":\"1.0.0\",\"displayName\":\"死循环\","
          + "\"description\":\"验收用\",\"author\":\"fixture\",\"github\":\"\",\"components\":[],"
          + "\"configRules\":[]} */\n"
          + "export default { validateConfig: c => c, createTools: () => [{ name: \"runawayTool\",\n"
          + "  parameters: { type: \"object\" }, execute() { while (true) {} } }] };\n";

  /** 真实市场制品用紧凑写法（`import{z}from"toonflow:tool-zod"`），同样必须在加载期被挡住。 */
  static final String IMPORTING_MINIFIED =
      "/*! toonflowTool:{\"name\":\"minifiedPack\",\"version\":\"1.0.0\",\"displayName\":\"紧凑包\","
          + "\"description\":\"验收用\",\"author\":\"fixture\",\"github\":\"\",\"components\":[],"
          + "\"configRules\":[]} */\n"
          + "import{z as e}from\"toonflow:tool-zod\";\n"
          + "export default{validateConfig:c=>c,createTools:()=>[]};\n";

  @Autowired ToolRuntime runtime;
  @Autowired PluginMapper plugins;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @BeforeEach
  void setup() {
    jdbc.update("DELETE FROM plugin_documents");
    jdbc.update("DELETE FROM plugins");
  }

  @Test
  void exposesAndExecutesSelfContainedTool() throws Exception {
    install("echoPack", ECHO);

    List<Map<String, Object>> definitions = runtime.definitions();
    assertThat(definitions).hasSize(1);
    @SuppressWarnings("unchecked")
    var function = (Map<String, Object>) definitions.get(0).get("function");
    assertThat(function.get("name")).isEqualTo("echoTool");
    assertThat(function.get("description")).isEqualTo("回显文本");
    assertThat(runtime.owns("echoTool")).isTrue();

    JsonNode result = runtime.execute("echoTool", "call-1", json.readTree("{\"text\":\"hello\"}"));
    assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("echo:hello");
  }

  /** 带模块引用的包不进入工具清单，调用方拿不到名字；执行器不做法外模块解析。 */
  @Test
  void skipsToolPackageThatImportsExternalModules() {
    install("importPack", IMPORTING);
    assertThat(runtime.definitions()).isEmpty();
    assertThat(runtime.owns("anything")).isFalse();
  }

  /** 「已安装」不等于「可用」：加载不了的工具必须能说出原因，不能静默当作正常。 */
  @Test
  void reportsWhyInstalledToolCannotLoad() {
    install("echoPack", ECHO);
    install("importPack", IMPORTING);
    install("minifiedPack", IMPORTING_MINIFIED);

    assertThat(runtime.loadError(plugins.find("tool", "echoPack"))).isNull();
    assertThat(runtime.loadError(plugins.find("tool", "importPack"))).contains("外部模块");
    assertThat(runtime.loadError(plugins.find("tool", "minifiedPack"))).contains("外部模块");
  }

  @Test
  void stopsRunawayToolWithTimeout() {
    install("runawayPack", RUNAWAY);
    assertThat(runtime.owns("runawayTool")).isTrue();
    assertThatThrownBy(() -> runtime.execute("runawayTool", "call-1", json.createObjectNode()))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> {
              var api = (ApiException) error;
              assertThat(api.status).isIn(409, 504);
              assertThat(api.code).isIn("TOOL_TIMEOUT", "TOOL_EXECUTION_FAILED");
            });
  }

  private void install(String name, String source) {
    byte[] content = source.getBytes(StandardCharsets.UTF_8);
    Instant now = Instant.now();
    plugins.insert(
        new PluginDO(
            "tool:" + name,
            "tool",
            name,
            name,
            "1.0.0",
            sha256(content),
            "file",
            null,
            true,
            null,
            "{}",
            content,
            "tester",
            now,
            now));
  }

  private static String sha256(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder();
      for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
