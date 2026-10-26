package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.mapper.DocumentMapper;
import cn.huimeng.dto.req.A2aSettingsReqDTO;
import cn.huimeng.security.Actor;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.AuditService;
import cn.huimeng.service.DocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 个性化文档与 A2A 设置的读写；名称白名单避免变成任意键值存储。 */
@Service
public class DocumentServiceImpl implements DocumentService {
  private static final Set<String> ALLOWED = Set.of("agents", "memory", "a2a");
  private static final int MAX_LENGTH = 20000;

  private final DocumentMapper documents;
  private final AuditService audit;
  private final ObjectMapper json;

  public DocumentServiceImpl(DocumentMapper documents, AuditService audit, ObjectMapper json) {
    this.documents = documents;
    this.audit = audit;
    this.json = json;
  }

  @Override
  public Document read(String name) {
    RequestContext.admin();
    check(name);
    var stored = documents.find(name);
    return new Document(
        stored == null ? "" : stored.content(), stored == null ? "" : stored.revision());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Document save(String name, String content, String expectedRevision, String requestId) {
    Actor actor = RequestContext.admin();
    check(name);
    if (content != null && content.length() > MAX_LENGTH)
      throw new ApiException(413, "DOCUMENT_TOO_LARGE", "内容不能超过 20000 个字符");
    var stored = documents.find(name);
    String current = stored == null ? "" : stored.revision();
    // 空修订号表示首次写入；已有内容时必须带当前修订，避免覆盖他人修改。
    if (!current.isEmpty() && !current.equals(expectedRevision))
      throw new ApiException(409, "REVISION_CONFLICT", "内容已被其他操作修改，请重新加载");
    String text = content == null ? "" : content;
    String revision = sha256(text);
    documents.save(name, text, revision);
    audit.record(actor.id(), null, "DOCUMENT_SAVE", name, "SUCCESS", requestId);
    return new Document(text, revision);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(String name, String requestId) {
    Actor actor = RequestContext.admin();
    check(name);
    documents.delete(name);
    audit.record(actor.id(), null, "DOCUMENT_DELETE", name, "SUCCESS", requestId);
  }

  @Override
  public A2aSettings a2aSettings(String baseUrl) {
    RequestContext.admin();
    var stored = documents.find("a2a");
    if (stored == null || stored.content().isBlank()) return empty(baseUrl);
    try {
      JsonNode node = json.readTree(stored.content());
      return new A2aSettings(
          node.path("enabled").asBoolean(false),
          node.path("directory").asText(""),
          "",
          baseUrl + "/a2a",
          node.path("providerId").asText(""),
          node.path("modelId").asText(""),
          node.path("thinkingLevel").asText(""));
    } catch (Exception ex) {
      return empty(baseUrl);
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public A2aSettings saveA2aSettings(A2aSettingsReqDTO input, String baseUrl, String requestId) {
    Actor actor = RequestContext.admin();
    if (input.enabled() && (input.providerId() == null || input.providerId().isBlank()))
      throw new ApiException(400, "A2A_MODEL_REQUIRED", "启用 A2A 需要选择可用的文本模型");
    var payload = json.createObjectNode();
    payload.put("enabled", input.enabled());
    payload.put("directory", input.directory() == null ? "" : input.directory());
    payload.put("providerId", input.providerId() == null ? "" : input.providerId());
    payload.put("modelId", input.modelId() == null ? "" : input.modelId());
    payload.put("thinkingLevel", input.thinkingLevel() == null ? "" : input.thinkingLevel());
    String text = payload.toString();
    documents.save("a2a", text, sha256(text));
    audit.record(actor.id(), null, "A2A_SETTINGS_SAVE", "a2a", "SUCCESS", requestId);
    return a2aSettings(baseUrl);
  }

  private A2aSettings empty(String baseUrl) {
    return new A2aSettings(false, "", "", baseUrl + "/a2a", "", "", "");
  }

  private void check(String name) {
    if (name == null || !ALLOWED.contains(name))
      throw new ApiException(400, "DOCUMENT_NAME", "不支持的文档名称");
  }

  private String sha256(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.substring(0, 32);
    } catch (Exception ex) {
      throw new ApiException(500, "DOCUMENT_DIGEST", "无法计算修订号");
    }
  }
}
