package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.PluginDO;
import cn.huimeng.dao.mapper.PluginMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 已安装工具包的服务端执行。
 *
 * <p>工具包是 ESM，`createTools({config})` 返回带 {@code execute(id, params)} 的工具数组。执行放在进程内的 JS 上下文里：
 * 禁止宿主访问、禁止文件与网络 IO、禁止创建线程，语句数与墙钟时间都有上限，单次调用结束即销毁上下文。
 *
 * <p>当前只接受自包含的工具包：请求里出现 {@code import}/{@code require} 一律拒绝并说明原因，不做模块解析。
 */
@Component
public class ToolRuntime {
  private static final Logger LOG = LoggerFactory.getLogger(ToolRuntime.class);
  private static final Pattern IMPORT =
      Pattern.compile("(?m)^\\s*(?:import\\b|export\\s+[^;]*\\bfrom\\b|.*\\brequire\\s*\\()");
  private static final long STATEMENT_LIMIT = 20_000_000L;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final int MAX_RESULT_CHARS = 100_000;
  private static final int MAX_ERROR_CHARS = 300;
  private static final int MAX_ERROR_CACHE = 512;

  /** 加载结果缓存：键为「插件 id + 摘要」，值为空串表示可加载，否则是失败原因。 */
  private final Map<String, String> errors = new ConcurrentHashMap<>();

  /** 与 Agent 内置工具重名时以内置为准，市场工具跳过并记录。 */
  private static final Set<String> RESERVED =
      Set.of("askQuestion", "generateImage", "canvasOperations");

  private static final String GLUE =
      """
      (function (module, configJson, toolName, callId, argsJson) {
        const plugin = module.default;
        if (!plugin || typeof plugin.createTools !== "function")
          throw new Error("工具包未导出 createTools");
        const config = JSON.parse(configJson);
        const tools = plugin.createTools({ config });
        if (!Array.isArray(tools)) throw new Error("createTools 未返回数组");
        const tool = tools.find(item => item && item.name === toolName);
        if (!tool || typeof tool.execute !== "function") throw new Error("工具不存在：" + toolName);
        return tool.execute(callId, JSON.parse(argsJson));
      })
      """;
  private static final String DESCRIBE =
      """
      (function (module, configJson) {
        const plugin = module.default;
        if (!plugin || typeof plugin.createTools !== "function")
          throw new Error("工具包未导出 createTools");
        return JSON.stringify(plugin.createTools({ config: JSON.parse(configJson) }).map(tool => ({
          name: String(tool && tool.name || ""),
          description: String(tool && tool.description || ""),
          parameters: tool && tool.parameters ? tool.parameters : { type: "object", properties: {} }
        })));
      })
      """;

  private final PluginMapper plugins;
  private final ObjectMapper json;

  public ToolRuntime(PluginMapper plugins, ObjectMapper json) {
    this.plugins = plugins;
    this.json = json;
  }

  /** 模型可见的工具定义：只读已启用工具包，损坏、重名或越界的包直接跳过。 */
  public List<Map<String, Object>> definitions() {
    var result = new ArrayList<Map<String, Object>>();
    var taken = new HashSet<>(RESERVED);
    for (PluginDO plugin : plugins.list()) {
      if (!"tool".equals(plugin.type()) || !plugin.enabled()) continue;
      for (Descriptor descriptor : describe(plugin)) {
        if (!taken.add(descriptor.name())) {
          LOG.warn("市场工具与内置或已有工具重名，已跳过：{}", descriptor.name());
          continue;
        }
        var function = new LinkedHashMap<String, Object>();
        function.put("name", descriptor.name());
        function.put("description", descriptor.description());
        function.put("parameters", descriptor.parameters());
        result.add(Map.of("type", "function", "function", function));
      }
    }
    return result;
  }

  /** 是否由已安装工具包提供该工具；调用前用它做分派。 */
  public boolean owns(String toolName) {
    for (PluginDO plugin : plugins.list()) {
      if (!"tool".equals(plugin.type()) || !plugin.enabled()) continue;
      for (Descriptor descriptor : describe(plugin))
        if (descriptor.name().equals(toolName)) return true;
    }
    return false;
  }

  /** 执行一次工具调用，返回工具给出的 JSON 结果；失败按可诊断的错误码抛出。 */
  public JsonNode execute(String toolName, String callId, JsonNode arguments) {
    PluginDO plugin = owner(toolName);
    if (plugin == null) throw new ApiException(400, "TOOL_UNKNOWN", "未接通该工具：" + toolName);
    String source = source(plugin);
    String result =
        run(
            toolName,
            context -> {
              Value module = evaluate(context, source, plugin.name());
              Value glue = context.eval("js", GLUE);
              Value outcome =
                  glue.execute(
                      module,
                      plugin.configJson() == null ? "{}" : plugin.configJson(),
                      toolName,
                      callId == null ? UUID.randomUUID().toString() : callId,
                      arguments == null ? "{}" : arguments.toString());
              return json(context, outcome);
            });
    try {
      return json.readTree(result);
    } catch (Exception ex) {
      return json.getNodeFactory().textNode(result);
    }
  }

  /**
   * 加载失败原因；{@code null} 表示可正常加载。按「插件 + 摘要」缓存，避免每次列表都重跑脚本。
   *
   * <p>没有这个反馈时，工具包损坏或依赖外部模块只会被静默跳过，管理员无法判断「已安装」为何不生效。
   */
  public String loadError(PluginDO plugin) {
    if (!"tool".equals(plugin.type()) || !plugin.enabled()) return null;
    String key = plugin.id() + "@" + plugin.sha256();
    String cached = errors.get(key);
    if (cached != null) return cached.isEmpty() ? null : cached;
    String message = null;
    try {
      String source = source(plugin);
      run(
          plugin.name(),
          context -> {
            Value module = evaluate(context, source, plugin.name());
            return context
                .eval("js", DESCRIBE)
                .execute(module, plugin.configJson() == null ? "{}" : plugin.configJson())
                .toString();
          });
    } catch (RuntimeException ex) {
      message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
      if (message.length() > MAX_ERROR_CHARS) message = message.substring(0, MAX_ERROR_CHARS) + "…";
    }
    if (errors.size() > MAX_ERROR_CACHE) errors.clear();
    errors.put(key, message == null ? "" : message);
    return message;
  }

  private List<Descriptor> describe(PluginDO plugin) {
    try {
      String source = source(plugin);
      String raw =
          run(
              plugin.name(),
              context -> {
                Value module = evaluate(context, source, plugin.name());
                return context
                    .eval("js", DESCRIBE)
                    .execute(module, plugin.configJson() == null ? "{}" : plugin.configJson())
                    .toString();
              });
      JsonNode list = json.readTree(raw);
      var result = new ArrayList<Descriptor>();
      if (list.isArray())
        for (JsonNode item : list) {
          String name = item.path("name").asText("");
          if (!ToolPackage.NAME.matcher(name).matches()) continue;
          result.add(
              new Descriptor(
                  plugin.name(),
                  name,
                  item.path("description").asText(""),
                  item.path("parameters")));
        }
      return result;
    } catch (Exception ex) {
      LOG.warn("工具包 {} 无法加载，已跳过：{}", plugin.name(), ex.getMessage());
      return List.of();
    }
  }

  private PluginDO owner(String toolName) {
    for (PluginDO plugin : plugins.list()) {
      if (!"tool".equals(plugin.type()) || !plugin.enabled()) continue;
      for (Descriptor descriptor : describe(plugin))
        if (descriptor.name().equals(toolName)) return plugin;
    }
    return null;
  }

  /** 只取包头之后的正文；带模块引用的包在执行前就拒绝。 */
  private String source(PluginDO plugin) {
    byte[] content = plugins.content(plugin.id());
    if (content == null || content.length == 0)
      throw new ApiException(404, "TOOL_CONTENT_MISSING", "工具包内容缺失");
    var lines = new String(content, StandardCharsets.UTF_8).lines().toList();
    int from = lines.isEmpty() ? 0 : 1;
    if (from < lines.size() && lines.get(from).startsWith("/*! toonflowToolClient:")) from++;
    String body = String.join("\n", lines.subList(Math.min(from, lines.size()), lines.size()));
    if (IMPORT.matcher(body).find())
      throw new ApiException(409, "TOOL_RUNTIME_IMPORT_UNSUPPORTED", "工具包引用了外部模块，当前执行器只支持自包含工具包");
    return body;
  }

  /** 把（可能是 Promise 的）执行结果收敛成 JSON 文本；宿主重新进入上下文以推进微任务。 */
  private String json(Context context, Value outcome) throws Exception {
    Value stringify =
        context.eval(
            "js",
            "(value) => Promise.resolve(value).then(result => JSON.stringify(result ?? null))");
    Value promise = stringify.execute(outcome);
    var done = new CompletableFuture<String>();
    promise.invokeMember(
        "then",
        (org.graalvm.polyglot.proxy.ProxyExecutable)
            args -> {
              done.complete(args[0].isNull() ? "null" : args[0].asString());
              return null;
            },
        (org.graalvm.polyglot.proxy.ProxyExecutable)
            args -> {
              done.completeExceptionally(new IllegalStateException(args[0].toString()));
              return null;
            });
    context.eval("js", "0");
    return done.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
  }

  private Value evaluate(Context context, String source, String name) {
    try {
      return context.eval(
          Source.newBuilder("js", source, name + ".tool.mjs")
              .mimeType("application/javascript+module")
              .encoding(StandardCharsets.UTF_8)
              .build());
    } catch (Exception ex) {
      throw new ApiException(409, "TOOL_RUNTIME_INVALID", "工具包执行失败：" + ex.getMessage());
    }
  }

  private interface Body {
    String run(Context context) throws Exception;
  }

  /** 每次调用一个独立上下文：禁止宿主访问与 IO，语句数受限，超时强制关闭。 */
  private String run(String label, Body body) {
    ExecutorService pool =
        Executors.newSingleThreadExecutor(
            r -> {
              var thread = new Thread(r, "tool-runtime-" + label);
              thread.setDaemon(true);
              return thread;
            });
    try (Context context =
        Context.newBuilder("js")
            .allowExperimentalOptions(true)
            .option("js.esm-eval-returns-exports", "true")
            .option("js.intl-402", "false")
            .option("engine.WarnInterpreterOnly", "false")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup(name -> false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
            .allowIO(IOAccess.NONE)
            .resourceLimits(
                org.graalvm.polyglot.ResourceLimits.newBuilder()
                    .statementLimit(STATEMENT_LIMIT, source -> true)
                    .build())
            .build()) {
      Future<String> future = pool.submit(() -> body.run(context));
      String result;
      try {
        result = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException ex) {
        context.close(true);
        future.cancel(true);
        throw new ApiException(504, "TOOL_TIMEOUT", "工具执行超时");
      } catch (ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof ApiException api) throw api;
        throw new ApiException(
            409,
            "TOOL_EXECUTION_FAILED",
            "工具执行失败：" + (cause == null ? "未知错误" : cause.getMessage()));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new ApiException(503, "TOOL_INTERRUPTED", "工具执行被中断");
      }
      return result.length() > MAX_RESULT_CHARS ? result.substring(0, MAX_RESULT_CHARS) : result;
    } finally {
      pool.shutdownNow();
    }
  }

  private record Descriptor(String plugin, String name, String description, JsonNode parameters) {}
}
