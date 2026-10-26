package cn.huimeng.controller;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.DocumentService;
import cn.huimeng.service.PluginService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 插件市场、已安装能力与技能/Agent 文档；全部入口要求管理员，秘密字段只写不读。 */
@RestController
public class PluginController {
  private final PluginService plugins;
  private final DocumentService documents;

  public PluginController(PluginService plugins, DocumentService documents) {
    this.plugins = plugins;
    this.documents = documents;
  }

  @GetMapping("/api/v1/admin/plugins")
  public PluginDTOs.Installed installed() {
    return new PluginDTOs.Installed(plugins.installed());
  }

  @PostMapping("/api/v1/admin/{type}/install")
  public ApiRespDTO<PluginDTOs.Named> install(
      @PathVariable String type,
      @RequestBody PluginInstallReqDTO input,
      HttpServletRequest request) {
    byte[] content = decode(input);
    var plugin =
        plugins.installFile(
            pluginType(type),
            input.fileName(),
            content,
            input.force(),
            RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Named(plugin.name()));
  }

  @PutMapping("/api/v1/admin/{type}/setEnabled")
  public ApiRespDTO<PluginDTOs.Named> setEnabled(
      @PathVariable String type,
      @RequestBody PluginNamedEnabledReqDTO input,
      HttpServletRequest request) {
    var plugin =
        plugins.enabled(
            pluginType(type), input.name(), input.enabled(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Named(plugin.name()));
  }

  @DeleteMapping("/api/v1/admin/{type}/uninstall")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void uninstallType(
      @PathVariable String type, @RequestParam String name, HttpServletRequest request) {
    plugins.uninstall(pluginType(type), name, RequestContext.requestId(request));
  }

  @PostMapping("/api/v1/admin/plugins/{type}/{name}/config")
  public ApiRespDTO<PluginRespDTO> config(
      @PathVariable String type,
      @PathVariable String name,
      @RequestBody PluginConfigReqDTO input,
      HttpServletRequest request) {
    return ApiRespDTO.ok(
        plugins.config(type, name, input.config(), RequestContext.requestId(request)));
  }

  @PostMapping("/api/v1/admin/plugins/{type}/{name}/enabled")
  public ApiRespDTO<PluginRespDTO> enabled(
      @PathVariable String type,
      @PathVariable String name,
      @RequestBody PluginEnabledReqDTO input,
      HttpServletRequest request) {
    return ApiRespDTO.ok(
        plugins.enabled(type, name, input.enabled(), RequestContext.requestId(request)));
  }

  @GetMapping("/api/v1/admin/plugins/export")
  public ResponseEntity<byte[]> export(@RequestParam String type, @RequestParam String name) {
    var export = plugins.export(type, name);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(export.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(export.content());
  }

  @DeleteMapping("/api/v1/admin/plugins/{type}/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void uninstall(
      @PathVariable String type, @PathVariable String name, HttpServletRequest request) {
    plugins.uninstall(type, name, RequestContext.requestId(request));
  }

  @PostMapping("/api/v1/admin/plugin-market/search")
  public PluginDTOs.MarketPage search(@RequestBody MarketSearchReqDTO input) {
    return plugins.search(input.page(), input.limit(), input.type(), input.searchKeyword());
  }

  @PostMapping("/api/v1/admin/plugin-market/collections/toggle")
  public PluginDTOs.Collected toggleCollection(@RequestBody MarketCollectionReqDTO input) {
    return new PluginDTOs.Collected(plugins.toggleCollection(input.pluginId()));
  }

  @PostMapping("/api/v1/admin/plugin-market/install")
  public ApiRespDTO<PluginDTOs.Named> installMarket(
      @RequestBody MarketInstallReqDTO input, HttpServletRequest request) {
    var plugin =
        plugins.installMarket(input.pluginId(), input.force(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Named(plugin.name()));
  }

  // --- 技能与 Agent 文档 -----------------------------------------------------

  @GetMapping("/api/v1/admin/skills/list")
  public ApiRespDTO<PluginDTOs.Skilled> skillList(@RequestParam String name) {
    var files = plugins.documents("skill", name);
    String main =
        files.containsKey("SKILL.md")
            ? "SKILL.md"
            : files.keySet().stream().findFirst().orElse("SKILL.md");
    return ApiRespDTO.ok(new PluginDTOs.Skilled(main, List.copyOf(files.keySet())));
  }

  @GetMapping("/api/v1/admin/skills/read")
  public ApiRespDTO<PluginDTOs.Document> skillRead(
      @RequestParam String name, @RequestParam(required = false) String path) {
    var files = plugins.documents("skill", name);
    String key = path == null || path.isBlank() ? defaultDocument(files) : path;
    String content = files.get(key);
    if (content == null) throw new ApiException(404, "DOCUMENT_MISSING", "文档不存在");
    return ApiRespDTO.ok(new PluginDTOs.Document(content, null));
  }

  @PutMapping("/api/v1/admin/skills/save")
  public ApiRespDTO<PluginDTOs.Document> skillSave(
      @RequestBody SkillSaveReqDTO input, HttpServletRequest request) {
    var files = plugins.documents("skill", input.name());
    String key =
        input.path() == null || input.path().isBlank() ? defaultDocument(files) : input.path();
    plugins.saveDocument(
        "skill", input.name(), key, input.content(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Document(input.content(), null));
  }

  @PostMapping("/api/v1/admin/skills/create")
  public ApiRespDTO<PluginDTOs.Named> skillCreate(
      @RequestBody SkillCreateReqDTO input, HttpServletRequest request) {
    var plugin =
        plugins.createDocument(
            "skill", input.name(), input.path(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Named(plugin.name()));
  }

  @PutMapping("/api/v1/admin/skills/move")
  public ApiRespDTO<PluginDTOs.Named> skillMove(
      @RequestBody SkillMoveReqDTO input, HttpServletRequest request) {
    var plugin =
        plugins.moveDocument(
            "skill", input.name(), input.path(), input.target(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Named(plugin.name()));
  }

  @PutMapping("/api/v1/admin/skills/order")
  public ApiRespDTO<PluginDTOs.Named> skillOrder(
      @RequestBody SkillOrderReqDTO input, HttpServletRequest request) {
    var plugin =
        plugins.orderDocuments(
            "skill", input.name(), input.order(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Named(plugin.name()));
  }

  @PostMapping("/api/v1/admin/skills/setEnabled")
  public ApiRespDTO<PluginDTOs.Named> skillEnabled(
      @RequestBody PluginNamedEnabledReqDTO input, HttpServletRequest request) {
    var plugin =
        plugins.enabled("skill", input.name(), input.enabled(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Named(plugin.name()));
  }

  @GetMapping("/api/v1/admin/agents/read")
  public ApiRespDTO<PluginDTOs.AgentFiles> agentRead(@RequestParam String name) {
    var files = plugins.documents("agent", name);
    var list =
        files.entrySet().stream()
            .map(entry -> new PluginDTOs.AgentFile(entry.getKey(), entry.getValue()))
            .toList();
    return ApiRespDTO.ok(new PluginDTOs.AgentFiles(list, files.getOrDefault("README.md", "")));
  }

  @GetMapping("/api/v1/admin/agents/get")
  public ApiRespDTO<PluginRespDTO> agentGet(@RequestParam String name) {
    return ApiRespDTO.ok(
        plugins.installed().stream()
            .filter(item -> item.type().equals("agent") && item.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new ApiException(404, "PLUGIN_NOT_FOUND", "未安装该 Agent")));
  }

  @PutMapping("/api/v1/admin/agents/save")
  public ApiRespDTO<PluginDTOs.Document> agentSave(
      @RequestBody AgentSaveReqDTO input, HttpServletRequest request) {
    plugins.saveDocument(
        "agent", input.name(), input.path(), input.content(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Document(input.content(), null));
  }

  @GetMapping("/api/v1/admin/agents/a2a/get")
  public ApiRespDTO<DocumentService.A2aSettings> a2aGet(HttpServletRequest request) {
    return ApiRespDTO.ok(documents.a2aSettings(baseUrl(request)));
  }

  @PutMapping("/api/v1/admin/agents/a2a/save")
  public ApiRespDTO<DocumentService.A2aSettings> a2aSave(
      @RequestBody A2aSettingsReqDTO input, HttpServletRequest request) {
    return ApiRespDTO.ok(
        documents.saveA2aSettings(input, baseUrl(request), RequestContext.requestId(request)));
  }

  @PostMapping("/api/v1/admin/agents/connect")
  public ApiRespDTO<PluginDTOs.Named> agentConnect(@RequestBody AgentConnectReqDTO input) {
    // 连接验证只做参数与会话可用性检查，真实协议调用走 /a2a 入口。
    if (input.url() == null || input.url().isBlank()) throw ApiException.invalid();
    return ApiRespDTO.ok(new PluginDTOs.Named(input.name() == null ? "a2a" : input.name()));
  }

  private static String pluginType(String path) {
    return switch (path) {
      case "nodes" -> "node";
      case "tools" -> "tool";
      case "skills" -> "skill";
      case "agents" -> "agent";
      case "ext" -> "ext";
      default -> throw ApiException.invalid();
    };
  }

  private static String defaultDocument(java.util.Map<String, String> files) {
    if (files.containsKey("SKILL.md")) return "SKILL.md";
    return files.keySet().stream().findFirst().orElse("SKILL.md");
  }

  private static byte[] decode(PluginInstallReqDTO input) {
    if (input.base64() != null && !input.base64().isBlank()) {
      try {
        return Base64.getDecoder().decode(input.base64());
      } catch (IllegalArgumentException ex) {
        throw new ApiException(400, "PLUGIN_FILE_ENCODING", "文件内容不是合法的 Base64");
      }
    }
    return input.source() == null ? new byte[0] : input.source().getBytes(StandardCharsets.UTF_8);
  }

  private String baseUrl(HttpServletRequest request) {
    return request.getRequestURL().toString().replaceAll("/api/v1/.*$", "");
  }
}
