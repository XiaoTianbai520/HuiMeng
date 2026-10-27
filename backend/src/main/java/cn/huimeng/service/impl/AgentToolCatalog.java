package cn.huimeng.service.impl;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Agent 可用工具目录：画布操作来自固定资源（与 {@code @toonflow/tool-canvas} 同名同参数），另含提问与内部生图。
 *
 * <p>浏览器不提供工具定义，避免模型被页面伪造的函数签名驱动；画布操作始终由页面校验并执行。
 */
@Component
public class AgentToolCatalog {
  public static final String ASK = "askQuestion";
  public static final String GENERATE_IMAGE = "generateImage";
  private final ObjectMapper json;
  private final List<Map<String, Object>> canvas = new ArrayList<>();
  private final List<Map<String, Object>> extra = new ArrayList<>();
  private final Map<String, Map<String, Object>> byName = new LinkedHashMap<>();

  public AgentToolCatalog(ObjectMapper json) {
    this.json = json;
  }

  @PostConstruct
  public void load() {
    try (var input = getClass().getResourceAsStream("/agent/canvas-tools.json")) {
      if (input == null) throw new IllegalStateException("Agent 工具定义缺失");
      JsonNode root = json.readTree(input);
      for (var group : List.of("canvas", "extra"))
        for (var node : root.path(group)) {
          var tool =
              json.convertValue(
                  node,
                  new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
          ("canvas".equals(group) ? canvas : extra).add(tool);
          byName.put(String.valueOf(tool.get("name")), tool);
        }
    } catch (IOException ex) {
      throw new IllegalStateException("Agent 工具定义无法读取", ex);
    }
  }

  public boolean isCanvasTool(String name) {
    return canvas.stream().anyMatch(tool -> tool.get("name").equals(name));
  }

  public Set<String> names(boolean withCanvas) {
    var result = new LinkedHashSet<String>();
    if (withCanvas) canvas.forEach(tool -> result.add(String.valueOf(tool.get("name"))));
    extra.forEach(tool -> result.add(String.valueOf(tool.get("name"))));
    return result;
  }

  /** 原始定义（name/label/description/parameters），供 MCP 目录复用同一份画布签名。 */
  public List<Map<String, Object>> raw(boolean withCanvas) {
    var result = new ArrayList<Map<String, Object>>();
    if (withCanvas) result.addAll(canvas);
    result.addAll(extra);
    return result;
  }

  public String label(String name) {
    Map<String, Object> tool = byName.get(name);
    return tool == null ? name : String.valueOf(tool.get("label"));
  }

  /** OpenAI function 工具定义；画布未激活时不下发画布工具，模型不会尝试不可用的操作。 */
  public List<Map<String, Object>> definitions(boolean withCanvas) {
    var source = new ArrayList<Map<String, Object>>();
    if (withCanvas) source.addAll(canvas);
    source.addAll(extra);
    return openAi(source);
  }

  /** 只含画布操作的定义；工具注册表按提供方分别取用。 */
  public List<Map<String, Object>> canvasDefinitions() {
    return openAi(canvas);
  }

  /** 按名字取单条定义（提问、内部生图各自独立提供）。 */
  public List<Map<String, Object>> definition(String name) {
    Map<String, Object> tool = byName.get(name);
    return tool == null ? List.of() : openAi(List.of(tool));
  }

  private List<Map<String, Object>> openAi(List<Map<String, Object>> source) {
    var result = new ArrayList<Map<String, Object>>();
    for (var tool : source) {
      result.add(
          Map.of(
              "type",
              "function",
              "function",
              Map.of(
                  "name", tool.get("name"),
                  "description", tool.get("description"),
                  "parameters", tool.get("parameters"))));
    }
    return result;
  }
}
