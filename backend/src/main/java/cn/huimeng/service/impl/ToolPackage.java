package cn.huimeng.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具包的头部元数据与内嵌界面。
 *
 * <p>上游把两类信息放在前两行注释里：第一行 {@code /*! toonflowTool:{...}*}{@code /} 是元数据，第二行 {@code /*!
 * toonflowToolClient:{"code","css"}*}{@code /} 是浏览器界面。界面不是 ESM 导出，因此不能把 {@code .tool.js}
 * 原样当脚本下发。解析规则与上游 {@code parseTool} 保持一致。
 */
record ToolPackage(JsonNode metadata, List<String> components, Client client) {
  static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]*");
  static final Pattern COMPONENT = Pattern.compile("[a-z][a-zA-Z0-9_]*");
  private static final String META_HEADER = "/*! toonflowTool:";
  private static final String CLIENT_HEADER = "/*! toonflowToolClient:";
  private static final int MAX_COMPONENTS = 100;

  /** 浏览器界面：脚本正文与随包样式，由 {@code tools/client} 拼接后下发。 */
  record Client(String code, String css) {}

  /**
   * 解析工具包。
   *
   * @param content 安装包原始字节
   * @param expected 安装名，必须与包头 {@code name} 一致
   * @param json 复用应用的对象映射器
   * @throws IllegalArgumentException 头部缺失、名称不符、组件名非法或界面格式错误
   */
  static ToolPackage parse(byte[] content, String expected, ObjectMapper json) {
    if (content == null || content.length == 0) throw new IllegalArgumentException("工具包内容为空");
    String text = new String(content, StandardCharsets.UTF_8);
    int firstBreak = text.indexOf('\n');
    String first = stripCarriageReturn(firstBreak < 0 ? text : text.substring(0, firstBreak));
    if (!first.startsWith(META_HEADER) || !first.endsWith(" */"))
      throw new IllegalArgumentException("工具元数据无效：缺少 toonflowTool 包头");
    JsonNode metadata;
    try {
      metadata = json.readTree(first.substring(META_HEADER.length(), first.length() - 3).trim());
    } catch (Exception ex) {
      throw new IllegalArgumentException("工具元数据不是合法 JSON", ex);
    }
    if (metadata == null || !metadata.isObject()) throw new IllegalArgumentException("工具元数据不是对象");
    String name = metadata.path("name").asText("");
    if (!NAME.matcher(name).matches() || !name.equals(expected))
      throw new IllegalArgumentException("工具元数据名称与安装名不一致");
    List<String> components = components(metadata.path("components"));
    Client client = firstBreak < 0 ? null : client(text.substring(firstBreak + 1), json);
    if (!components.isEmpty() && client == null)
      throw new IllegalArgumentException("工具声明了组件但缺少客户端界面");
    return new ToolPackage(metadata, components, client);
  }

  /** 组件即界面里的工具名；空数组表示纯 Agent 工具，没有界面。 */
  private static List<String> components(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) return List.of();
    if (!value.isArray() || value.size() > MAX_COMPONENTS)
      throw new IllegalArgumentException("工具组件列表无效");
    Set<String> unique = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();
    for (JsonNode item : value) {
      String name = item.asText("");
      if (!COMPONENT.matcher(name).matches())
        throw new IllegalArgumentException("工具组件名必须是小驼峰：" + name);
      if (!unique.add(name)) throw new IllegalArgumentException("工具组件名不能重复：" + name);
      result.add(name);
    }
    return List.copyOf(result);
  }

  /** 第二行必须整行匹配，且与正文分属两行；不符合就当没有界面。 */
  private static Client client(String remainder, ObjectMapper json) {
    String line = stripCarriageReturn(remainder.lines().findFirst().orElse(""));
    if (!line.startsWith(CLIENT_HEADER) || !line.endsWith(" */")) return null;
    try {
      JsonNode value =
          json.readTree(line.substring(CLIENT_HEADER.length(), line.length() - 3).trim());
      if (!value.isObject()) return null;
      String code = value.path("code").asText("");
      String css = value.path("css").asText("");
      if (code.isBlank()) return null;
      return new Client(code, css);
    } catch (Exception ex) {
      return null;
    }
  }

  private static String stripCarriageReturn(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }
}
