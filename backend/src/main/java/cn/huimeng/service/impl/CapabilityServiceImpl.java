package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.PluginDO;
import cn.huimeng.dao.mapper.PluginMapper;
import cn.huimeng.dto.resp.CapabilityDTOs;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.CapabilityService;
import cn.huimeng.service.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 已安装能力的运行期分发：页面通过 {@code nodes/get} 获取节点脚本地址并在浏览器注册，工具界面按摘要版本取回。
 *
 * <p>只发布已登记内容，不接受客户端给出路径或摘要；秘密配置字段不下发。
 */
@Service
public class CapabilityServiceImpl implements CapabilityService {
  private static final Pattern SCRIPT_NAME = Pattern.compile("[a-z][a-zA-Z0-9]*");
  private static final Pattern SECRET_KEY =
      Pattern.compile("(?i).*(key|secret|token|password|credential).*");
  private final PluginMapper plugins;
  private final ProjectService projects;
  private final ObjectMapper json;

  public CapabilityServiceImpl(PluginMapper plugins, ProjectService projects, ObjectMapper json) {
    this.plugins = plugins;
    this.projects = projects;
    this.json = json;
  }

  @Override
  public List<CapabilityDTOs.NodeItem> nodes() {
    RequestContext.actor();
    var result = new ArrayList<CapabilityDTOs.NodeItem>();
    for (PluginDO plugin : plugins.list()) {
      if (!"node".equals(plugin.type()) || !SCRIPT_NAME.matcher(plugin.name()).matches()) continue;
      result.add(
          new CapabilityDTOs.NodeItem(
              plugin.name(),
              plugin.displayName(),
              scriptUrl("nodes/files", plugin.name(), null),
              plugin.enabled(),
              config(plugin)));
    }
    result.sort(Comparator.comparing(CapabilityDTOs.NodeItem::name));
    return result;
  }

  @Override
  public CapabilityDTOs.Script nodeScript(String name) {
    RequestContext.actor();
    return script("node", name, null);
  }

  @Override
  public CapabilityDTOs.ToolCatalog tools() {
    RequestContext.actor();
    var items = new ArrayList<CapabilityDTOs.ToolItem>();
    items.add(new CapabilityDTOs.ToolItem("canvasOperations", "画布操作", "由页面执行的画布读取与控制工具", true));
    for (PluginDO plugin : plugins.list())
      if ("tool".equals(plugin.type()))
        items.add(
            new CapabilityDTOs.ToolItem(
                plugin.name(), plugin.displayName(), description(plugin), plugin.enabled()));
    return new CapabilityDTOs.ToolCatalog(List.copyOf(items), false);
  }

  @Override
  public List<CapabilityDTOs.RendererItem> renderers() {
    RequestContext.actor();
    var result = new ArrayList<CapabilityDTOs.RendererItem>();
    for (PluginDO plugin : plugins.list()) {
      if (!"tool".equals(plugin.type())
          || !plugin.enabled()
          || !SCRIPT_NAME.matcher(plugin.name()).matches()) continue;
      result.add(
          new CapabilityDTOs.RendererItem(
              plugin.name(),
              List.of(plugin.name()),
              scriptUrl("tools/client", plugin.name(), plugin.sha256())));
    }
    result.sort(Comparator.comparing(CapabilityDTOs.RendererItem::name));
    return result;
  }

  @Override
  public CapabilityDTOs.Script toolClient(String name, String version) {
    RequestContext.actor();
    return script("tool", name, version);
  }

  @Override
  public List<CapabilityDTOs.SkillItem> skills() {
    RequestContext.actor();
    var result = new ArrayList<CapabilityDTOs.SkillItem>();
    for (PluginDO plugin : plugins.list())
      if ("skill".equals(plugin.type()) && plugin.enabled())
        result.add(
            new CapabilityDTOs.SkillItem(plugin.name(), plugin.displayName(), plugin.version()));
    result.sort(Comparator.comparing(CapabilityDTOs.SkillItem::name));
    return result;
  }

  @Override
  public List<CapabilityDTOs.SkillItem> agentSkills(String projectId) {
    projects.requireMember(projectId, false);
    return skills();
  }

  /** 脚本只在类型、启用状态与摘要都匹配时下发；任何不符都按“能力未安装或已停用”处理。 */
  private CapabilityDTOs.Script script(String type, String name, String version) {
    if (name == null || !SCRIPT_NAME.matcher(name).matches()) throw notFound();
    PluginDO plugin = plugins.find(type, name);
    if (plugin == null || !plugin.enabled()) throw notFound();
    if (version != null && !version.equals(plugin.sha256())) throw notFound();
    // 列表查询不带内容列，正文单独读取，避免每次目录请求搬运安装包。
    byte[] content = plugins.content(plugin.id());
    if (content == null || content.length == 0) throw notFound();
    return new CapabilityDTOs.Script(content, plugin.sha256());
  }

  private String scriptUrl(String path, String name, String version) {
    String base = "/api/v1/capabilities/" + path + "?name=" + name;
    return version == null ? base : base + "&version=" + version;
  }

  /** 配置里的秘密字段只留在服务端加密存储，目录接口不下发。 */
  private Map<String, Object> config(PluginDO plugin) {
    if (plugin.configJson() == null || plugin.configJson().isBlank()) return Map.of();
    try {
      JsonNode value = json.readTree(plugin.configJson());
      if (!value.isObject()) return Map.of();
      return json.convertValue(stripSecrets(value), Map.class);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private JsonNode stripSecrets(JsonNode value) {
    if (value.isObject()) {
      var result = json.createObjectNode();
      value
          .fields()
          .forEachRemaining(
              field -> {
                if (!SECRET_KEY.matcher(field.getKey()).matches())
                  result.set(field.getKey(), stripSecrets(field.getValue()));
              });
      return result;
    }
    if (value.isArray()) {
      var result = json.createArrayNode();
      value.forEach(item -> result.add(stripSecrets(item)));
      return result;
    }
    return value;
  }

  private String description(PluginDO plugin) {
    try {
      JsonNode manifest = json.readTree(plugin.manifestJson());
      String text = manifest.path("description").asText("");
      return text.length() > 200 ? text.substring(0, 200) : text;
    } catch (Exception ex) {
      return "";
    }
  }

  private static ApiException notFound() {
    return new ApiException(404, "CAPABILITY_NOT_FOUND", "能力未安装或已停用");
  }
}
