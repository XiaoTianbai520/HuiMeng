package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.MediaProviderDO;
import cn.huimeng.dao.mapper.MediaProviderMapper;
import cn.huimeng.dto.req.MediaProviderReqDTO;
import cn.huimeng.dto.resp.MediaProviderDTOs;
import cn.huimeng.security.Actor;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.AuditService;
import cn.huimeng.service.MediaProviderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自定义媒体供应商文件的管理面。
 *
 * <p>只做两件事：把来源保存下来、把它<b>声明</b>的模型清单读出来。解析只取 {@code models: [...]} 这段数据字面量并在无宿主访问的 JS
 * 上下文里求值，不执行供应商代码的函数体，因此不需要 Node/Bun，也不会因为来源里有网络/文件调用而产生副作用。
 *
 * <p>生成链路仍只走内置供应商：本平台没有执行供应商 {@code generate} 的运行时，返回里固定 {@code executable=false}，面板据此禁用生成，
 * 避免把「已登记」显示成「可用」。
 */
@Service
public class MediaProviderServiceImpl implements MediaProviderService {
  private static final Logger LOG = LoggerFactory.getLogger(MediaProviderServiceImpl.class);
  private static final int MAX_SOURCE_CHARS = 2 * 1024 * 1024;
  private static final long STATEMENT_LIMIT = 5_000_000L;
  private static final Pattern FILE_NAME =
      Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}\\.(ts|js|mjs)$");
  private static final Pattern MODELS = Pattern.compile("\\bmodels\\s*:\\s*\\[");
  private static final Pattern VERSION =
      Pattern.compile("\\bversion\\s*[:=]\\s*[\"']([^\"']{1,32})[\"']");
  private static final Pattern LABEL =
      Pattern.compile("\\b(?:name|label)\\s*:\\s*[\"']([^\"']{1,120})[\"']");

  private final MediaProviderMapper providers;
  private final AuditService audit;
  private final ObjectMapper json;

  public MediaProviderServiceImpl(
      MediaProviderMapper providers, AuditService audit, ObjectMapper json) {
    this.providers = providers;
    this.audit = audit;
    this.json = json;
  }

  @Override
  public List<MediaProviderDTOs.Provider> list() {
    RequestContext.admin();
    var result = new ArrayList<MediaProviderDTOs.Provider>();
    for (MediaProviderDO provider : providers.listWithoutSource()) result.add(view(provider));
    return result;
  }

  @Override
  public MediaProviderDTOs.Provider validate(MediaProviderReqDTO input) {
    RequestContext.admin();
    String source = input == null ? null : input.source();
    if (source == null || source.isBlank() || source.length() > MAX_SOURCE_CHARS)
      throw new ApiException(400, "MEDIA_PROVIDER_SOURCE", "供应商来源不能为空且不超过 2 MB");
    String fileName = fileName(input.fileName(), source);
    Parsed parsed = parse(source);
    return view(
        new MediaProviderDO(
            fileName,
            parsed.displayName(),
            parsed.version(),
            source,
            parsed.modelsJson(),
            sha256(source),
            Instant.now(),
            Instant.now()));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MediaProviderDTOs.Provider add(MediaProviderReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    String source = input == null ? null : input.source();
    if (source == null || source.isBlank() || source.length() > MAX_SOURCE_CHARS)
      throw new ApiException(400, "MEDIA_PROVIDER_SOURCE", "供应商来源不能为空且不超过 2 MB");
    String fileName = fileName(input.fileName(), source);
    Parsed parsed = parse(source);
    Instant now = Instant.now();
    MediaProviderDO existing = providers.find(fileName);
    var row =
        new MediaProviderDO(
            fileName,
            parsed.displayName(),
            parsed.version(),
            source,
            parsed.modelsJson(),
            sha256(source),
            existing == null ? now : existing.createdAt(),
            now);
    if (existing == null) providers.insert(row);
    else providers.update(row);
    audit.record(
        actor.id(),
        null,
        existing == null ? "MEDIA_PROVIDER_ADD" : "MEDIA_PROVIDER_UPDATE",
        fileName,
        "SUCCESS",
        requestId);
    return view(row);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MediaProviderDTOs.Provider refreshModels(MediaProviderReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    String fileName = requiredName(input);
    MediaProviderDO existing = providers.find(fileName);
    if (existing == null) throw new ApiException(404, "NOT_FOUND", "供应商文件不存在");
    Parsed parsed = parse(existing.source());
    providers.updateModels(fileName, parsed.modelsJson());
    audit.record(actor.id(), null, "MEDIA_PROVIDER_MODELS", fileName, "SUCCESS", requestId);
    MediaProviderDO updated = providers.find(fileName);
    return view(updated == null ? existing : updated);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MediaProviderDTOs.Provider save(MediaProviderReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    String fileName = requiredName(input);
    MediaProviderDO existing = providers.find(fileName);
    if (existing == null) throw new ApiException(404, "NOT_FOUND", "供应商文件不存在");
    if (input.models() == null || input.models().isEmpty())
      throw new ApiException(400, "MEDIA_PROVIDER_MODELS", "模型清单不能为空");
    Map<String, JsonNode> declarations = declarations(existing.modelsJson());
    var merged = json.createArrayNode();
    for (var model : input.models()) {
      if (model.id() == null || model.id().isBlank() || model.id().length() > 128)
        throw new ApiException(400, "MEDIA_PROVIDER_MODELS", "模型 id 不合法");
      ObjectNode item = json.createObjectNode();
      item.put("id", model.id());
      item.put("label", model.label() == null ? model.id() : model.label());
      item.put("type", model.type() == null ? "image" : model.type());
      JsonNode declaration = declarations.get(model.id());
      if (declaration != null) item.set("declaration", declaration);
      merged.add(item);
    }
    providers.updateModels(fileName, merged.toString());
    audit.record(actor.id(), null, "MEDIA_PROVIDER_SAVE", fileName, "SUCCESS", requestId);
    MediaProviderDO updated = providers.find(fileName);
    return view(updated == null ? existing : updated);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void delete(MediaProviderReqDTO input, String requestId) {
    Actor actor = RequestContext.admin();
    String fileName = requiredName(input);
    if (providers.find(fileName) == null) throw new ApiException(404, "NOT_FOUND", "供应商文件不存在");
    providers.remove(fileName);
    audit.record(actor.id(), null, "MEDIA_PROVIDER_DELETE", fileName, "SUCCESS", requestId);
  }

  // --- 解析与视图 -----------------------------------------------------------

  private record Parsed(String displayName, String version, String modelsJson) {}

  private static String requiredName(MediaProviderReqDTO input) {
    String fileName = input == null ? null : input.fileName();
    if (fileName == null || !FILE_NAME.matcher(fileName).matches())
      throw new ApiException(400, "MEDIA_PROVIDER_FILE", "供应商文件名不合法");
    return fileName;
  }

  private static String fileName(String requested, String source) {
    if (requested != null && !requested.isBlank()) {
      if (!FILE_NAME.matcher(requested).matches())
        throw new ApiException(400, "MEDIA_PROVIDER_FILE", "供应商文件名不合法");
      return requested;
    }
    Matcher label = LABEL.matcher(source);
    String base = label.find() ? label.group(1) : "provider";
    base = base.replaceAll("[^A-Za-z0-9_-]", "");
    if (base.isEmpty()) base = "provider";
    if (!Character.isLetter(base.charAt(0))) base = "p" + base;
    return (base.length() > 56 ? base.substring(0, 56) : base) + ".ts";
  }

  private Parsed parse(String source) {
    String literal = modelsLiteral(source);
    if (literal == null)
      throw new ApiException(400, "MEDIA_PROVIDER_FORMAT", "未找到 models 声明；请确认来源是完整的供应商文件");
    ArrayNode models = evaluate(literal);
    if (models.isEmpty()) throw new ApiException(400, "MEDIA_PROVIDER_FORMAT", "供应商没有声明任何模型");
    var normalized = json.createArrayNode();
    for (JsonNode item : models) {
      if (!item.isObject() || item.path("id").asText("").isBlank())
        throw new ApiException(400, "MEDIA_PROVIDER_FORMAT", "模型缺少 id");
      ObjectNode entry = json.createObjectNode();
      entry.put("id", item.path("id").asText());
      entry.put(
          "label", item.path("label").asText(item.path("name").asText(item.path("id").asText())));
      entry.put("type", item.path("type").asText("image"));
      entry.set("declaration", item);
      normalized.add(entry);
    }
    Matcher version = VERSION.matcher(source);
    String displayName = displayName(source, models);
    return new Parsed(
        displayName, version.find() ? version.group(1) : "0.0.0", normalized.toString());
  }

  private static String displayName(String source, ArrayNode models) {
    Matcher matcher =
        Pattern.compile("\\bdisplayName\\s*:\\s*[\"']([^\"']{1,120})[\"']").matcher(source);
    if (matcher.find()) return matcher.group(1);
    Matcher label = Pattern.compile("\\bname\\s*:\\s*[\"']([^\"']{1,120})[\"']").matcher(source);
    if (label.find()) return label.group(1);
    return models.path(0).path("label").asText("自定义供应商");
  }

  /** 取出 {@code models: [...]} 这段数据字面量；字符串里的括号与转义都要跳过。 */
  static String modelsLiteral(String source) {
    Matcher matcher = MODELS.matcher(source);
    if (!matcher.find()) return null;
    int start = source.indexOf('[', matcher.start());
    int depth = 0;
    boolean inString = false;
    boolean escape = false;
    char quote = 0;
    for (int index = start; index < source.length(); index++) {
      char ch = source.charAt(index);
      if (inString) {
        if (escape) escape = false;
        else if (ch == '\\') escape = true;
        else if (ch == quote) inString = false;
        continue;
      }
      if (ch == '"' || ch == '\'' || ch == '`') {
        inString = true;
        quote = ch;
        continue;
      }
      if (ch == '[') depth++;
      else if (ch == ']' && --depth == 0) return source.substring(start, index + 1);
    }
    return null;
  }

  /** 只在数据字面量上求值：无宿主访问、无 IO、语句数受限，不执行供应商的函数体。 */
  private ArrayNode evaluate(String literal) {
    try (Context context =
        Context.newBuilder("js")
            .option("js.intl-402", "false")
            .option("engine.WarnInterpreterOnly", "false")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup(name -> false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
            .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
            .resourceLimits(
                org.graalvm.polyglot.ResourceLimits.newBuilder()
                    .statementLimit(STATEMENT_LIMIT, source -> true)
                    .build())
            .build()) {
      Value value = context.eval("js", "(" + literal + ")");
      Value stringify = context.eval("js", "(value) => JSON.stringify(value)");
      String text = stringify.execute(value).asString();
      JsonNode node = json.readTree(text);
      if (!node.isArray()) throw new ApiException(400, "MEDIA_PROVIDER_FORMAT", "models 必须是数组");
      return (ArrayNode) node;
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      LOG.warn("供应商模型清单解析失败：{}", ex.toString());
      throw new ApiException(
          400, "MEDIA_PROVIDER_FORMAT", "models 声明不是可解析的数据字面量：" + ex.getMessage());
    }
  }

  private MediaProviderDTOs.Provider view(MediaProviderDO provider) {
    var models = new ArrayList<MediaProviderDTOs.Model>();
    try {
      JsonNode root = json.readTree(provider.modelsJson());
      if (root.isArray())
        for (JsonNode item : root)
          models.add(
              new MediaProviderDTOs.Model(
                  item.path("id").asText(),
                  item.path("label").asText(),
                  item.path("type").asText(),
                  item.path("declaration")));
    } catch (Exception ex) {
      LOG.warn("供应商 {} 的模型清单损坏：{}", provider.fileName(), ex.toString());
    }
    return new MediaProviderDTOs.Provider(
        provider.fileName(),
        provider.displayName(),
        provider.version(),
        provider.sha256(),
        false,
        List.copyOf(models));
  }

  private Map<String, JsonNode> declarations(String modelsJson) {
    var result = new LinkedHashMap<String, JsonNode>();
    try {
      JsonNode root = json.readTree(modelsJson);
      if (root.isArray())
        for (JsonNode item : root) result.put(item.path("id").asText(), item.path("declaration"));
    } catch (Exception ex) {
      LOG.warn("供应商模型清单无法回读：{}", ex.toString());
    }
    return result;
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }
}
