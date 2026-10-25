package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dao.entity.*;
import cn.huimeng.dao.mapper.*;
import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号隔离的个人偏好；共享密钥和供应商配置不能写入此存储。 */
@Service
public class PreferencesServiceImpl implements PreferencesService {
  private static final Set<String> FIELDS = Set.of("ui", "general", "stores", "documentExtensions");
  private static final Set<String> FORBIDDEN =
      Set.of(
          "apikey",
          "token",
          "accesstoken",
          "authorization",
          "password",
          "secret",
          "secretkey",
          "clientsecret",
          "providers",
          "customproviders",
          "mediamodels",
          "market",
          "tfrouter",
          "mcp",
          "a2a");

  private final PreferencesMapper preferencesMapper;
  private final UserMapper userMapper;
  private final ObjectMapper json;

  public PreferencesServiceImpl(
      PreferencesMapper preferencesMapper, UserMapper userMapper, ObjectMapper json) {
    this.preferencesMapper = preferencesMapper;
    this.userMapper = userMapper;
    this.json = json;
  }

  @Override
  public JsonNode get() {
    String value = preferencesMapper.find(RequestContext.actor().id());
    try {
      return value == null ? json.createObjectNode() : json.readTree(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Preferences unavailable");
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void save(JsonNode input) {
    String id = RequestContext.actor().id();
    if (input == null || !input.isObject()) throw ApiException.invalid();
    input
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!FIELDS.contains(field) || !input.get(field).isObject())
                throw ApiException.invalid();
            });
    if (input.has("documentExtensions"))
      input
          .get("documentExtensions")
          .elements()
          .forEachRemaining(
              value -> {
                if (!value.isTextual()) throw ApiException.invalid();
              });
    validate(input, 0);
    String value = input.toString();
    if (value.getBytes(StandardCharsets.UTF_8).length > 32768) throw ApiException.invalid();

    userMapper.lockAccount(id);
    preferencesMapper.save(id, value);
  }

  private void validate(JsonNode value, int depth) {
    if (depth > 32) throw ApiException.invalid();
    if (value.isObject())
      value
          .fields()
          .forEachRemaining(
              entry -> {
                if (FORBIDDEN.contains(
                    entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")))
                  throw ApiException.invalid();
                validate(entry.getValue(), depth + 1);
              });
    else if (value.isArray())
      value.elements().forEachRemaining(child -> validate(child, depth + 1));
  }
}
