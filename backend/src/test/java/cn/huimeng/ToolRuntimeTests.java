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

/**
 * 已安装工具包的服务端执行：自包含包与内置 zod 模块可跑，文件/线程等外部模块与死循环被明确拒绝。
 *
 * <p>真实市场工具都写成紧凑的 ESM（`import{z as e}from"toonflow:tool-zod"`），这里同时固定紧凑写法的回归用例。
 */
@SpringBootTest(properties = "huimeng.market.endpoint=http://127.0.0.1:1")
@ActiveProfiles("test")
class ToolRuntimeTests {
  private static String header(String name) {
    return "/*! toonflowTool:{\"name\":\""
        + name
        + "\",\"version\":\"1.0.0\",\"displayName\":\"验收用\",\"description\":\"验收用\","
        + "\"author\":\"fixture\",\"github\":\"\",\"components\":[],\"configRules\":[]} */\n";
  }

  static final String ECHO =
      header("echoPack")
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

  /** 具名导入：最常见的市场工具写法（未压缩）。 */
  static final String ZOD_NAMED =
      header("zodNamedPack")
          + "import { z } from \"toonflow:tool-zod\";\n"
          + "export default { validateConfig: c => c, createTools: () => [{ name: \"checkName\",\n"
          + "  description: \"校验名称\", parameters: { type: \"object\" },\n"
          + "  execute: (id, params) => {\n"
          + "    const value = z.object({ name: z.string().min(2) }).parse(params);\n"
          + "    return { content: [{ type: \"text\", text: \"ok:\" + value.name }] };\n"
          + "  } }] };\n";

  /** 真实市场制品的紧凑写法。 */
  static final String ZOD_MINIFIED =
      header("minifiedPack")
          + "import{z as e}from\"toonflow:tool-zod\";\n"
          + "export default{validateConfig:c=>c,createTools:()=>[{name:\"checkAge\",description:\"校验年龄\","
          + "parameters:{type:\"object\"},execute:(t,r)=>({content:[{type:\"text\","
          + "text:\"age:\"+e.object({age:e.number().int().min(0)}).parse(r).age}]})}]};\n";

  /** default 与命名空间写法。 */
  static final String ZOD_DEFAULT =
      header("zodDefaultPack")
          + "import zod from \"toonflow:tool-zod\";\n"
          + "export default { validateConfig: c => c, createTools: () => [{ name: \"checkCount\",\n"
          + "  description: \"校验数量\", parameters: { type: \"object\" },\n"
          + "  execute: (id, params) => ({ content: [{ type: \"text\",\n"
          + "    text: \"count:\" + zod.number().int().parse(params.count) }] }) }] };\n";

  static final String ZOD_NAMESPACE =
      header("zodNsPack")
          + "import * as z from \"toonflow:tool-zod\";\n"
          + "export default { validateConfig: c => c, createTools: () => [{ name: \"checkFlag\",\n"
          + "  description: \"校验布尔\", parameters: { type: \"object\" },\n"
          + "  execute: (id, params) => ({ content: [{ type: \"text\",\n"
          + "    text: \"flag:\" + z.boolean().parse(params.flag) }] }) }] };\n";

  static final String FS_PACK =
      header("fsPack")
          + "import fs from \"node:fs\";\n"
          + "export default { validateConfig: c => c, createTools: () => [] };\n";

  static final String THREADS_PACK =
      header("threadsPack")
          + "import { Worker } from \"worker_threads\";\n"
          + "export default { validateConfig: c => c, createTools: () => [] };\n";

  /** 真实市场里的删除工具依赖页面注入的宿主接口；服务端没有该桥，必须显式说明而不是一调用就报未定义。 */
  static final String HOST_BRIDGE_PACK =
      header("hostBridgePack")
          + "function listFiles() { return globalThis.toonflowToolHost.axios.post('/list', {}); }\n"
          + "export default { validateConfig: c => c, createTools: () => [{ name: \"bridgeTool\",\n"
          + "  parameters: { type: \"object\" }, execute: () => listFiles() }] };\n";

  static final String RUNAWAY =
      header("runawayPack")
          + "export default { validateConfig: c => c, createTools: () => [{ name: \"runawayTool\",\n"
          + "  parameters: { type: \"object\" }, execute() { while (true) {} } }] };\n";

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

  /** 内置 zod 模块：具名导入可用，且校验失败按执行错误返回。 */
  @Test
  void runsToolThatImportsBundledZod() throws Exception {
    install("zodNamedPack", ZOD_NAMED);

    assertThat(runtime.owns("checkName")).isTrue();
    JsonNode ok = runtime.execute("checkName", "call-1", json.readTree("{\"name\":\"绘梦\"}"));
    assertThat(ok.path("content").get(0).path("text").asText()).isEqualTo("ok:绘梦");

    assertThatThrownBy(
            () -> runtime.execute("checkName", "call-2", json.readTree("{\"name\":\"x\"}")))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> {
              var api = (ApiException) error;
              assertThat(api.status).isEqualTo(409);
              assertThat(api.code).isEqualTo("TOOL_EXECUTION_FAILED");
            });
  }

  /** 紧凑写法是真实制品形态，必须与未压缩写法同样可用。 */
  @Test
  void runsMinifiedToolThatImportsBundledZod() throws Exception {
    install("minifiedPack", ZOD_MINIFIED);
    JsonNode result = runtime.execute("checkAge", "call-1", json.readTree("{\"age\":18}"));
    assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("age:18");
  }

  @Test
  void supportsDefaultAndNamespaceZodImports() throws Exception {
    install("zodDefaultPack", ZOD_DEFAULT);
    install("zodNsPack", ZOD_NAMESPACE);

    assertThat(
            runtime
                .execute("checkCount", "call-1", json.readTree("{\"count\":3}"))
                .path("content")
                .get(0)
                .path("text")
                .asText())
        .isEqualTo("count:3");
    assertThat(
            runtime
                .execute("checkFlag", "call-1", json.readTree("{\"flag\":true}"))
                .path("content")
                .get(0)
                .path("text")
                .asText())
        .isEqualTo("flag:true");
  }

  /** 文件与线程模块不随 zod 一起放开：它们需要独立的工作区沙箱与资源限制评估。 */
  @Test
  void refusesFileSystemAndWorkerThreadsModules() {
    install("fsPack", FS_PACK);
    install("threadsPack", THREADS_PACK);

    assertThat(runtime.owns("anything")).isFalse();
    assertThat(runtime.loadError(plugins.find("tool", "fsPack"))).contains("外部模块");
    assertThat(runtime.loadError(plugins.find("tool", "threadsPack"))).contains("外部模块");
  }

  /** 依赖宿主文件桥的工具在登记期就说清原因；否则只能等调用时抛「未定义」。 */
  @Test
  void explainsToolsThatNeedTheHostBridge() {
    install("hostBridgePack", HOST_BRIDGE_PACK);

    assertThat(runtime.owns("bridgeTool")).isFalse();
    assertThat(runtime.loadError(plugins.find("tool", "hostBridgePack"))).contains("宿主文件接口");
  }

  /** 「已安装」不等于「可用」：加载不了的工具必须能说出原因，不能静默当作正常。 */
  @Test
  void reportsWhyInstalledToolCannotLoad() {
    install("echoPack", ECHO);
    install("fsPack", FS_PACK);

    assertThat(runtime.loadError(plugins.find("tool", "echoPack"))).isNull();
    assertThat(runtime.loadError(plugins.find("tool", "fsPack"))).contains("toonflow:tool-zod");
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
