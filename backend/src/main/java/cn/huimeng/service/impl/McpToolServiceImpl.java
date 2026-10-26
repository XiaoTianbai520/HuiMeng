package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.ExternalActor;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * MCP 工具目录与执行。目录固定由服务端提供，浏览器不能注入函数签名。
 *
 * <p>页面类工具（应用状态、设置、文档、画布）转交同项目已连接页面校验并执行；服务端工具复用工作区与媒体服务， 因此权限、修订链与五名额语义与 Web 入口完全一致。
 */
@Service
public class McpToolServiceImpl implements McpToolService {
  private static final long PAGE_TIMEOUT_SECONDS = 120;
  private static final int MAX_READ_BYTES = 2 * 1024 * 1024;
  private final ProjectService projects;
  private final ProjectFileService files;
  private final ProjectMediaService media;
  private final MediaGenerationService generation;
  private final McpControlService control;
  private final ExternalClientService clients;
  private final ToolRuntime runtime;
  private final ObjectMapper json;
  private final List<Tool> tools = new ArrayList<>();
  private final Set<String> names = new LinkedHashSet<>();

  private record Tool(String name, String description, JsonNode schema, boolean page) {}

  public McpToolServiceImpl(
      ProjectService projects,
      ProjectFileService files,
      ProjectMediaService media,
      MediaGenerationService generation,
      McpControlService control,
      ExternalClientService clients,
      AgentToolCatalog catalog,
      ToolRuntime runtime,
      ObjectMapper json) {
    this.projects = projects;
    this.files = files;
    this.media = media;
    this.generation = generation;
    this.control = control;
    this.clients = clients;
    this.runtime = runtime;
    this.json = json;
    pageTools();
    for (var raw : catalog.raw(true)) {
      String name = String.valueOf(raw.get("name"));
      if ("askQuestion".equals(name) || "generateImage".equals(name)) continue;
      add(
          name,
          String.valueOf(raw.get("description")),
          json.valueToTree(raw.get("parameters")),
          true);
    }
    serverTools();
  }

  /**
   * 目录 = 内置工具 + 已安装工具包（市场/文件安装）。
   *
   * <p>每次现算而不是启动时缓存：安装/停用工具后无需重启即可出现在 MCP 目录里。执行走 {@link ToolRuntime} 的隔离上下文，与 Web Agent
   * 共用同一份登记与限制；能否调用仍由凭证能力（工具名）决定，出现在目录里不等于已授权。
   */
  private List<Tool> catalog() {
    var result = new ArrayList<>(tools);
    var known = new HashSet<>(names);
    for (var definition : runtime.definitions()) {
      var function = (Map<?, ?>) definition.get("function");
      if (function == null) continue;
      String name = String.valueOf(function.get("name"));
      if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || !known.add(name)) {
        if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
          org.slf4j.LoggerFactory.getLogger(McpToolServiceImpl.class)
              .warn("已安装工具名不符合 MCP 工具命名规范，已跳过：{}", name);
        continue;
      }
      result.add(
          new Tool(
              name,
              String.valueOf(function.get("description")),
              json.valueToTree(function.get("parameters")),
              false));
    }
    return result;
  }

  private void add(String name, String description, JsonNode schema, boolean page) {
    if (!names.add(name)) return;
    tools.add(new Tool(name, description, schema, page));
  }

  private static JsonNode schema(ObjectMapper json, String text) {
    try {
      return json.readTree(text);
    } catch (Exception ex) {
      throw new IllegalStateException("MCP 工具 schema 无效", ex);
    }
  }

  private void pageTools() {
    add(
        "getAppState",
        "读取绑定项目的页面状态、画布与已加载节点工具；页面未连接时只返回项目标识。",
        schema(
            json,
            """
        {"type":"object","properties":{},"additionalProperties":false}
        """),
        true);
    add(
        "getSettings",
        "读取页面当前设置；凭据类字段已脱敏。",
        schema(
            json,
            """
        {"type":"object","properties":{},"additionalProperties":false}
        """),
        true);
    add(
        "openProject",
        "在已连接页面打开绑定项目并等待工作区就绪。",
        schema(
            json,
            """
        {"type":"object","properties":{"projectId":{"type":"string","minLength":1,"maxLength":128}},"required":["projectId"],"additionalProperties":false}
        """),
        true);
    add(
        "switchPanel",
        "切换页面面板（画布或文档）。",
        schema(
            json,
            """
        {"type":"object","properties":{"panel":{"type":"string","enum":["canvas","document"]}},"required":["panel"],"additionalProperties":false}
        """),
        true);
    add(
        "getDocument",
        "读取当前文档面板内容。",
        schema(
            json,
            """
        {"type":"object","properties":{},"additionalProperties":false}
        """),
        true);
    add(
        "openDocument",
        "在文档面板打开项目内文本文件。",
        schema(
            json,
            """
        {"type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":4096}},"required":["path"],"additionalProperties":false}
        """),
        true);
    add(
        "writeDocument",
        "把内容写入当前文档并保存；须先打开文档。",
        schema(
            json,
            """
        {"type":"object","properties":{"path":{"type":"string","maxLength":4096},"content":{"type":"string","maxLength":1000000}},"required":["content"],"additionalProperties":false}
        """),
        true);
  }

  private void serverTools() {
    add(
        "listWorkspaceFiles",
        "列出项目工作区目录；path 为项目内相对路径，省略表示根目录。",
        schema(
            json,
            """
        {"type":"object","properties":{"path":{"type":"string","maxLength":4096}},"additionalProperties":false}
        """),
        false);
    add(
        "readWorkspaceFile",
        "读取项目内文本文件快照，返回内容、编码与修订号。",
        schema(
            json,
            """
        {"type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":4096},"maxBytes":{"type":"integer","minimum":1,"maximum":2097152}},"required":["path"],"additionalProperties":false}
        """),
        false);
    add(
        "saveWorkspaceFile",
        "保存项目内文本文件；新建须 exclusive=true，覆盖须传 expectedRevision。",
        schema(
            json,
            """
        {"type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":4096},"content":{"type":"string","maxLength":2000000},"expectedRevision":{"type":"string","maxLength":128},"exclusive":{"type":"boolean"}},"required":["path","content"],"additionalProperties":false}
        """),
        false);
    add(
        "listMedia",
        "分页列出项目媒体素材，返回可供生成引用的媒体 ID。",
        schema(
            json,
            """
        {"type":"object","properties":{"cursor":{"type":"string","maxLength":4096},"limit":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}
        """),
        false);
    add(
        "generateMedia",
        "发起受控图片或视频生成并立即返回运行 ID；单次调用占用且仅占用一个名额，必须携带 Idempotency-Key 请求头。",
        schema(
            json,
            """
        {"type":"object","properties":{"prompt":{"type":"string","minLength":1,"maxLength":8000},"mediaType":{"type":"string","enum":["image","video"]},"providerId":{"type":"string","maxLength":64},"modelId":{"type":"string","maxLength":64},"size":{"type":"string","maxLength":16},"ratio":{"type":"string","maxLength":8},"duration":{"type":"integer","minimum":1,"maximum":60},"images":{"type":"array","maxItems":9,"items":{"type":"string","maxLength":64}}},"required":["prompt"],"additionalProperties":false}
        """),
        false);
    add(
        "getRun",
        "查询运行状态；只返回本项目运行。",
        schema(
            json,
            """
        {"type":"object","properties":{"runId":{"type":"string","minLength":1,"maxLength":128}},"required":["runId"],"additionalProperties":false}
        """),
        false);
    add(
        "cancelRun",
        "取消本项目运行；已确认终态才释放名额。",
        schema(
            json,
            """
        {"type":"object","properties":{"runId":{"type":"string","minLength":1,"maxLength":128}},"required":["runId"],"additionalProperties":false}
        """),
        false);
    add(
        "listTeams",
        "列出该凭证获授权的 A2A 团队。",
        schema(
            json,
            """
        {"type":"object","properties":{},"additionalProperties":false}
        """),
        false);
  }

  @Override
  public List<Map<String, Object>> tools(ExternalActor actor) {
    var granted = new HashSet<>(clients.abilities(actor, "MCP"));
    var result = new ArrayList<Map<String, Object>>();
    for (var tool : catalog())
      if (granted.contains(tool.name()))
        result.add(
            Map.of(
                "name", tool.name(),
                "description", tool.description(),
                "inputSchema", tool.schema()));
    return result;
  }

  @Override
  public int totalTools() {
    return catalog().size();
  }

  @Override
  public JsonNode call(
      ExternalActor actor, String name, JsonNode args, String requestId, String idempotencyKey) {
    Tool tool =
        catalog().stream()
            .filter(item -> item.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new ApiException(404, "MCP_TOOL_NOT_FOUND", "未知工具：" + name));
    // 网关已在分派前校验；这里再按同一名单确认，避免未来新增路由绕过。
    if (!clients.abilities(actor, "MCP").contains(name)) throw ApiException.forbidden();
    JsonNode input = args == null || args.isNull() ? json.createObjectNode() : args;
    if (!input.isObject()) throw new ApiException(400, "INVALID_ARGUMENT", "args 必须是对象");
    String project = actor.projectId();
    return tool.page()
        ? pageCall(project, name, input)
        : serverCall(project, actor, name, input, requestId, idempotencyKey);
  }

  private JsonNode pageCall(String project, String name, JsonNode args) {
    if ("getAppState".equals(name)) {
      JsonNode state = control.state(project);
      ObjectNode result = json.createObjectNode();
      result.put("projectId", project);
      result.put("pageConnected", state != null);
      if (state != null) result.set("page", state);
      else result.put("hint", "在项目工作区打开页面后可读取画布、文档与节点工具");
      return result;
    }
    return control.call(project, name, args, PAGE_TIMEOUT_SECONDS);
  }

  private JsonNode serverCall(
      String project,
      ExternalActor actor,
      String name,
      JsonNode args,
      String requestId,
      String idempotencyKey) {
    switch (name) {
      case "listWorkspaceFiles" -> {
        String path = optional(args, "path");
        return json.valueToTree(files.list(project, path == null ? "" : path));
      }
      case "readWorkspaceFile" -> {
        String path = required(args, "path");
        long max = args.path("maxBytes").asLong(256 * 1024L);
        max = Math.max(1, Math.min(max, MAX_READ_BYTES));
        return json.valueToTree(files.snapshot(project, path, max, null));
      }
      case "saveWorkspaceFile" -> {
        String path = required(args, "path");
        String content = required(args, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var input =
            new FileWriteReqDTO(
                path,
                optional(args, "expectedRevision"),
                args.path("exclusive").asBoolean(false),
                "utf-8",
                bytes.length);
        return json.valueToTree(
            files.write(project, input, new ByteArrayInputStream(bytes), requestId));
      }
      case "listMedia" -> {
        int limit = Math.max(1, Math.min(args.path("limit").asInt(20), 50));
        return json.valueToTree(media.list(project, optional(args, "cursor"), limit));
      }
      case "generateMedia" -> {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
          throw new ApiException(400, "INVALID_ARGUMENT", "generateMedia 必须携带 Idempotency-Key 请求头");
        return json.valueToTree(
            generation.start(project, request(args), idempotencyKey, requestId));
      }
      case "getRun" -> {
        return json.valueToTree(generation.get(project, required(args, "runId")));
      }
      case "cancelRun" -> {
        return json.valueToTree(generation.cancel(project, required(args, "runId")));
      }
      case "listTeams" -> {
        return json.valueToTree(Map.of("teams", clients.abilities(actor, "A2A")));
      }
      default -> {
        if (runtime.owns(name)) return runtime.execute(name, requestId, args);
        throw new ApiException(404, "MCP_TOOL_NOT_FOUND", "未知工具：" + name);
      }
    }
  }

  private GenerateImageReqDTO request(JsonNode args) {
    String mediaType = args.path("mediaType").asText("image");
    if (!"image".equals(mediaType) && !"video".equals(mediaType))
      throw new ApiException(400, "INVALID_ARGUMENT", "mediaType 只能是 image 或 video");
    String providerId =
        args.path("providerId")
            .asText("video".equals(mediaType) ? "h3Local3060" : "qwenImage21Local");
    String modelId =
        args.path("modelId").asText("video".equals(mediaType) ? "h3T2v" : "qwenImage21Text");
    String prompt = required(args, "prompt");
    List<GenerateImageMediaDTO> images = null;
    JsonNode list = args.path("images");
    if (list.isArray() && !list.isEmpty()) {
      images = new ArrayList<>();
      for (JsonNode item : list) {
        String id = item.asText("");
        if (!id.matches("[A-Za-z0-9-]{1,64}"))
          throw new ApiException(400, "INVALID_ARGUMENT", "images 只接受项目媒体 ID");
        images.add(new GenerateImageMediaDTO(".huimeng-media/" + id + ".png", "image/png"));
      }
    }
    Integer duration = args.has("duration") ? args.path("duration").asInt() : null;
    return new GenerateImageReqDTO(
        providerId,
        modelId,
        mediaType,
        prompt,
        optional(args, "size"),
        optional(args, "ratio"),
        images,
        duration);
  }

  private static String required(JsonNode args, String name) {
    JsonNode value = args.path(name);
    if (!value.isTextual() || value.asText().isBlank())
      throw new ApiException(400, "INVALID_ARGUMENT", "缺少 " + name);
    return value.asText();
  }

  private static String optional(JsonNode args, String name) {
    JsonNode value = args.path(name);
    return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }
}
