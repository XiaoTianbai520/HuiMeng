package cn.huimeng.service.impl;

import cn.huimeng.dao.entity.PluginDO;
import cn.huimeng.dao.mapper.PluginMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 随部署分发的内置节点包登记。
 *
 * <p>启动时扫描 classpath 上的 {@code capabilities/nodes/*.umd.js}，把每个产物登记为 {@code type=node}、 {@code
 * source=builtin} 的能力：摘要取产物字节的 SHA-256，版本/显示名/作者/配置规则取构建时写入的 toonflowNode 包头元数据，标识沿用安装链路既有的 {@code
 * type + ":" + name} 约定。
 *
 * <p>登记是幂等的：摘要相同则不写库，管理员设置的启停状态、配置、安装者与更新时间全部保留；只有摘要变化才
 * 更新内容与元数据，并且仍然保留启停状态与配置，因此被管理员停用的内置节点不会在重启后被自动打开。
 *
 * <p>失败策略：单个制品无法解析或登记时记录 WARN 并跳过该节点，其余节点继续登记，也不阻断启动。内置包损坏 不应让已部署的 Web 平台整体不可用；跳过行为由 {@code
 * BuiltinNodeTests} 固定，避免退化成静默成功。
 */
@Component
public class BuiltinNodeRegistrar implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(BuiltinNodeRegistrar.class);
  private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]*");
  private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}");
  private static final String SUFFIX = ".umd.js";
  private static final String HEADER = "/*! toonflowNode:";
  private static final String TYPE = "node";
  private static final String SOURCE = "builtin";
  private static final String INSTALLER = "builtin";

  /** manifest_json 是 TEXT 列，留出余量后仍给 readme 一个明确上限。 */
  private static final int MAX_README_CHARS = 20000;

  private final PluginMapper plugins;
  private final ObjectMapper json;
  private final String location;
  private final ResourcePatternResolver resources = new PathMatchingResourcePatternResolver();

  public BuiltinNodeRegistrar(
      PluginMapper plugins,
      ObjectMapper json,
      @Value("${huimeng.builtin-nodes.location:classpath*:capabilities/nodes/*.umd.js}")
          String location) {
    this.plugins = plugins;
    this.json = json;
    this.location = location;
  }

  /** 登记结果的语义：新增、摘要变化后的更新、完全未动、被跳过。 */
  public enum Change {
    INSERTED,
    UPDATED,
    UNCHANGED,
    SKIPPED
  }

  /** 单个产物的登记结果，便于启动日志与测试断言。 */
  public record Outcome(String name, Change change) {}

  @Override
  public void run(ApplicationArguments args) {
    int registered = 0;
    for (Outcome outcome : sync()) {
      if (outcome.change() != Change.SKIPPED) registered++;
      LOG.info("内置节点 {} 登记结果 {}", outcome.name(), outcome.change());
    }
    if (registered == 0) LOG.info("没有可登记的内置节点产物：{}", location);
  }

  /**
   * 扫描并登记全部内置节点产物。
   *
   * @return 按文件名排序的登记结果；单个产物失败时该条为 {@link Change#SKIPPED}
   */
  public List<Outcome> sync() {
    Resource[] found;
    try {
      found = resources.getResources(location);
    } catch (IOException ex) {
      LOG.warn("内置节点产物位置无法读取，已跳过本轮登记：{}", location, ex);
      return List.of();
    }
    List<Resource> ordered = new ArrayList<>(List.of(found));
    ordered.sort(Comparator.comparing(BuiltinNodeRegistrar::fileNameOf));
    List<Outcome> outcomes = new ArrayList<>();
    for (Resource resource : ordered) {
      String fileName = fileNameOf(resource);
      if (!fileName.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) continue;
      String name;
      try {
        name = nameOf(fileName);
      } catch (RuntimeException ex) {
        LOG.warn("内置节点文件名不合法，已跳过：{}（{}）", fileName, ex.toString());
        continue;
      }
      try {
        outcomes.add(new Outcome(name, save(fileName, resource.getInputStream().readAllBytes())));
      } catch (IOException | RuntimeException ex) {
        // 不吞掉：WARN 留痕 + 该节点跳过，其余节点照常登记，启动不被单个坏包阻断。
        LOG.warn("内置节点 {} 登记失败，已跳过：{}", fileName, ex.toString());
        outcomes.add(new Outcome(name, Change.SKIPPED));
      }
    }
    return List.copyOf(outcomes);
  }

  /**
   * 登记单个产物，按摘要决定是否需要写库。
   *
   * @param fileName 产物文件名，必须是 {@code <name>.umd.js}
   * @param artifact 产物字节，摘要与内容都取这里的原始字节
   * @return 本次登记实际发生的变更
   */
  public Change save(String fileName, byte[] artifact) {
    String name = nameOf(fileName);
    if (artifact == null || artifact.length == 0)
      throw new IllegalArgumentException("内置节点产物为空：" + fileName);
    Metadata metadata = metadata(artifact);
    String sha256 = sha256Hex(artifact);
    String id = TYPE + ":" + name;
    PluginDO existing = plugins.find(TYPE, name);
    String manifest = manifest(fileName, name, metadata, artifact.length, sha256);
    Instant now = Instant.now();
    if (existing == null) {
      plugins.insert(
          new PluginDO(
              id,
              TYPE,
              name,
              metadata.displayName(),
              metadata.version(),
              sha256,
              SOURCE,
              null,
              true,
              null,
              manifest,
              artifact,
              INSTALLER,
              now,
              now));
      return Change.INSERTED;
    }
    if (!SOURCE.equals(existing.source())) {
      // 同名但非内置的能力由管理员安装，内置登记不覆盖它，避免静默替换管理员的内容。
      LOG.warn("已存在同名的非内置节点 {}，跳过内置登记（来源 {}）", name, existing.source());
      return Change.SKIPPED;
    }
    if (sha256.equals(existing.sha256())) return Change.UNCHANGED;
    plugins.replace(
        new PluginDO(
            id,
            TYPE,
            name,
            metadata.displayName(),
            metadata.version(),
            sha256,
            SOURCE,
            existing.sourceUrl(),
            existing.enabled(),
            existing.configJson(),
            manifest,
            artifact,
            existing.installedBy(),
            existing.createdAt(),
            now));
    return Change.UPDATED;
  }

  /** 构建期元数据随产物一起分发，登记时解析，避免第二份版本来源。 */
  private record Metadata(
      String displayName,
      String version,
      String author,
      String github,
      JsonNode configRules,
      String readme) {}

  private Metadata metadata(byte[] artifact) {
    String source = new String(artifact, StandardCharsets.UTF_8);
    if (!source.startsWith(HEADER))
      throw new IllegalArgumentException("产物缺少 toonflowNode 元数据包头，请用 nodes-scaffold 构建");
    int end = source.indexOf("*/", HEADER.length());
    if (end < 0) throw new IllegalArgumentException("产物元数据包头没有结束标记");
    JsonNode node;
    try {
      node = json.readTree(source.substring(HEADER.length(), end).trim());
    } catch (Exception ex) {
      throw new IllegalArgumentException("产物元数据不是合法 JSON", ex);
    }
    String displayName = node.path("displayName").asText("").trim();
    String version = node.path("version").asText("").trim();
    String readme = node.path("readme").asText("");
    if (displayName.isEmpty() || displayName.length() > 200)
      throw new IllegalArgumentException("产物元数据缺少有效的 displayName");
    if (!VERSION.matcher(version).matches())
      throw new IllegalArgumentException("产物元数据缺少有效的 version");
    // manifest_json 是 TEXT（64 KB）；超长 readme 在 MySQL 上会写入失败，H2 上却看不出来。
    // 这里显式拒绝而不是静默截断，让问题落在上面的 WARN 分支。
    if (readme.length() > MAX_README_CHARS)
      throw new IllegalArgumentException("产物元数据 readme 超过 " + MAX_README_CHARS + " 字符");
    return new Metadata(
        displayName,
        version,
        node.path("author").asText("").trim(),
        node.path("github").asText("").trim(),
        node.path("configRules"),
        readme);
  }

  private String manifest(
      String fileName, String name, Metadata metadata, int bytes, String sha256) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("type", TYPE);
    manifest.put("name", name);
    manifest.put("version", metadata.version());
    manifest.put("displayName", metadata.displayName());
    manifest.put("author", metadata.author());
    manifest.put("github", metadata.github());
    manifest.put("configRules", metadata.configRules());
    manifest.put("readme", metadata.readme());
    manifest.put("file", fileName);
    manifest.put("bytes", bytes);
    manifest.put("sha256", sha256);
    manifest.put("origin", "classpath:capabilities/nodes/" + fileName);
    try {
      return json.writeValueAsString(manifest);
    } catch (Exception ex) {
      throw new IllegalStateException("无法序列化内置节点清单：" + name, ex);
    }
  }

  private static String fileNameOf(Resource resource) {
    String name = resource.getFilename();
    return name == null ? "" : name;
  }

  private static String nameOf(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(SUFFIX))
      throw new IllegalArgumentException("内置节点产物必须以 " + SUFFIX + " 结尾：" + fileName);
    String name = fileName.substring(0, fileName.length() - SUFFIX.length());
    if (!NAME.matcher(name).matches())
      throw new IllegalArgumentException("内置节点标识必须是小驼峰：" + fileName);
    return name;
  }

  static String sha256Hex(byte[] content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) hex.append(String.format("%02x", value));
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("无法计算内置节点摘要", ex);
    }
  }
}
