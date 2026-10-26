package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.PluginDO;
import cn.huimeng.dao.mapper.ConfigurationMapper;
import cn.huimeng.dao.mapper.PluginMapper;
import cn.huimeng.dto.resp.PluginDTOs;
import cn.huimeng.dto.resp.PluginRespDTO;
import cn.huimeng.security.Actor;
import cn.huimeng.security.RequestContext;
import cn.huimeng.security.SecretBox;
import cn.huimeng.service.AuditService;
import cn.huimeng.service.ConfigurationService;
import cn.huimeng.service.PluginService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 能力登记、市场代理与文档型能力读写；下载与归档都在受限范围内解析。 */
@Service
public class PluginServiceImpl implements PluginService {
  private static final Set<String> TYPES = Set.of("node", "tool", "skill", "ext", "agent");
  private static final Set<String> DOCUMENT_TYPES = Set.of("skill", "agent");
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern SECRET_KEY =
      Pattern.compile("(?i).*(key|secret|token|password|credential).*");
  private static final int MAX_BYTES = 20 * 1024 * 1024;
  private static final int MAX_ENTRIES = 200;
  private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;
  private static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;
  private static final Set<String> TEXT_EXT =
      Set.of(".md", ".txt", ".json", ".yaml", ".yml", ".js", ".ts", ".vue", ".csv");

  private final PluginMapper plugins;
  private final ConfigurationMapper configuration;
  private final ConfigurationService settings;
  private final SecretBox box;
  private final AuditService audit;
  private final ObjectMapper json;
  private final String marketEndpoint;
  private final Set<String> artifactHosts;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public PluginServiceImpl(
      PluginMapper plugins,
      ConfigurationMapper configuration,
      ConfigurationService settings,
      SecretBox box,
      AuditService audit,
      ObjectMapper json,
      @Value("${huimeng.market.endpoint:https://api.toonflow.net}") String marketEndpoint,
      @Value("${huimeng.market.artifact-hosts:api.toonflow.net}") String artifactHosts) {
    this.plugins = plugins;
    this.configuration = configuration;
    this.settings = settings;
    this.box = box;
    this.audit = audit;
    this.json = json;
    this.marketEndpoint = marketEndpoint.replaceAll("/+$", "");
    this.artifactHosts =
        new HashSet<>(
            Arrays.stream(artifactHosts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
  }

  @Override
  public List<PluginRespDTO> installed() {
    RequestContext.admin();
    return plugins.list().stream()
        .map(plugin -> view(plugin, plugins.documents(plugin.id()).keySet()))
        .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO installFile(
      String type, String fileName, byte[] content, boolean force, String requestId) {
    checkType(type);
    return install(type, fileName, content, force, "file", null, requestId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO installMarket(
      String type,
      String name,
      String version,
      String url,
      String sha256,
      boolean force,
      String requestId) {
    checkType(type);
    if (!settings.marketConfigured())
      throw new ApiException(409, "MARKET_NOT_CONFIGURED", "团队市场凭据未配置");
    if (url == null || url.isBlank()) throw ApiException.invalid();
    if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw ApiException.invalid();
    byte[] bytes = download(url);
    if (!sha256.equals(sha256Hex(bytes)))
      throw new ApiException(409, "CHECKSUM_MISMATCH", "下载内容与市场摘要不一致");
    return install(type, name + suffixOf(url), bytes, force, "market", url, requestId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO config(
      String type, String name, Map<String, Object> config, String requestId) {
    Actor actor = RequestContext.admin();
    PluginDO existing = require(type, name);
    ObjectNode stored = json.createObjectNode();
    if (config != null) {
      config.forEach(
          (key, value) -> {
            if (SECRET_KEY.matcher(key).matches()) {
              if (value instanceof String text && !text.isBlank())
                stored.put(key, box.encrypt(secretId(existing.id(), key), text));
            } else {
              stored.set(key, json.valueToTree(value));
            }
          });
    }
    plugins.config(existing.id(), write(stored));
    audit.record(actor.id(), null, "PLUGIN_CONFIG", existing.id(), "SUCCESS", requestId);
    return view(require(type, name), plugins.documents(existing.id()).keySet());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO enabled(String type, String name, boolean enabled, String requestId) {
    Actor actor = RequestContext.admin();
    PluginDO existing = require(type, name);
    plugins.enabled(existing.id(), enabled);
    audit.record(
        actor.id(),
        null,
        enabled ? "PLUGIN_ENABLE" : "PLUGIN_DISABLE",
        existing.id(),
        "SUCCESS",
        requestId);
    return view(require(type, name), plugins.documents(existing.id()).keySet());
  }

  @Override
  public Export export(String type, String name) {
    RequestContext.admin();
    PluginDO existing = require(type, name);
    if (DOCUMENT_TYPES.contains(type)) {
      byte[] zip = zip(plugins.documents(existing.id()));
      return new Export(existing.name() + ".zip", "application/zip", zip);
    }
    byte[] content = plugins.content(existing.id());
    if (content == null) throw new ApiException(404, "PLUGIN_CONTENT_MISSING", "该能力没有可导出内容");
    return new Export(exportName(existing), "application/octet-stream", content);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void uninstall(String type, String name, String requestId) {
    Actor actor = RequestContext.admin();
    PluginDO existing = require(type, name);
    if ("builtin".equals(existing.source()))
      throw new ApiException(403, "PLUGIN_BUILTIN", "内置能力不能卸载，只能停用");
    plugins.delete(existing.id());
    audit.record(actor.id(), null, "PLUGIN_UNINSTALL", existing.id(), "SUCCESS", requestId);
  }

  @Override
  public Map<String, String> documents(String type, String name) {
    RequestContext.admin();
    checkDocumentType(type);
    return plugins.documents(require(type, name).id());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO saveDocument(
      String type, String name, String path, String content, String requestId) {
    Actor actor = RequestContext.admin();
    checkDocumentType(type);
    PluginDO existing = require(type, name);
    String safe = safePath(path);
    Map<String, String> documents = new LinkedHashMap<>(plugins.documents(existing.id()));
    if (!documents.containsKey(safe))
      throw new ApiException(404, "DOCUMENT_MISSING", "文档不存在，请重新加载");
    documents.put(safe, content == null ? "" : content);
    plugins.documents(existing.id(), documents);
    audit.record(
        actor.id(), null, "PLUGIN_DOCUMENT_SAVE", existing.id() + ":" + safe, "SUCCESS", requestId);
    return view(require(type, name), documents.keySet());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO createDocument(String type, String name, String path, String requestId) {
    Actor actor = RequestContext.admin();
    checkDocumentType(type);
    PluginDO existing = require(type, name);
    String safe = safePath(path);
    Map<String, String> documents = new LinkedHashMap<>(plugins.documents(existing.id()));
    if (documents.containsKey(safe)) throw new ApiException(409, "DOCUMENT_EXISTS", "同名文档已存在");
    documents.put(safe, "");
    plugins.documents(existing.id(), documents);
    audit.record(
        actor.id(),
        null,
        "PLUGIN_DOCUMENT_CREATE",
        existing.id() + ":" + safe,
        "SUCCESS",
        requestId);
    return view(require(type, name), documents.keySet());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO moveDocument(
      String type, String name, String path, String target, String requestId) {
    Actor actor = RequestContext.admin();
    checkDocumentType(type);
    PluginDO existing = require(type, name);
    String from = safePath(path);
    String to = safePath(target);
    Map<String, String> documents = new LinkedHashMap<>(plugins.documents(existing.id()));
    if (!documents.containsKey(from)) throw new ApiException(404, "DOCUMENT_MISSING", "文档不存在");
    if (documents.containsKey(to)) throw new ApiException(409, "DOCUMENT_EXISTS", "目标文档已存在");
    // 目录移动：把该前缀下的文档整体改名。
    Map<String, String> moved = new LinkedHashMap<>();
    documents.forEach(
        (key, value) -> {
          if (key.equals(from) || key.startsWith(from + "/")) {
            String suffix = key.substring(from.length());
            moved.put(to + suffix, value);
          } else {
            moved.put(key, value);
          }
        });
    if (moved.containsKey(to) && !to.equals(from)) {
      // 目录改名后与既有文档冲突时拒绝，避免覆盖。
      boolean conflicts =
          documents.keySet().stream()
              .anyMatch(key -> key.startsWith(to + "/") && !key.startsWith(from + "/"));
      if (conflicts) throw new ApiException(409, "DOCUMENT_EXISTS", "目标目录已存在");
    }
    plugins.documents(existing.id(), moved);
    audit.record(
        actor.id(), null, "PLUGIN_DOCUMENT_MOVE", existing.id() + ":" + from, "SUCCESS", requestId);
    return view(require(type, name), moved.keySet());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PluginRespDTO orderDocuments(
      String type, String name, List<String> order, String requestId) {
    Actor actor = RequestContext.admin();
    checkDocumentType(type);
    PluginDO existing = require(type, name);
    Map<String, String> documents = new LinkedHashMap<>(plugins.documents(existing.id()));
    List<String> wanted = order == null ? List.of() : order.stream().map(this::safePath).toList();
    Map<String, String> reordered = new LinkedHashMap<>();
    for (String path : wanted) {
      String content = documents.remove(path);
      if (content != null) reordered.put(path, content);
    }
    reordered.putAll(documents);
    plugins.documents(existing.id(), reordered);
    audit.record(actor.id(), null, "PLUGIN_DOCUMENT_ORDER", existing.id(), "SUCCESS", requestId);
    return view(require(type, name), reordered.keySet());
  }

  @Override
  public PluginDTOs.MarketPage search(int page, int limit, String type, String keyword) {
    RequestContext.admin();
    if (!settings.marketConfigured()) return new PluginDTOs.MarketPage(List.of(), 0, false);
    String key = marketKey();
    ObjectNode body = json.createObjectNode();
    body.put("page", Math.max(1, page));
    body.put("limit", Math.min(50, Math.max(1, limit)));
    body.put("type", type == null || type.isBlank() ? "" : type);
    if (keyword != null && !keyword.isBlank()) body.put("searchKeyword", keyword);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(marketEndpoint + "/v1/plugin/getPlugin"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(write(body)))
            .build();
    JsonNode payload;
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new ApiException(503, "MARKET_UNAVAILABLE", "市场暂不可用，已安装能力仍可使用");
      payload = json.readTree(response.body());
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(503, "MARKET_UNAVAILABLE", "市场暂不可用，已安装能力仍可使用");
    }
    JsonNode data = payload.path("data");
    JsonNode items = data.isArray() ? data : data.path("items");
    List<PluginDTOs.MarketItem> mapped = new ArrayList<>();
    if (items.isArray()) {
      for (JsonNode item : items) {
        String id = item.path("id").asText(item.path("name").asText());
        JsonNode manifest = item.path("manifest");
        Map<String, Object> manifestMap =
            manifest.isObject() ? json.convertValue(manifest, Map.class) : null;
        boolean installable = manifest.isObject() && !manifest.path("sha256").asText("").isBlank();
        mapped.add(
            new PluginDTOs.MarketItem(
                id,
                item.path("type").asText(type),
                item.path("name").asText(),
                item.path("displayName").asText(item.path("name").asText()),
                item.path("author").asText(""),
                item.path("description").asText(""),
                item.path("version").asText(""),
                item.path("fileName").asText(""),
                plugins.collected(id),
                installable,
                manifestMap));
      }
    }
    int total = data.path("total").asInt(mapped.size());
    return new PluginDTOs.MarketPage(mapped, total, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean toggleCollection(String pluginId) {
    RequestContext.admin();
    if (pluginId == null || pluginId.isBlank()) throw ApiException.invalid();
    return plugins.toggleCollection(pluginId);
  }

  // --- 内部 -----------------------------------------------------------------

  private PluginRespDTO install(
      String type,
      String fileName,
      byte[] content,
      boolean force,
      String source,
      String sourceUrl,
      String requestId) {
    Actor actor = RequestContext.admin();
    if (content == null || content.length == 0)
      throw new ApiException(400, "PLUGIN_FILE_EMPTY", "请选择非空插件文件");
    if (content.length > MAX_BYTES)
      throw new ApiException(413, "PLUGIN_FILE_TOO_LARGE", "插件文件不能超过 20 MB");
    String safeName = NAME.matcher(fileName == null ? "" : fileName).matches() ? fileName : "";
    if (safeName.isBlank()) throw new ApiException(400, "PLUGIN_FILE_NAME", "文件名不合法");

    Parsed parsed = parse(type, safeName, content);
    String id = type + ":" + parsed.name();
    String sha = sha256Hex(content);
    PluginDO existing = plugins.find(type, parsed.name());
    if (existing != null) {
      if (existing.sha256().equals(sha)) return view(existing, parsed.documents().keySet());
      if (!force)
        throw new ApiException(409, "VERSION_CONFLICT", "已安装" + existing.version() + "，覆盖需管理员明确确认");
    }
    Instant now = Instant.now();
    PluginDO plugin =
        new PluginDO(
            id,
            type,
            parsed.name(),
            parsed.displayName(),
            parsed.version(),
            sha,
            source,
            sourceUrl,
            true,
            existing == null ? null : existing.configJson(),
            write(parsed.manifest()),
            content,
            actor.id(),
            existing == null ? now : existing.createdAt(),
            now);
    if (existing == null) plugins.insert(plugin);
    else plugins.replace(plugin);
    if (DOCUMENT_TYPES.contains(type)) plugins.documents(id, parsed.documents());
    audit.record(
        actor.id(),
        null,
        existing == null ? "PLUGIN_INSTALL" : "PLUGIN_UPDATE",
        id,
        "SUCCESS",
        requestId);
    return view(require(type, parsed.name()), parsed.documents().keySet());
  }

  private Parsed parse(String type, String fileName, byte[] content) {
    if (DOCUMENT_TYPES.contains(type)
        && fileName.toLowerCase(Locale.ROOT).matches(".*\\.(zip|md)$")) {
      if (fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
        String name = nameOf(fileName);
        Map<String, String> documents =
            Map.of("SKILL.md", new String(content, StandardCharsets.UTF_8));
        return new Parsed(name, name, "1.0.0", manifest(type, name, "1.0.0", documents), documents);
      }
      return parseArchive(type, fileName, content);
    }
    if (DOCUMENT_TYPES.contains(type))
      throw new ApiException(400, "PLUGIN_ARCHIVE", "技能与 Agent 只接受 .md 或 .zip");
    String name = nameOf(fileName);
    String version = versionOf(content);
    Map<String, String> documents = Map.of();
    return new Parsed(name, name, version, manifest(type, name, version, documents), documents);
  }

  private Parsed parseArchive(String type, String fileName, byte[] content) {
    Map<String, String> documents = new LinkedHashMap<>();
    Map<String, Object> fromManifest = new LinkedHashMap<>();
    int entries = 0;
    long total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) throw new ApiException(400, "PLUGIN_ARCHIVE", "归档条目过多");
        String path = entry.getName().replace('\\', '/');
        if (path.startsWith("/") || path.contains(".."))
          throw new ApiException(400, "PLUGIN_ARCHIVE_PATH", "归档包含越界路径");
        if (entry.isDirectory()) continue;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int read;
        while ((read = zip.read(buffer)) > 0) {
          total += read;
          if (out.size() + read > MAX_ENTRY_BYTES)
            throw new ApiException(413, "PLUGIN_ARCHIVE_TOO_LARGE", "归档内单文件过大");
          if (total > MAX_TOTAL_BYTES)
            throw new ApiException(413, "PLUGIN_ARCHIVE_TOO_LARGE", "归档解压后过大");
          out.write(buffer, 0, read);
        }
        if (path.equals("manifest.json") || path.endsWith("/manifest.json")) {
          JsonNode node = json.readTree(out.toByteArray());
          fromManifest.put("name", node.path("name").asText(""));
          fromManifest.put("version", node.path("version").asText(""));
          fromManifest.put("displayName", node.path("displayName").asText(""));
          fromManifest.put("description", node.path("description").asText(""));
          continue;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (TEXT_EXT.stream().anyMatch(lower::endsWith))
          documents.put(path, out.toString(StandardCharsets.UTF_8));
      }
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(400, "PLUGIN_ARCHIVE", "无法解析归档文件");
    }
    String name =
        String.valueOf(fromManifest.getOrDefault("name", "")).isBlank()
            ? nameOf(fileName)
            : String.valueOf(fromManifest.get("name"));
    String version =
        String.valueOf(fromManifest.getOrDefault("version", "")).isBlank()
            ? "1.0.0"
            : String.valueOf(fromManifest.get("version"));
    Map<String, Object> manifest = manifest(type, name, version, documents);
    if (!String.valueOf(fromManifest.getOrDefault("displayName", "")).isBlank())
      manifest.put("displayName", fromManifest.get("displayName"));
    if (!String.valueOf(fromManifest.getOrDefault("description", "")).isBlank())
      manifest.put("description", fromManifest.get("description"));
    return new Parsed(
        name, String.valueOf(manifest.get("displayName")), version, manifest, documents);
  }

  private Map<String, Object> manifest(
      String type, String name, String version, Map<String, String> documents) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("type", type);
    manifest.put("name", name);
    manifest.put("version", version);
    manifest.put("displayName", name);
    manifest.put("documents", documents.size());
    return manifest;
  }

  private PluginRespDTO view(PluginDO plugin, Collection<String> documents) {
    Map<String, Object> manifest = readMap(plugin.manifestJson());
    Map<String, Object> config = readMap(plugin.configJson());
    config.replaceAll((key, value) -> SECRET_KEY.matcher(key).matches() ? "***" : value);
    return new PluginRespDTO(
        plugin.type(),
        plugin.name(),
        plugin.displayName(),
        String.valueOf(manifest.getOrDefault("author", "")),
        String.valueOf(manifest.getOrDefault("description", "")),
        plugin.version(),
        plugin.enabled(),
        plugin.source(),
        true,
        !"builtin".equals(plugin.source()),
        plugins.collected("market:" + plugin.type() + ":" + plugin.name()),
        false,
        "local",
        manifest,
        config,
        List.copyOf(documents),
        null);
  }

  private Map<String, Object> readMap(String raw) {
    if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
    try {
      JsonNode node = json.readTree(raw);
      if (!node.isObject()) return new LinkedHashMap<>();
      return json.convertValue(node, Map.class);
    } catch (Exception ex) {
      return new LinkedHashMap<>();
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception ex) {
      throw new ApiException(500, "PLUGIN_SERIALIZE", "无法序列化插件数据");
    }
  }

  private String marketKey() {
    String encrypted = configuration.encryptedSecret(ConfigurationService.MARKET);
    if (encrypted == null) throw new ApiException(409, "MARKET_NOT_CONFIGURED", "团队市场凭据未配置");
    return box.decrypt(ConfigurationService.MARKET, encrypted);
  }

  private byte[] download(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (Exception ex) {
      throw new ApiException(400, "PLUGIN_SOURCE", "下载地址不合法");
    }
    String host = uri.getHost() == null ? "" : uri.getHost();
    boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host);
    if (!artifactHosts.contains(host) && !loopback)
      throw new ApiException(403, "PLUGIN_SOURCE_FORBIDDEN", "下载来源不在允许清单内");
    if (!"https".equals(uri.getScheme()) && !loopback)
      throw new ApiException(403, "PLUGIN_SOURCE_FORBIDDEN", "下载来源必须使用 HTTPS");
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
    try {
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200)
        throw new ApiException(502, "PLUGIN_DOWNLOAD", "下载插件失败：HTTP " + response.statusCode());
      if (response.body().length > MAX_BYTES)
        throw new ApiException(413, "PLUGIN_FILE_TOO_LARGE", "插件文件不能超过 20 MB");
      return response.body();
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(502, "PLUGIN_DOWNLOAD", "下载插件失败，请稍后重试");
    }
  }

  private PluginDO require(String type, String name) {
    checkType(type);
    PluginDO plugin = plugins.find(type, name);
    if (plugin == null) throw new ApiException(404, "PLUGIN_NOT_FOUND", "未安装该能力");
    return plugin;
  }

  private void checkType(String type) {
    if (type == null || !TYPES.contains(type)) throw ApiException.invalid();
  }

  private void checkDocumentType(String type) {
    if (type == null || !DOCUMENT_TYPES.contains(type))
      throw new ApiException(400, "PLUGIN_TYPE", "该能力没有可编辑文档");
  }

  private String safePath(String path) {
    if (path == null || path.isBlank()) throw ApiException.invalid();
    String normalized = path.replace('\\', '/');
    if (normalized.startsWith("/") || normalized.contains("..") || normalized.length() > 512)
      throw new ApiException(400, "PLUGIN_PATH", "路径不合法");
    return normalized;
  }

  private String nameOf(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    for (String suffix :
        List.of(".agent.zip", ".umd.js", ".tool.js", ".tar.gz", ".tgz", ".zip", ".md", ".js")) {
      if (lower.endsWith(suffix)) {
        String name = fileName.substring(0, fileName.length() - suffix.length());
        if (NAME.matcher(name).matches()) return name;
      }
    }
    throw new ApiException(400, "PLUGIN_FILE_NAME", "无法从文件名确定能力标识");
  }

  private String suffixOf(String url) {
    String lower = url.toLowerCase(Locale.ROOT);
    for (String suffix : List.of(".agent.zip", ".umd.js", ".tool.js", ".md", ".zip"))
      if (lower.contains(suffix)) return suffix;
    return ".zip";
  }

  private String versionOf(byte[] content) {
    String text = new String(content, StandardCharsets.UTF_8);
    var matcher = Pattern.compile("@version\\s+([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(text);
    return matcher.find() ? matcher.group(1) : "1.0.0";
  }

  private String exportName(PluginDO plugin) {
    return switch (plugin.type()) {
      case "node", "ext" -> plugin.name() + ".umd.js";
      case "tool" -> plugin.name() + ".tool.js";
      default -> plugin.name() + ".zip";
    };
  }

  private String secretId(String pluginId, String field) {
    return "plugin:" + pluginId + ":" + field;
  }

  private byte[] zip(Map<String, String> documents) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (var entry : documents.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (Exception ex) {
      throw new ApiException(500, "PLUGIN_EXPORT", "导出失败");
    }
    return out.toByteArray();
  }

  private String sha256Hex(byte[] content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception ex) {
      throw new ApiException(500, "PLUGIN_DIGEST", "无法计算摘要");
    }
  }

  private record Parsed(
      String name,
      String displayName,
      String version,
      Map<String, Object> manifest,
      Map<String, String> documents) {}
}
