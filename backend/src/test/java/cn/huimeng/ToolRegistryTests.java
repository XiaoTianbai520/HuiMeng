package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.service.impl.AgentToolCatalog;
import cn.huimeng.service.impl.agent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 工具注册表：顺序、重名与未知工具的行为固定下来，新增提供方不会改变主循环契约。 */
class ToolRegistryTests {
  private final ObjectMapper json = new ObjectMapper();

  private static Map<String, Object> definition(String name) {
    return Map.of(
        "type",
        "function",
        "function",
        Map.of("name", name, "description", name + " 描述", "parameters", Map.of("type", "object")));
  }

  private record Fake(String id, List<String> tools, JsonNode result) implements ToolProvider {
    @Override
    public List<Map<String, Object>> definitions(ToolSession session) {
      return tools.stream().map(ToolRegistryTests::definition).toList();
    }

    @Override
    public boolean owns(String name) {
      return tools.contains(name);
    }

    @Override
    public JsonNode invoke(String name, String callId, JsonNode args, ToolSession session) {
      return result;
    }
  }

  private static ToolSession session(boolean canvas) {
    return new ToolSession() {
      @Override
      public String projectId() {
        return "p1";
      }

      @Override
      public String sessionFile() {
        return "s.jsonl";
      }

      @Override
      public boolean canvasEnabled() {
        return canvas;
      }

      @Override
      public JsonNode pageRequest(
          String callId, com.fasterxml.jackson.databind.node.ObjectNode payload, long seconds) {
        return new ObjectMapper().createObjectNode();
      }
    };
  }

  @Test
  void keepsFirstProviderWhenNamesCollide() {
    var first =
        new Fake(
            "first", List.of("same", "onlyFirst"), json.createObjectNode().put("from", "first"));
    var second =
        new Fake(
            "second", List.of("same", "onlySecond"), json.createObjectNode().put("from", "second"));
    var registry = new ToolRegistry(List.of(first, second));

    List<String> names =
        registry.definitions(session(true)).stream()
            .map(item -> String.valueOf(((Map<?, ?>) item.get("function")).get("name")))
            .toList();
    assertThat(names).containsExactly("same", "onlyFirst", "onlySecond");
    assertThat(registry.providerIds()).containsExactly("first", "second");
  }

  @Test
  void routesToTheProviderThatOwnsTheName() throws Exception {
    var a = new Fake("a", List.of("alpha"), json.createObjectNode().put("from", "a"));
    var b = new Fake("b", List.of("beta"), json.createObjectNode().put("from", "b"));
    var registry = new ToolRegistry(List.of(a, b));

    assertThat(
            registry
                .invoke("beta", "c1", json.createObjectNode(), session(true))
                .path("from")
                .asText())
        .isEqualTo("b");
    assertThatThrownBy(
            () -> registry.invoke("missing", "c1", json.createObjectNode(), session(true)))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).code).isEqualTo("TOOL_UNKNOWN"));
  }

  /** 画布未激活时，画布提供方不下发定义，但提问与生图仍然可用。 */
  @Test
  void canvasToolsFollowTheSessionFlag() {
    var catalog = new AgentToolCatalog(json);
    catalog.load();
    var canvasOnly = new ToolRegistry(List.of(new CanvasToolProvider(catalog, json)));
    assertThat(canvasOnly.definitions(session(false))).isEmpty();
    assertThat(canvasOnly.definitions(session(true))).isNotEmpty();

    var ask = new ToolRegistry(List.of(new AskToolProvider(catalog, json)));
    assertThat(ask.definitions(session(false))).hasSize(1);
  }
}
